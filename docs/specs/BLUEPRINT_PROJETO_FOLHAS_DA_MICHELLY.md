# BLUEPRINT DO PROJETO - FOLHAS DA MICHELLY — v1.0

**Versão:** 1.0  
**Data-base:** 20 de agosto de 2026  
**Status:** especificação funcional e arquitetural para início de implementação  
**Produto:** aplicativo desktop corporativo para automação segura de documentos contábeis e e-mails  
**Plataformas:** Windows e macOS  
**Idioma inicial:** Português do Brasil

---

## 1. Resumo executivo

**Folhas da Michelly** será um aplicativo desktop instalável, com funcionamento principal em Windows e suporte integral a macOS, criado para substituir o processo manual de localizar documentos contábeis, descobrir a qual cliente pertencem, conferir informações, redigir e-mails, anexar arquivos, enviar mensagens e produzir registros de auditoria.

O produto deve ser simples para o operador, mas tecnicamente rigoroso. O objetivo não é construir apenas um “disparador de e-mails”. O sistema será uma esteira de processamento documental e comunicação, com cinco responsabilidades centrais:

1. **identificar corretamente o cliente e o contexto de cada documento**;
2. **detectar inconsistências antes do envio**;
3. **montar e revisar mensagens individuais ou em lote**;
4. **enviar por Gmail ou Outlook/Microsoft 365 usando APIs oficiais**;
5. **registrar tudo de forma auditável, sincronizada e recuperável**.

A arquitetura atual do projeto também resolve uma necessidade central: o cadastro de clientes, usuários, templates, perfis de reconhecimento, configurações e parte da auditoria precisam ser **centralizados e sincronizados entre as máquinas**. Portanto, o produto deixa de ser um aplicativo puramente local e passa a seguir uma arquitetura **desktop + nuvem**, sem transformar os documentos reais em arquivos públicos ou colocar o repositório de código como depósito de dados de clientes.

A recomendação técnica é:

| Camada | Decisão principal |
|---|---|
| Desktop | C# + .NET 10 LTS + Avalonia UI + MVVM |
| Sistemas | Windows 11/10 suportados e macOS suportado pelo framework |
| Banco local | SQLite como cache operacional, fila offline e preferências |
| Backend | ASP.NET Core Web API em .NET 10 |
| Banco central | PostgreSQL gerenciado |
| Tempo real | SignalR/WebSocket para atualizações de cadastro e configuração |
| E-mail | Microsoft Graph e Gmail API, ambos com OAuth 2.0 |
| Segredos locais | abstração de cofre seguro: Windows Credential Manager/DPAPI e macOS Keychain |
| Atualizações | pipeline CI/CD + releases assinadas + atualizador cross-platform |
| Atualizador recomendado | Velopack, após validação final de licença e compatibilidade |
| Relatórios | XLSX colorido como formato principal; CSV obrigatório; PDF opcional |
| Código-fonte | repositório Git privado, com cópia local no Mac e origem remota na nuvem |

### Decisão arquitetural principal

A proposta inicial considerava WPF, que é adequada para Windows, mas não atende ao requisito de macOS. O projeto deve migrar para **Avalonia UI**, preservando C#/.NET, MVVM, SQLite, Entity Framework Core e a maior parte da arquitetura conceitual. Assim, o proprietário pode desenvolver no Mac e os operadores podem usar Windows sem manter duas aplicações independentes.

---

## 2. Visão do produto

O cenário atual envolve aproximadamente 100 clientes e um processo predominantemente manual. Após os documentos estarem disponíveis, uma pessoa precisa verificar arquivos, descobrir a empresa correspondente, localizar o endereço de e-mail, redigir a mensagem, anexar os documentos corretos, conferir novamente e enviar.

O fluxo desejado passa a ser:

```text
Importar arquivos ou selecionar pasta
            ->
Ler conteúdo e metadados
            ->
Classificar tipo documental
            ->
Extrair identificadores e competência/período
            ->
Resolver cliente e estabelecimento
            ->
Executar validações
            ->
Agrupar documentos conforme política de envio
            ->
Gerar assunto e corpo do e-mail
            ->
Revisar individualmente OU revisar lote
            ->
Criar rascunho OU enviar
            ->
Registrar auditoria e retorno do provedor
            ->
Organizar arquivos e gerar relatório
```

O aplicativo deve reduzir esforço repetitivo sem retirar o controle humano dos pontos de maior risco.

### Princípio de segurança

**Nenhuma otimização de velocidade pode tornar aceitável o envio de um documento ao destinatário errado.** Em caso de dúvida de identidade, conflito de CNPJ/CPF, documento ambíguo, cliente inativo, destinatário ausente ou validação bloqueante, o sistema deve interromper o envio daquele item e exigir intervenção humana.

---

## 3. O que as amostras reais já revelam sobre o problema

Os PDFs fornecidos servem como amostras de estrutura e demonstram que o reconhecimento não pode depender apenas do nome do arquivo.

Foram observadas estruturas representativas de:

- demonstrativo/aviso e recibo de férias;
- guia do FGTS Digital;
- folha de pagamento multipágina;
- Documento de Arrecadação de Receitas Federais, usado no fluxo de INSS/tributos;
- recibos de pagamento de 13º salário e adiantamento de 13º;
- recibos de pró-labore;
- termo de rescisão do contrato de trabalho.

### Consequências arquiteturais extraídas das amostras

1. **O nome do arquivo não é suficiente.** Há arquivos com nomes genéricos; a identificação deve privilegiar o conteúdo.
2. **Um PDF pode conter diversos identificadores pessoais e empresariais.** Não basta localizar “qualquer CNPJ” ou “qualquer CPF”. É necessário entender o papel semântico do identificador.
3. **O CNPJ do empregador deve ter prioridade sobre CPFs de empregados.** Em documentos trabalhistas aparecem ambos.
4. **Termos rescisórios podem trazer CNPJ do empregador e CNPJ de entidade sindical.** Um parser genérico que captura a primeira inscrição encontrada pode direcionar o documento ao cliente errado.
5. **Uma empresa pode possuir estabelecimentos/filiais com CNPJs diferentes sob a mesma raiz.** O modelo de dados precisa distinguir cliente jurídico de estabelecimento.
6. **A competência não aparece sempre no mesmo formato.** Pode ser `07/2026`, mês por extenso, ano, período de apuração, período de gozo ou uma data de evento.
7. **Há documentos multipágina.** O sistema deve reconhecer o arquivo como uma unidade e não gerar envios duplicados página a página.
8. **Alguns documentos apresentam vencimento e valor total.** Esses campos podem enriquecer a mensagem e alimentar validações e relatórios.
9. **Há layouts de fontes distintas.** O motor de reconhecimento precisa ser extensível por perfis e parsers específicos, em vez de uma única expressão regular global.

---

## 4. Perfis de usuário e permissões

O produto deve operar com usuários autenticados e papéis explícitos.

### 4.1 Proprietário técnico

Responsável pela manutenção do software, releases, correções, monitoramento técnico e administração global. Trabalhará principalmente em macOS.

Permissões esperadas:

- administrar versões e canais de atualização;
- gerenciar feature flags e bloqueios emergenciais;
- visualizar logs técnicos permitidos;
- administrar perfis de reconhecimento;
- administrar organizações e usuários quando necessário;
- executar diagnóstico e suporte sem visualizar conteúdo sensível além do estritamente necessário.

### 4.2 Administrador do escritório

Pode cadastrar clientes, usuários, remetentes autorizados, templates, regras de envio e revisar auditorias.

### 4.3 Gestor

Pode revisar lotes, aprovar envios, corrigir cadastros, acessar relatórios e gerenciar operadores conforme autorização.

### 4.4 Operador

Pode importar arquivos, corrigir associações, revisar itens, criar rascunhos e enviar apenas quando possuir permissão.

### 4.5 Leitor/Auditoria

Acesso somente leitura a histórico e relatórios, sem capacidade de alterar cadastro ou enviar mensagens.

### Regra de autorização

As ações de maior risco devem ser controladas no servidor e também refletidas na interface. Esconder um botão não é controle de acesso suficiente.

---

## 5. Arquitetura de alto nível

```text
┌─────────────────────────────────────────────────────────────┐
│                 FOLHAS DA MICHELLY - DESKTOP                │
│              Windows                 macOS                  │
│                                                             │
│ Avalonia UI / MVVM                                           │
│ Importação -> Parser -> Validação -> Revisão -> E-mail      │
│ SQLite local / fila offline / cache / cofre de tokens       │
└───────────────┬───────────────────────┬─────────────────────┘
                │ HTTPS/TLS             │ OAuth 2.0
                │                       │
        ┌───────▼──────────┐      ┌─────▼──────────────────┐
        │ API Folhas       │      │ Gmail / Microsoft 365 │
        │ ASP.NET Core     │      │ APIs oficiais         │
        │ Auth + RBAC      │      └────────────────────────┘
        │ SignalR          │
        └───────┬──────────┘
                │
      ┌─────────▼───────────┐
      │ PostgreSQL central  │
      │ clientes            │
      │ usuários            │
      │ templates           │
      │ perfis de parser    │
      │ auditoria           │
      │ configurações       │
      └─────────────────────┘

Releases: Git privado -> CI/CD -> build Windows/macOS -> assinatura -> feed de atualização
```

### O que fica local

- documentos que o operador importou, salvo opção futura de armazenamento central criptografado;
- cache SQLite;
- fila de sincronização pendente;
- tokens de provedor protegidos pelo cofre do sistema operacional;
- logs técnicos locais com redução de dados pessoais;
- prévias temporárias e índices de processamento.

### O que fica centralizado

- usuários, papéis e permissões;
- cadastro de clientes, pessoas e estabelecimentos;
- destinatários e regras de roteamento;
- modelos de assunto e mensagem;
- perfis de reconhecimento e validação configuráveis;
- feature flags;
- metadados de lotes e auditoria;
- versões mínimas suportadas e avisos operacionais.

### O que não deve ir para o repositório Git

- PDFs reais;
- banco de clientes;
- planilhas reais;
- tokens OAuth;
- senhas;
- arquivos `.env` de produção;
- chaves de assinatura;
- backups de produção;
- logs contendo dados pessoais.

---

## 6. Cadastro central de clientes

O cadastro deixa de ser apenas uma tabela simples e passa a representar corretamente PF, PJ e estabelecimentos.

### 6.1 Cliente

Campos principais:

- ID interno imutável;
- tipo: Pessoa Jurídica ou Pessoa Física;
- razão social ou nome completo;
- nome de preferência opcional;
- código interno opcional;
- documento principal normalizado: CNPJ ou CPF;
- status: ativo/inativo;
- observações internas opcionais;
- template padrão de assunto opcional;
- template padrão de mensagem opcional;
- data de criação e atualização;
- versão do registro para controle de concorrência.

### 6.2 Estabelecimentos

Uma PJ pode possuir matriz e filiais. Cada estabelecimento pode ter:

- CNPJ completo;
- CNPJ raiz normalizado;
- nome do estabelecimento;
- apelido interno;
- status ativo/inativo;
- código interno;
- regras de envio específicas, se necessárias.

### 6.3 Identificadores adicionais

Usar uma tabela extensível de identificadores permite relacionar:

- CNPJ completo;
- raiz de CNPJ;
- CPF;
- código interno do sistema contábil;
- inscrição ou alias conhecido;
- razão social normalizada;
- nomes anteriores, quando necessário.

A correspondência automática deve sempre priorizar identificadores exatos. Correspondência aproximada por nome serve apenas como **sugestão para revisão**, nunca como autorização automática de envio.

### 6.4 Destinatários

Para cada cliente, permitir:

- e-mail principal;
- e-mail secundário;
- e-mail interno;
- nome do contato;
- papel do endereço: Para, CC, Cópia interna ou outro perfil configurável;
- status ativo/inativo;
- validade opcional;
- regras por tipo de documento, caso sejam necessárias no futuro.

### 6.5 Cliente inativo

Um cliente inativo:

- continua visível no histórico;
- não pode ser autoassociado para novo envio;
- não pode receber mensagens sem uma reativação ou exceção explícita de administrador;
- deve gerar bloqueio visível se um documento novo corresponder a ele.

---

## 7. Motor de importação de documentos

### 7.1 Entrada

O operador deve poder:

- selecionar uma pasta;
- arrastar e soltar arquivos;
- selecionar múltiplos arquivos;
- importar subpastas opcionalmente;
- reprocessar um arquivo após correção de regra, mantendo o histórico.

### 7.2 Extensões

A arquitetura deve usar uma interface `IDocumentExtractor` por MIME/extensão.

**Obrigatório na primeira versão:** PDF digital com texto extraível.

**Arquitetura preparada desde o início:** DOCX, XLSX, CSV, PNG/JPG e PDFs escaneados. A implementação dessas extensões pode ser ativada por fase conforme amostras e testes existirem.

### 7.3 Pipeline

```text
Arquivo
  -> integridade e tamanho
  -> SHA-256
  -> detecção de MIME
  -> extração textual nativa
  -> classificação do tipo documental
  -> parser específico do tipo
  -> extração semântica de campos
  -> resolução do cliente/estabelecimento
  -> validações
  -> score de confiança
  -> agrupamento
  -> revisão humana
```

### 7.4 OCR

OCR deve ser fallback, não primeira opção. Os exemplos atuais demonstram PDFs com texto extraível; portanto, o MVP não precisa pagar o custo de OCR em todos os arquivos.

Quando um arquivo não contiver texto utilizável:

- marcar como `NecessitaOCR`;
- permitir OCR local opcional em fase posterior;
- nunca usar OCR de baixa confiança para liberar envio sem revisão.

---

## 8. Reconhecimento semântico e perfis documentais

Cada tipo de documento deve possuir um perfil versionado.

Um perfil pode conter:

- nome e código do tipo;
- palavras/âncoras necessárias;
- âncoras proibidas;
- padrões de CNPJ/CPF por papel semântico;
- campos de empresa/empregador;
- padrões de competência e período;
- padrões de vencimento;
- padrões de valores totais;
- regras de validação;
- política de agrupamento;
- prioridade;
- versão;
- status ativo/inativo.

### Benefício operacional

Perfis simples podem ser sincronizados pela nuvem. Assim, uma mudança de layout que exige apenas novo regex, nova âncora ou novo mapeamento pode ser corrigida centralmente sem aguardar uma nova versão do executável. Mudanças que alterem código continuam exigindo release.

### Evidência de reconhecimento

A tela de detalhes deve mostrar por que o sistema chegou a uma conclusão:

```text
Tipo: Folha de Pagamento
Cliente: Empresa Alfa
Identificador usado: CNPJ do Empregador
Valor extraído: **.***.***/****-**
Página: 1
Competência: 07/2026
Confiança: Alta
```

Dados sensíveis podem ser mascarados visualmente quando o usuário não precisar vê-los por inteiro.

---

## 9. Algoritmo de resolução de cliente

A resolução deve seguir uma ordem determinística:

1. identificador do **empregador/cliente** com CNPJ completo e papel semântico confirmado;
2. estabelecimento cadastrado com aquele CNPJ;
3. CNPJ raiz, quando o documento traz apenas a raiz e ela é única na organização;
4. CPF do cliente, quando o cliente cadastrado é pessoa física e o campo semântico representa o contratante/cliente;
5. código interno exato;
6. razão social/nome completo normalizado e exato;
7. alias explícito cadastrado;
8. sugestão aproximada por nome, **somente para intervenção humana**.

### Conflitos que bloqueiam

- dois clientes ativos com o mesmo identificador primário;
- arquivo contendo identificadores empresariais de raízes diferentes sem regra conhecida;
- CNPJ do empregador diferente do estabelecimento escolhido;
- apenas CPF de empregado encontrado, sem identificador de cliente;
- CNPJ sindical ou terceiro identificado no lugar do empregador;
- cliente inativo;
- ausência de destinatário válido.

---

## 10. Classificação de documentos

O catálogo inicial deve prever ao menos:

- Folha de Pagamento;
- FGTS Digital;
- INSS/DARF/DCTFWeb conforme o documento de arrecadação usado pelo escritório;
- Férias/Aviso de Férias/Recibo de Férias;
- Recibo de 13º Salário;
- Adiantamento de 13º Salário;
- Pró-Labore;
- Termo de Rescisão;
- Documento não classificado;
- Tipo personalizado cadastrado posteriormente.

O sistema deve permitir adicionar um novo tipo sem reescrever a lógica central.

---

## 11. Competência, período e contexto temporal

Não reduzir todos os documentos a uma única coluna `MM/AAAA`.

Criar um objeto de período capaz de representar:

- competência mensal;
- competência anual;
- período de apuração;
- período inicial/final;
- vencimento;
- data do evento;
- período de férias;
- período de rescisão;
- texto original quando não houver normalização segura.

A interface pode exibir uma coluna amigável chamada **Competência/Período**, mas internamente os campos devem ser distintos.

---

## 12. Motor de validação e conferência

O sistema deve ajudar a encontrar problemas antes do e-mail, sem se apresentar como substituto do software contábil ou como certificador jurídico de cálculos.

### 12.1 Níveis

- `INFO`: informação útil, não exige ação;
- `AVISO`: possível anomalia, permite continuar mediante revisão;
- `ERRO`: item precisa ser corrigido;
- `BLOQUEIO`: envio proibido até resolver a causa.

### 12.2 Validações universais

- arquivo legível;
- hash calculado;
- arquivo não vazio;
- cliente resolvido de forma não ambígua;
- cliente ativo;
- destinatário válido;
- documento reconhecido ou manualmente classificado;
- ausência de duplicidade proibida;
- consistência entre lote, cliente e período;
- tamanho total de anexos dentro da capacidade configurada;
- nenhum arquivo alterado após aprovação.

### 12.3 Campos vazios

Perfis documentais podem definir campos obrigatórios. Exemplo: documento que deveria conter CNPJ do empregador, competência e valor total mas apresenta campo ausente pode receber `ERRO` ou `BLOQUEIO` conforme a regra.

### 12.4 Cálculos e valores absurdos

Adotar três camadas:

1. **aritmética determinística**, apenas onde os campos foram extraídos com alta confiança; por exemplo, somas, total de proventos menos descontos e totais declarados;
2. **regras de faixa configuráveis**, como valor negativo onde não deveria existir;
3. **detecção de anomalia histórica**, opcional e sempre como aviso, por exemplo uma variação muito alta em relação às competências anteriores.

Nunca “corrigir” o PDF automaticamente. O sistema sinaliza e o profissional decide.

### 12.5 Ortografia

O corpo de e-mail e templates podem receber correção ortográfica local. Para o conteúdo de PDFs, o corretor deve ser opcional, porque documentos contábeis contêm siglas, nomes e abreviações que geram falsos positivos.

---

## 13. Modo individual e modo lote

A primeira versão deve nascer com os dois fluxos.

### 13.1 Análise individual

Adequada ao piloto e aos documentos com alertas.

O operador visualiza:

- documento(s);
- cliente e estabelecimento;
- identificadores usados;
- destinatários;
- período;
- alertas;
- assunto;
- corpo da mensagem;
- anexos;
- resultado de validação.

Pode corrigir associação, destinatário ou template antes de aprovar.

### 13.2 Análise em lote

A grade deve permitir:

- selecionar todos os itens elegíveis;
- filtrar por erro, aviso, cliente, período e tipo;
- aprovar todos os itens sem bloqueio;
- manter bloqueados fora da seleção;
- abrir qualquer linha para análise detalhada;
- cancelar ou pausar processamento;
- confirmar novamente antes de envio real.

### 13.3 Aprovação em lote não significa confiança cega

O botão **Aprovar todos os elegíveis** só deve selecionar itens que passaram pelas regras estritas. Um item vermelho/bloqueado nunca pode ser incluído por acidente.

---

## 14. Agrupamento de documentos

O agrupamento deve ser configurável, não rigidamente `CNPJ + competência`.

### Chave padrão

```text
Cliente + estabelecimento/política + período de despacho + perfil de destinatário
```

### Exemplos

- folha + FGTS + documento de arrecadação da mesma competência podem ir em uma mensagem mensal;
- pró-labore pode ser incluído na mesma mensagem ou ter política própria;
- férias e rescisão podem ser eventos independentes;
- documentos de filiais podem ser agrupados no cliente raiz quando o cadastro autorizar;
- documentos com vencimentos diferentes podem ser listados separadamente no corpo do e-mail.

O operador sempre deve poder separar ou unir grupos antes da aprovação, com a alteração registrada em auditoria.

---

## 15. Geração do assunto e corpo do e-mail

### 15.1 Padrão determinístico

O sistema deve funcionar perfeitamente sem IA.

Exemplo de assunto:

```text
Documentos contábeis - {{cliente.nome_preferencia_ou_razao_social}} - {{periodo.rotulo}}
```

Exemplo de corpo:

```text
Olá, {{contato.nome_ou_cliente}},

Encaminhamos em anexo os documentos referentes a {{periodo.rotulo}}:
{{documentos.lista}}

{{vencimentos.bloco_se_existir}}

Pedimos, por gentileza, que confira os anexos. Permanecemos à disposição em caso de dúvidas.

Atenciosamente,
{{escritorio.nome}}
```

### 15.2 Linguagem e gênero

Não inferir gênero a partir do nome. Usar linguagem neutra por padrão. Se o cadastro contiver uma saudação/preferência explicitamente definida, utilizá-la.

### 15.3 Personalização “com a cara do escritório”

Permitir:

- assinatura por usuário ou escritório;
- saudação customizada;
- blocos de rodapé;
- aviso específico por tipo documental;
- observação sobre vencimentos;
- templates por cliente;
- templates por tipo de documento.

### 15.4 IA opcional

Uma camada de IA pode ser adicionada, mas deve permanecer opcional e desligada por padrão.

Uso seguro recomendado:

- reescrever somente o texto do e-mail;
- usar apenas metadados mínimos: nome de preferência, tipos de documentos, período e vencimentos;
- não enviar PDFs, CPFs, salários ou conteúdo integral de folha a um serviço externo sem autorização administrativa explícita e avaliação de privacidade;
- mostrar o texto gerado antes de qualquer envio;
- registrar que houve assistência de IA.

---

## 16. Gmail, Outlook e autenticação

O usuário deve poder conectar uma ou mais contas de envio.

### Experiência desejada

```text
Configurações > Contas de e-mail > Adicionar conta
[ Conectar com Google ]
[ Conectar com Microsoft ]
```

O navegador do sistema abre, o usuário autentica diretamente no provedor e autoriza os escopos necessários.

### Sobre “salvar senha”

O produto deve oferecer **“Manter conectado”**, mas não armazenar a senha do Gmail ou Outlook. A experiência equivalente é persistir tokens OAuth/refresh tokens de forma segura no cofre do sistema operacional. O usuário não precisa digitar a senha a cada sessão, mas a aplicação não conhece nem grava essa senha.

Isso é requisito de segurança e compatibilidade com os provedores modernos.

### Múltiplas contas

Cada conta conectada deve possuir:

- provedor;
- endereço;
- usuário proprietário;
- nome de exibição;
- permissões/capacidades;
- status da conexão;
- data da última validação;
- escopos concedidos;
- indicação de conta padrão.

Tokens não devem ser sincronizados em texto claro pela nuvem.

---

## 17. Rascunhos, testes e envio real

Três modos obrigatórios:

### TESTE

- substitui o destinatário real por endereço de teste configurado;
- preserva no relatório qual seria o destinatário original;
- marca assunto com `[TESTE]`;
- nunca envia para cliente.

### RASCUNHO

- cria mensagem no provedor quando suportado;
- não dispara imediatamente;
- registra ID do rascunho;
- permite conferência externa.

### ENVIO

- exige permissão específica;
- revalida cliente, destinatários, anexos, hash e versão do cadastro segundos antes do envio;
- exibe confirmação final com contagem de clientes e anexos;
- envia cada grupo como mensagem independente.

A primeira implantação deve iniciar em TESTE/RASCUNHO. O modo ENVIO só entra no piloto após o fluxo falso estar estável.

---

## 18. Duplicidade, idempotência e integridade

Para cada arquivo, calcular SHA-256.

### Duplicidade exata

Mesmo hash previamente processado para o mesmo contexto deve gerar bloqueio ou confirmação especial.

### Duplicidade semântica

Dois PDFs diferentes podem representar a mesma obrigação. Perfis documentais podem definir chave semântica, por exemplo:

```text
cliente + tipo documental + período + identificador externo
```

### Fingerprint do despacho

Também registrar uma impressão do envio:

```text
cliente + destinatários + período + lista ordenada de hashes + template renderizado
```

Isso evita que uma repetição automática após timeout envie a mesma mensagem duas vezes.

---

## 19. Auditoria

Auditoria é funcionalidade central, não acessório.

Cada evento relevante deve registrar:

- timestamp UTC e horário local de exibição;
- organização;
- usuário;
- máquina/dispositivo;
- versão do app;
- lote;
- cliente e estabelecimento;
- documento e SHA-256;
- ação;
- destinatários snapshot;
- assunto snapshot;
- tipo de documento;
- período;
- provedor;
- ID de mensagem/rascunho quando houver;
- resultado;
- código de erro;
- origem: automático/manual;
- justificativa de override, quando aplicável.

### Categorias de erro

- Sistema;
- Arquivo;
- Reconhecimento;
- Validação;
- Duplicidade;
- Cadastro;
- Operador;
- Autenticação;
- Provedor de e-mail;
- Rede;
- Sincronização;
- Atualização;
- Incidente.

---

## 20. Relatórios

### Formato principal: XLSX

O relatório deve possuir, no mínimo:

- **Resumo**;
- **Itens processados**;
- **Erros e bloqueios**;
- **Duplicados**;
- **Auditoria**.

### Cores sugeridas

- verde: sucesso/aprovado;
- vermelho: erro/bloqueio;
- amarelo/laranja: aviso;
- azul: rascunho;
- cinza: ignorado/cancelado.

A cor complementa o texto; nunca deve ser a única forma de transmitir status.

### PDF

Exportação PDF pode ser adicionada como resumo executivo, mas XLSX e CSV são prioritários por permitirem filtro, ordenação e análise.

---

## 21. Sincronização em tempo real

O requisito de todos receberem novos cadastros e atualizações implica um servidor central.

### Dados sincronizados

- clientes;
- estabelecimentos;
- destinatários;
- templates;
- regras;
- perfis de reconhecimento;
- usuários e permissões;
- auditoria;
- feature flags;
- configurações compartilhadas.

### Estratégia

- API REST para comandos e consultas;
- SignalR para notificar alterações quase em tempo real;
- versionamento/ETag para evitar sobrescrever alterações concorrentes;
- cache SQLite para velocidade e modo degradado;
- fila de sincronização para eventos locais.

### Conflitos

Nunca usar “último salvamento vence” silenciosamente para campos críticos. Em conflito de e-mail, CNPJ, template ou status, a interface deve avisar e solicitar resolução.

---

## 22. Operação offline e falhas de internet

O app deve continuar abrindo, consultando cache e processando documentos locais quando possível.

### Padrão seguro

- importação, leitura e validação podem funcionar offline;
- criação de e-mail e envio exigem internet;
- o modo de envio real deve, por padrão, exigir que a auditoria central esteja disponível;
- eventos locais pendentes ficam em fila criptografada/protegida para sincronizar depois;
- nunca perder histórico por fechar o aplicativo.

---

## 23. Atualização, patches e manutenção remota

Há três tipos de atualização:

### 23.1 Dados e configuração

Novo cliente, e-mail, template ou regra configurável pode aparecer nas demais máquinas quase imediatamente via sincronização. Não exige reinstalar o aplicativo.

### 23.2 Backend

Correções feitas apenas no servidor entram em produção após deploy do backend e podem beneficiar todos imediatamente.

### 23.3 Aplicativo desktop

Correções no executável exigem nova versão. O fluxo desejado:

```text
Alteração no código
 -> Pull Request
 -> testes automáticos Windows/macOS
 -> versão/tag
 -> builds assinados
 -> publicação no canal de atualização
 -> aplicativo detecta nova versão
 -> usuário instala/reinicia
```

### Atualizações controladas

Manter canais:

- `stable`;
- `beta` para máquinas de teste.

O servidor pode informar `minimum_supported_version` e desabilitar temporariamente o envio em versões conhecidamente defeituosas.

### Kill switch

Uma feature flag remota deve permitir bloquear **Envio real** emergencialmente sem desinstalar o app, caso um bug crítico seja descoberto.

---

## 24. Repositório e estratégia de desenvolvimento

O código deve existir **nos dois lugares**:

1. **localmente no Mac**, onde o proprietário trabalha;
2. **em um repositório Git privado na nuvem**, recomendado GitHub, como fonte de verdade, backup, colaboração e base para CI/CD e Codex cloud.

O repositório não é banco de dados operacional.

### Estrutura inicial

```text
FolhasDaMichelly/
  .github/workflows/
  assets/brand/
  docs/
    specs/
    adr/
  src/
    FolhasDaMichelly.Desktop/
    FolhasDaMichelly.Domain/
    FolhasDaMichelly.Application/
    FolhasDaMichelly.Infrastructure/
    FolhasDaMichelly.Contracts/
    FolhasDaMichelly.Server/
  tests/
    FolhasDaMichelly.Domain.Tests/
    FolhasDaMichelly.Application.Tests/
    FolhasDaMichelly.Infrastructure.Tests/
    FolhasDaMichelly.Server.Tests/
    FolhasDaMichelly.Ui.Tests/
    Fixtures/Synthetic/
  tools/
  AGENTS.md
  README.md
```

---

## 25. Windows e macOS no mesmo projeto

A maior parte do código deve ser comum.

### Builds iniciais

- `win-x64` obrigatório;
- `osx-arm64` obrigatório para Apple Silicon;
- `osx-x64` recomendado se houver Macs Intel;
- `win-arm64` preparado para fase posterior, se necessário.

### Desenvolvimento a partir do Mac

É totalmente viável. O aplicativo Avalonia pode ser desenvolvido e executado no Mac, e builds Windows podem ser gerados por CI ou cross-compilação. Entretanto, testes reais de interface, instalador, antivírus, permissões e integração com o Windows devem rodar em Windows antes de cada release estável.

### Matriz de CI

Executar pelo menos:

```text
ubuntu: testes de lógica/backend quando aplicável
windows: build, testes e pacote Windows
macOS: build, testes, assinatura/notarização e pacote macOS
```

---

## 26. Segurança e LGPD por design

Os documentos processados contêm dados pessoais e financeiros. O sistema deve ser projetado para minimização e rastreabilidade.

### Regras obrigatórias

- TLS em toda comunicação com backend;
- nunca salvar senha de Gmail/Outlook;
- tokens OAuth em cofre seguro;
- senhas do próprio sistema somente com hash forte, se esse método de login for adotado;
- RBAC no servidor;
- dados de uma organização nunca acessíveis por outra;
- logs com mascaramento de CPF/CNPJ quando o valor integral não for necessário;
- backups criptografados;
- nenhuma telemetria com conteúdo de folha;
- arquivos temporários apagados de forma previsível;
- expiração de sessão;
- capacidade de revogar dispositivos e conexões de e-mail;
- trilha de auditoria de alterações de cadastro;
- princípio de menor privilégio nos escopos de APIs de e-mail.

### Exclusão de cliente

Preferir inativação/soft delete em registros que participam da auditoria. A remoção definitiva deve respeitar política de retenção e obrigações do escritório.

---

## 27. Identidade visual

O aplicativo se chamará **Folhas da Michelly**.

### Ativos recebidos

Foram fornecidas três referências visuais:

- logotipo do escritório em preto, dourado e branco;
- retrato profissional;
- fotografia da equipe/sócios.

### Recomendação de uso

**Logotipo do escritório:** base principal da identidade do produto, tela Sobre, login, instalador e documentação.

**Retrato:** opcional em tela de boas-vindas, ajuda, assinatura institucional ou material de apresentação; não deve ser o ícone principal do executável.

**Foto da equipe:** opcional na tela Sobre/Escritório; não é necessária para o funcionamento do app.

### Ícone do aplicativo

O arquivo de logotipo atual é raster e de resolução modesta. Para um ícone profissional, obter preferencialmente o vetor original (`SVG`, `AI`, `PDF vetorial`) ou uma versão de alta resolução. Criar uma marca simplificada para tamanhos de 16px a 1024px, preservando a identidade dourado/preto/branco. Exportar ao menos:

- SVG mestre;
- PNG 1024x1024;
- ICO multiresolução para Windows;
- ICNS ou asset equivalente para macOS.

---

## 28. Telas principais

### 28.1 Login

- autenticação do app;
- organização;
- lembrar sessão de forma segura;
- status do servidor.

### 28.2 Dashboard

- lotes recentes;
- pendências;
- documentos bloqueados;
- rascunhos;
- envios do dia;
- problemas de sincronização;
- versão e atualização disponível.

### 28.3 Clientes

- busca;
- filtros ativo/inativo;
- PF/PJ;
- estabelecimentos;
- destinatários;
- templates;
- histórico de mudanças.

### 28.4 Importar documentos

- selecionar pasta/arrastar arquivos;
- barra de progresso;
- contagem por tipo;
- erros de leitura.

### 28.5 Revisão de lote

Colunas sugeridas:

- status;
- arquivo/grupo;
- tipo;
- cliente;
- estabelecimento;
- identificador;
- competência/período;
- destinatário;
- anexos;
- avisos;
- aprovação.

### 28.6 Detalhes

- preview PDF;
- evidências extraídas;
- validações;
- e-mail renderizado;
- histórico/overrides.

### 28.7 Auditoria e relatórios

- filtros;
- linha do tempo;
- exportação.

### 28.8 Configurações

- contas de e-mail;
- templates;
- usuários;
- perfis documentais;
- atualizações;
- segurança;
- backup/cache.

---

## 29. Fluxo de incidente

Se um envio incorreto ocorrer, o sistema não deve fingir que pode “desenviar” universalmente uma mensagem.

Criar ação **Registrar incidente**, contendo:

- mensagem afetada;
- cliente;
- destinatário;
- arquivos;
- operador;
- causa conhecida;
- medidas tomadas;
- status;
- observação do responsável.

Esse fluxo ajuda a investigação e prevenção, mas não substitui procedimentos jurídicos/administrativos de proteção de dados.

---

## 30. Requisitos não funcionais

### Confiabilidade

- nenhuma operação crítica depende apenas de memória RAM;
- lote recuperável após crash;
- transações para mudanças relacionadas;
- idempotência de envio;
- sincronização com retry e backoff;
- snapshots antes de enviar.

### Desempenho

- interface não pode congelar durante leitura de PDFs;
- processamento assíncrono e cancelável;
- cache de resultados de extração pelo hash;
- virtualização da grade de lote;
- análise paralela limitada para não saturar a máquina.

### Acessibilidade

- status não depende apenas de cor;
- navegação por teclado;
- labels acessíveis;
- contraste adequado;
- escala de interface.

### Manutenibilidade

- arquitetura modular;
- interfaces para e-mail, documentos, armazenamento, updater e secret store;
- testes automatizados;
- dependências centralizadas e auditáveis;
- ADRs para decisões importantes;
- migrações versionadas.

---

## 31. Roadmap de implementação

### Fase 0 - Descoberta, repositório e ADRs

- validar .NET/Avalonia estáveis;
- configurar Git privado;
- definir ambientes;
- criar `AGENTS.md`, `EXECUTION_PLAN.md` e ADRs;
- inventariar amostras sem copiar dados reais para o repositório.

### Fase 1 - Scaffold cross-platform

- solução .NET;
- Avalonia;
- Domain/Application/Infrastructure/Server;
- testes;
- CI inicial Windows/macOS.

### Fase 2 - Identidade, backend e sincronização básica

- autenticação do app;
- organização/usuários/RBAC;
- PostgreSQL;
- API;
- cache SQLite;
- sincronização de registros.

### Fase 3 - Cadastro de clientes

- PF/PJ;
- estabelecimentos;
- identificadores;
- destinatários;
- status ativo/inativo;
- importação/exportação.

### Fase 4 - Importação e motor documental

- leitura de PDFs digitais;
- hash;
- classificação;
- perfis;
- campos semânticos;
- evidências;
- fixtures sintéticos inspirados nos layouts.

### Fase 5 - Validação e agrupamento

- regras;
- bloqueios;
- duplicidade;
- competência/período;
- agrupamento configurável.

### Fase 6 - UI individual e lote + e-mail falso

- workflow completo com `FakeEmailProvider`;
- teste, rascunho simulado e envio simulado;
- auditoria e relatórios.

### Fase 7 - Microsoft Graph

- OAuth;
- conexão de conta;
- rascunhos;
- anexos;
- envio;
- tratamento de `202 Accepted` como aceitação, não prova final de entrega.

### Fase 8 - Gmail API

- OAuth desktop;
- mensagens MIME;
- rascunhos;
- envio;
- escopos mínimos.

### Fase 9 - Relatórios, incidentes e hardening

- XLSX colorido;
- CSV;
- PDF opcional;
- incidentes;
- logs;
- segurança.

### Fase 10 - Atualizador e distribuição

- Velopack ou solução equivalente aprovada;
- canais beta/stable;
- CI de release;
- assinatura Windows;
- assinatura/notarização macOS;
- rollback.

### Fase 11 - Piloto supervisionado

- dados de teste;
- uma ou poucas empresas;
- modo rascunho;
- checklist operacional;
- métricas de erros.

### Fase 12 - Produção gradual

- liberar lote completo;
- liberar envio real por papel;
- monitorar;
- retrospectiva e backlog.

---

## 32. Critérios de aceite de alto nível

O produto não deve ser considerado pronto até que:

- rode em Windows e macOS a partir da mesma base de código;
- possa ser instalado por usuário comum conforme política definida;
- usuários e clientes sincronizem entre máquinas;
- um cliente novo cadastrado em uma máquina apareça nas demais sem reinstalação;
- documentos sejam identificados por conteúdo e não apenas por nome;
- empregador seja distinguido de empregado, sindicato e terceiros;
- filiais sejam tratadas corretamente;
- itens ambíguos sejam bloqueados;
- modos individual e lote funcionem;
- `FakeEmailProvider` cubra todo o fluxo antes das APIs reais;
- Gmail e Microsoft funcionem por OAuth sem senha armazenada;
- duplicidade exata e envio idempotente sejam testados;
- auditoria sobreviva a reinicialização;
- relatório XLSX represente sucesso, erro, aviso e duplicidade;
- atualizações possam ser distribuídas em canais;
- build Windows seja produzido por CI mesmo que o desenvolvimento cotidiano ocorra em Mac;
- arquivos e credenciais reais não sejam versionados no Git;
- exista documentação de recuperação e suporte.

---

## 33. O que permanece fora do MVP

A arquitetura pode prever, mas não deve atrasar a primeira versão estável por:

- portal web para clientes;
- aplicativo móvel;
- assinatura eletrônica;
- envio automático sem aprovação humana;
- correção automática do conteúdo de PDFs;
- IA decidindo destinatário;
- OCR universal de todos os arquivos;
- armazenamento central obrigatório de PDFs reais;
- integração por automação de tela com sistemas contábeis;
- marketplace/SaaS multi-escritório completo;
- interpretação jurídica automática de rescisões;
- certificação de exatidão tributária.

---

## 34. Diretriz final

O projeto deve ser construído para que o operador pense em **clientes, documentos, alertas e envios**, não em tecnologia. A complexidade de APIs, hashes, sincronização, tokens, filas e versões fica escondida atrás de uma interface clara.

A meta não é apenas economizar cliques. É transformar uma tarefa repetitiva e sujeita a erro humano em um processo **assistido, verificável, auditável, centralizado e atualizável**, mantendo a decisão final com o profissional responsável.
