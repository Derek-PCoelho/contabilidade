# Folhas da Michelly (Java): implantação

Este guia vale para a versão Java: Server com Spring Boot e PostgreSQL 17, e Desktop com JavaFX 25.

## 1. Perfis de uso

| Perfil | Quando usar | Precisa de Server? | Login |
|---|---|---|---|
| **Local** | Um computador. Os dados ficam só nele. | Não | Não |
| **Conectado** | Vários computadores, cadastro compartilhado e envio real com controle central | Sim | Sim (OIDC + PKCE pelo navegador) |

O Desktop escolhe o perfil pela variável `FOLHAS_API_BASE_ADDRESS`:

- **Vazia:** perfil Local.
- **Com o endereço HTTPS do Server:** perfil Conectado.

No perfil Conectado, sem login, nenhuma operação é liberada.

## 2. Server

### 2.1 Pré-requisitos

- Docker com Compose v2, ou Java 25 e PostgreSQL 17.
- Um nome DNS com HTTPS válido, por exemplo `folhas.seu-escritorio.com.br`.
- Um proxy TLS na frente do Server (Caddy, nginx ou o balanceador da nuvem).
- Um certificado PKCS#12 para assinar os tokens:
  ```bash
  keytool -genkeypair -alias folhas -keyalg RSA -keysize 3072 -validity 1095 \
    -storetype PKCS12 -keystore deploy/java/secrets/oidc-signing.p12 -dname "CN=folhas-oidc"
  ```

### 2.2 Configuração

```bash
cp deploy/java/.env.example deploy/java/.env      # preencha e nunca versione este arquivo
```

Chaves obrigatórias fora de Development/Testing:

| Chave | Conteúdo |
|---|---|
| `FOLHAS_OIDC_ISSUER` | O mesmo endereço público HTTPS usado pelos Desktops. |
| `FOLHAS_OIDC_SIGNING_CERTIFICATE_PATH` / `_PASSWORD` | Certificado de assinatura. |
| `FOLHAS_ALLOWED_HOSTS` | Nome(s) DNS aceitos. |
| `FOLHAS_FORWARDED_HEADERS_KNOWN_PROXIES` | IP(s) do proxy TLS. Os cabeçalhos `X-Forwarded-*` de outras origens são ignorados (pendência 4.11). |

Todas as travas de envio começam **desligadas** (pendência 4.18). As que liberam envio real são `FOLHAS_PHASE10_EMAIL_SEND_ENABLED` e `FOLHAS_PHASE12_*`. Elas só devem ser ligadas depois do piloto supervisionado; ver `docs/PILOT.md` e `docs/PRODUCTION.md`.

### 2.3 Primeira subida

```bash
cd deploy/java
docker compose up -d db
# Migrações, organização e 1º administrador (idempotente; pode rodar a cada release):
FOLHAS_ADMIN_PASSWORD='senha-forte-do-admin' docker compose run --rm server provision \
  --org-name="Contadores Associados" --org-slug=contadores \
  --admin-email=ti@seu-escritorio.com.br --admin-name="Equipe técnica"
docker compose up -d server
curl -fsS http://127.0.0.1:8080/health/ready
```

No primeiro login, o administrador precisa cadastrar a verificação em duas etapas (TOTP). O navegador mostra o QR code.

### 2.4 Atualização do Server

```bash
git pull && cd deploy/java
docker compose build server
docker compose run --rm server provision ...   # aplica migrações novas, sem apagar dados
docker compose up -d server
```

### 2.5 Backup do banco

```bash
docker compose exec db pg_dump -U folhas -Fc folhas > folhas-$(date +%F).dump
# restauração de teste (ensaio obrigatório antes da produção gradual):
docker compose exec -T db pg_restore -U folhas -d folhas_restore --clean < folhas-AAAA-MM-DD.dump
```

## 3. Desktop

### 3.1 Instaladores

Os instaladores são gerados **no próprio sistema de destino**, porque o jpackage não faz compilação cruzada:

```bash
cd java
./gradlew :desktop:packageNative -PappVersion=1.0.0
# Windows → desktop/build/package/Folhas da Michelly-1.0.0.msi
# macOS   → desktop/build/package/Folhas da Michelly-1.0.0.dmg
# Linux   → desktop/build/package/Folhas da Michelly/ (app-image, só para testes)
```

O instalador já inclui o Java; o computador do escritório não precisa ter Java instalado. A assinatura está descrita em `PACKAGING.md`.

### 3.2 Configuração no computador

| Variável | Uso |
|---|---|
| `FOLHAS_API_BASE_ADDRESS` | Endereço HTTPS do Server. Vazio = perfil Local. |
| `FOLHAS_DESKTOP_DATA_DIR` | Pasta de dados alternativa. O padrão é o mesmo da versão .NET: `%LOCALAPPDATA%\FolhasDaMichelly` no Windows e `~/Library/Application Support/FolhasDaMichelly` no macOS. |
| `FOLHAS_UPDATE_FEED`, `FOLHAS_UPDATE_PUBLIC_KEYS`, `FOLHAS_UPDATE_PUBLISHER` | Canal de atualização assinado. Sem esses valores, as atualizações ficam desligadas. |

**Compatibilidade com a versão .NET.** O Desktop Java abre o `cache.db` existente usando a chave guardada no cofre do sistema (DPAPI ou Keychain). As preferências, o acervo e a pasta de relatórios continuam nos mesmos lugares.

### 3.3 Login (perfil Conectado)

1. Abra **Configurações → Conta do escritório → Entrar com a conta do escritório**.
2. O navegador do sistema abre a página do Server: login, 2FA quando exigido, e retorno automático ao aplicativo.
3. A sessão fica guardada no cofre do sistema e é renovada automaticamente. **Sair da conta** revoga a sessão deste computador no Server.

O escopo dos dados locais passa a ser o da organização, e as permissões vêm da função do usuário. O envio real exige a permissão `email.send` e a autorização do preflight central.

## 4. Verificação pós-implantação

- [ ] `GET /health/ready` responde 200 pelo endereço público.
- [ ] `/.well-known/openid-configuration` mostra `issuer` igual ao endereço público.
- [ ] O login pelo Desktop funciona, e **Sair da conta** encerra a sessão (a chamada seguinte ao Server responde 401).
- [ ] Fluxo completo em **Teste seguro**: importar, conferir, liberar, preparar, aprovar e concluir. Os relatórios são gerados.
- [ ] Cópia de segurança salva e restaurada em outro computador.
- [ ] Backup do PostgreSQL restaurado num banco de ensaio.
