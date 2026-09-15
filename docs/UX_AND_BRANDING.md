# Experiência do usuário e identidade visual

## Objetivo

O aplicativo deve apresentar o trabalho na linguagem do escritório: clientes, documentos, pendências, mensagens, envios e histórico. Termos de implementação, códigos de erro e marcos de desenvolvimento ficam fora do fluxo cotidiano e aparecem somente em **Configurações > Detalhes técnicos para suporte**.

Esta reformulação foi executada depois da Fase 7 e antes de qualquer autorização da Fase 8. Ela não altera regras contábeis, reconhecimento, aprovação, provedores ou gates de segurança.

## Arquitetura de informação

A navegação lateral é permanente e contém sete destinos:

1. **Início** — resumo, sequência de trabalho e atalhos;
2. **Clientes** — busca e cadastro unificado;
3. **Documentos** — importação, conferência e aprovação;
4. **Envios** — preparação, revisão, aprovação e conclusão;
5. **Relatórios** — geração de XLSX/CSV;
6. **Histórico** — decisões e operações relevantes;
7. **Configurações** — conta de e-mail, cópia de segurança, apresentação e suporte.

Não existem abas centrais misturando jornadas distintas.

## Cadastro unificado de clientes

O cadastro é um formulário contínuo com duas seções principais:

- **Dados essenciais:** tipo de cliente, CPF/CNPJ, razão social/nome completo e campos opcionais de preferência;
- **Contato de entrega:** endereço que receberá os documentos e eventuais cópias.

As demais estruturas continuam necessárias ao domínio, mas aparecem recolhidas e explicadas:

- **Filiais ou estabelecimentos:** somente para PJ com matriz/filiais; permite resolver o CNPJ da unidade sem misturar clientes;
- **Outros nomes e códigos:** aliases e códigos que realmente aparecem nos documentos; CPF/CNPJ principal não é repetido aqui;
- **Mensagem personalizada:** somente quando o cliente precisa de texto diferente do padrão do escritório;
- **Observações internas:** contexto operacional que não entra automaticamente no e-mail.

“Destinatário” significa o contato de entrega da empresa. Na maioria dos casos existe apenas um; múltiplos contatos e papéis explícitos continuam disponíveis para os casos reais de cópia ou setor interno.

## Jornadas principais

### Documentos

```text
Importar PDFs -> Conferir pendências -> Aprovar grupos
```

O detalhe principal mostra linguagem amigável. Correção manual, reorganização e evidências de reconhecimento ficam em painéis recolhidos para uso excepcional.

### Envios

```text
Preparar -> Conferir mensagem e anexos -> Aprovar -> Concluir
```

Modos técnicos são apresentados como **Teste seguro**, **Salvar como rascunho** e **Enviar aos destinatários**. Cenários artificiais de falha ficam somente nos detalhes de suporte.

### Cópia de segurança

O antigo editor de JSON foi removido do fluxo comum. Em Configurações, o usuário:

1. salva uma cópia em arquivo;
2. escolhe uma cópia existente;
3. recebe uma prévia do que será criado/atualizado;
4. confirma a restauração explicitamente.

O arquivo `.fdmbackup` é protegido por senha e autenticado. A tela explica que a senha não é recuperável e que PDFs/tokens exigem cópias separadas. O tenant continua derivado da sessão autenticada e o formato versionado permanece interno.

## Identidade visual oficial

O proprietário forneceu e autorizou expressamente o uso, o versionamento e a distribuição das três imagens no aplicativo. Elas são recursos Avalonia oficiais em `src/FolhasDaMichelly.Desktop/Assets/Branding`, seguem em todos os builds Windows/macOS e não dependem de instalação ou configuração por estação:

```text
Assets/Branding/
  logo1.png
  aaa1.png
  aaa2.png
```

Uso visual:

- `logo1.png`: ícone da janela e retrato/logomarca do aplicativo no cabeçalho lateral;
- `aaa1.png`: apresentação discreta na visão geral e na área Sobre;
- `aaa2.png`: assinatura institucional no rodapé lateral e na área Sobre.

Os textos alternativos descrevem retrato, equipe e marca institucional. A posição e as dimensões evitam competir com os fluxos de trabalho. A rastreabilidade dos arquivos está em `docs/BRAND_ASSETS.md`.

## Diretrizes visuais e de acessibilidade

- conteúdo principal claro e navegação escura, com dourado apenas para ação/destaque;
- contraste de texto preservado;
- estado representado por texto, não apenas por cor;
- controles com rótulos de negócio e áreas clicáveis consistentes;
- suporte a teclado herdado dos controles Avalonia;
- atalhos `Ctrl+1` a `Ctrl+7`, título exposto como heading e estado/progresso como região viva;
- listas e formulários com rolagem independente em resoluções menores;
- históricos exibem no máximo os 500 eventos mais recentes por visão e importações podem ser interrompidas cooperativamente;
- detalhes técnicos e evidências progressivamente revelados, sem poluir o primeiro contato.

## Limites

- Fase 8/Gmail não foi iniciada por esta reformulação; foi autorizada e implementada offline em entrega posterior;
- nenhuma regra de cliente, parser, agrupamento, aprovação ou envio foi relaxada;
- as imagens oficiais aumentam discretamente o tamanho do executável e qualquer substituição futura exige nova revisão visual e atualização do manifesto de origem;
- smoke visual em Windows físico continua obrigatório antes de release estável.

## Segunda revisão anterior à Fase 9

As capturas `ImgCont1` a `ImgCont5` motivaram uma segunda revisão, sem iniciar o hardening da Fase 9:

- menu lateral passou a usar ícones vetoriais de 18 px, eliminando a variação de glifos e o círculo desproporcional de Clientes;
- cabeçalho alinha título, indicador de proteção e seletor global de competência;
- Início usa texto alinhado à esquerda e quatro passos em colunas iguais;
- ações “Novo cliente” e “Abrir selecionado” ficam logo após a busca; inativação aparece somente em cadastro existente;
- PF mostra CPF e nome completo; PJ mostra CNPJ, razão social, nome fantasia, código e sócios/representantes opcionais;
- CPF/CNPJ recebem máscara durante a digitação, mas o domínio mantém normalização restritiva; raiz de CNPJ continua exigindo coerência com nome;
- sócios têm papel próprio e não aparecem em “outros nomes e códigos” nem resolvem documentos da empresa;
- Documentos começa por uma única área de importação com arrastar, escolher PDFs e importar uma pasta inteira;
- Envios declara que sua origem são os grupos aprovados em Documentos e oferece retorno/avanço explícitos;
- Relatórios mostra competência e pasta de destino;
- Configurações apresenta Google e Microsoft em cartões de conexão, preferência de sessão e as três pastas de trabalho;
- `logo1.png` também gera `app-icon.ico` e `app-icon.icns`; o ícone foi verificado em um bundle macOS no Finder.

O seletor ano/mês filtra listas, contadores, lotes, histórico e relatórios. A pasta do acervo organiza novas importações em `AAAA/MM`; conteúdo repetido continua bloqueado por SHA-256 e chave semântica antes de qualquer aprovação.

## Ampliação da Fase 9

Histórico concentra também **Ocorrências**, com linguagem direta: relatar, conter, apurar, resolver e encerrar. A ocorrência sempre nasce de uma tentativa registrada; a interface não oferece exclusão e explica a redação de dados sensíveis. Configurações apresenta backup protegido e revisão de retenção somente leitura. Documentos mostra progresso e botão **Interromper**, preservando itens já concluídos. Esses controles seguem a mesma hierarquia visual e não expõem códigos de implementação no fluxo cotidiano.
