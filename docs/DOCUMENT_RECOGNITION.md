# Reconhecimento documental — Fase 4

## Escopo e segurança

O Desktop aceita somente arquivos `.pdf` cuja assinatura comece com `%PDF-`. O arquivo é lido localmente, limitado a 25 MB, 100 páginas, 2 milhões de caracteres extraídos e timeout cooperativo de 20 segundos. O hash SHA-256 é calculado em streaming. PDF, texto integral e caminho absoluto nunca são enviados à API.

Antes da resolução, a aplicação remove todos os campos que não podem identificar o cliente. Somente `EmployerTaxId`, `ClientTaxId`, `EstablishmentTaxId`, `EmployerName`, `ClientName` e `InternalCode` podem seguir ao servidor; o snippet enviado é mascarado. `EmployeeCpf`, `PartnerCpf`, `UnionTaxId`, `DocumentIssuerTaxId`, valores e datas permanecem locais.

## Perfis v1

| Tipo | Âncoras combinadas | Identificador elegível | Campos contábeis iniciais |
|---|---|---|---|
| Férias | `RECIBO DE FERIAS` + `PERIODO DE GOZO` | CNPJ do empregador | empregado, gozo e líquido |
| FGTS Digital | `FGTS DIGITAL` + `COMPETENCIA` | CNPJ do empregador | razão social, competência, vencimento e total |
| Folha | `FOLHA DE PAGAMENTO` + `TOTAL DA FOLHA` | CNPJ do empregador | competência e total; um PDF multipágina é uma unidade |
| DARF | `DARF` + `PERIODO DE APURACAO` | CNPJ do contribuinte | razão social, apuração, vencimento e total |
| 13º salário | `DECIMO TERCEIRO SALARIO` + `EMPREGADO CPF` | CNPJ do empregador | empregado, competência e líquido |
| Pró-labore | `PRO-LABORE` + `SOCIO CPF` | CNPJ da empresa | sócio, competência e líquido |
| Rescisão | `TERMO DE RESCISAO` + `TRABALHADOR CPF` | CNPJ do empregador | trabalhador, sindicato, desligamento e líquido |

Os rótulos são normalizados para caixa/acentuação, mas o valor é extraído somente da linha contextual correspondente. Cada campo conserva página, caixa aproximada, snippet e confiança. No 13º, cabeçalhos repetidos com o mesmo CNPJ são deduplicados, enquanto inscrições distintas são preservadas para a regra de coerência de raiz da Fase 5. Texto vazio produz `NeedsOcr`; OCR não é acionado.

## Ordem de resolução

1. CNPJ exato do cliente/empregador;
2. CNPJ exato do estabelecimento;
3. raiz CNPJ única com nome coerente;
4. CPF exato somente quando o papel é `ClientTaxId` e o cadastro é PF;
5. código interno exato;
6. razão social exata;
7. alias exato;
8. fuzzy somente como alternativa para decisão humana.

Cliente/estabelecimento inativo, raiz ambígua e ausência de correspondência produzem bloqueio. A organização é obtida da identidade autenticada; não existe campo de tenant no request.

## Fixtures e evolução

Os artefatos ficam em `tests/Fixtures/Documents/{Pdf,Text,Expected}` e são reproduzíveis por `generate_fixtures.py`. Todos exibem `DADOS SINTETICOS - SEM VALIDADE`. Mudanças de perfil ou PdfPig devem incrementar a versão do motor, regenerar conscientemente os goldens, rodar a suíte completa em Windows/macOS e revisar licença/vulnerabilidades.

O reconhecimento da Fase 4 alimenta o motor local da Fase 5 descrito em `docs/VALIDATION_AND_GROUPING.md`. Vencimento, duplicidade, grouping, split/merge, override e snapshot são tratados depois da resolução. OCR, composição, providers e envio permanecem inexistentes.
