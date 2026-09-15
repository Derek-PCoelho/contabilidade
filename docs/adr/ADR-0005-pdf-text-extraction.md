# ADR-0005: Abstração e biblioteca de extração PDF

- Status: Accepted
- Data: 2026-08-20
- Decisores: proprietário técnico e equipe do projeto

## Contexto

O MVP precisa ler PDFs digitais cross-platform e preservar evidência sem tratar a primeira inscrição encontrada como cliente. A biblioteca deve ser permissiva, ativa e isolada porque layouts, arquivos malformados e versões podem exigir substituição. OCR e viewer sofisticado não pertencem ao caminho crítico inicial.

## Decisão

- Definir `IPdfTextExtractor` e `IDocumentExtractor`; Domain/Application não referenciam PdfPig.
- Usar PdfPig 0.1.15 estável como candidato inicial para a prova de conceito, sob licença Apache-2.0.
- Abrir arquivos por stream, calcular hash separadamente, impor limites configuráveis de tamanho/páginas/tempo e tratar explicitamente PDFs corrompidos, criptografados ou com profundidade maliciosa.
- Extrair texto por página com coordenadas/evidências mínimas; classificação e papéis semânticos ficam em parsers próprios, não na biblioteca.
- Marcar texto vazio como `NeedsOcr`; não ativar OCR automaticamente no MVP.
- Usar o visualizador do sistema como fallback. Renderização embutida e OCR exigem ADR/avaliação próprios.
- Fixar a versão após o PoC; PdfPig está abaixo de 1.0 e suas atualizações não serão automerged.

## Consequências

- A abstração permite trocar o extrator sem contaminar o domínio.
- PdfPig tem releases recentes e builds macOS, mas API pré-1.0 e PDFs complexos exigem corpus sintético e fuzz/limites.
- Extração textual não garante ordem visual perfeita; parsers precisam trabalhar com tokens, posição e âncoras contextuais.

## Gate para Accepted

Na Fase 4, fixtures sintéticos devem comprovar:

1. texto e página/posição nos sete perfis iniciais;
2. folha multipágina sem duplicação de cabeçalho;
3. empregador distinto de empregado/sindicato/terceiro;
4. limites e cancelamento em arquivo hostil/corrompido;
5. mesmo resultado lógico em Windows e macOS;
6. nenhuma dependência GPL/AGPL transitiva inesperada.

## Evidência de aceite

Em 2026-08-20, a Fase 4 aprovou os seis itens: sete fixtures determinísticas; evidência por página/posição; folha de três páginas sem duplicação; papéis semânticos adversariais; rejeição/limites/cancelamento; matriz de build/test planejada para Windows/macOS. PdfPig 0.1.15 ficou fixado em lockfiles e a auditoria de dependências não introduziu licença GPL/AGPL conhecida.

## Alternativas consideradas

- iText e MuPDF: não adotadas por licenciamento AGPL/comercial incompatível sem aprovação específica.
- PDFium/native wrappers: possível alternativa, com maior complexidade de binários nativos e distribuição.
- OCR como primeira etapa: rejeitado por custo, latência e risco de baixa confiança.

## Referências

- https://github.com/UglyToad/PdfPig
- https://github.com/UglyToad/PdfPig/releases
- https://github.com/UglyToad/PdfPig/blob/master/LICENSE
