# Manifesto de assets de identidade

## Autorização

Em 2026-08-21, o proprietário do projeto forneceu diretamente os três arquivos abaixo e autorizou expressamente seu uso, versionamento no repositório privado, incorporação aos builds e distribuição no aplicativo Folhas da Michelly para o proprietário e seus clientes.

Essa autorização é específica para os arquivos identificados por nome e SHA-256. Ela não transforma os arquivos em conteúdo público nem autoriza reutilização fora deste projeto.

## Arquivos oficiais

| Arquivo | SHA-256 | Finalidade no aplicativo |
|---|---|---|
| `logo1.png` | `8e4f70f3295f7d572eacd89147b57a93c564f39d385effcbe683ba34e37ac919` | ícone da janela e retrato/logomarca no cabeçalho lateral |
| `aaa1.png` | `d521cfc5512c2caebb1b6b93183109de6334426798b580bd0de422f048a60d42` | imagem discreta da equipe na página inicial e na área Sobre |
| `aaa2.png` | `ed1bf0373ed2538f18ce0e9318c82f632d10297e7e2f0b657c03790f1cebf6e0` | marca Contadores Associados no rodapé lateral e na área Sobre |

Os arquivos versionados ficam em `src/FolhasDaMichelly.Desktop/Assets/Branding` e são incluídos pelo padrão `AvaloniaResource Include="Assets/**"` do projeto Desktop.

## Regra para futuras contribuições

Arquivos, imagens e dados fornecidos pelo proprietário podem integrar o projeto quando a autorização indicar os arquivos e a finalidade. A contribuição deve registrar origem, escopo de distribuição e, para binários, hash de integridade. Credenciais, tokens e segredos permanecem proibidos no Git; documentos contábeis operacionais exigem autorização nominal adicional e revisão de segurança.
