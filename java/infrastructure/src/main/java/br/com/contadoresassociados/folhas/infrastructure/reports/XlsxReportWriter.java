package br.com.contadoresassociados.folhas.infrastructure.reports;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchOutcomePresenter;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Planilha com as 5 abas da versão .NET, cores da marca, tabelas filtráveis e colunas técnicas ocultas. */
final class XlsxReportWriter {

    static final int HEADER_ROW = 5;
    static final String BRAND_BLACK = "1C1A17";
    static final String BRAND_GOLD = "E1AA00";
    static final String WARM_WHITE = "FAF8F3";
    static final String BORDER = "DED8CC";
    static final String TEXT = "28251F";
    static final String MUTED = "6F685D";
    static final String GREEN = "E6F4EA";
    static final String GREEN_TEXT = "1E6A36";
    static final String RED = "FCE8E6";
    static final String RED_TEXT = "A7352A";
    static final String YELLOW = "FFF4CE";
    static final String YELLOW_TEXT = "805800";
    static final String BLUE = "E8F0FE";
    static final String BLUE_TEXT = "315D9B";
    static final String GRAY = "F0EEE9";
    static final String GRAY_TEXT = "625D54";

    private final XSSFWorkbook wb = new XSSFWorkbook();
    private final Map<String, XSSFCellStyle> styles = new HashMap<>();

    static void write(Path path, DispatchWorkspace dispatch, DocumentReviewWorkspace review, OffsetDateTime exportedAt,
            String coverage) throws IOException {
        var writer = new XlsxReportWriter();
        try (var wb = writer.wb; OutputStream out = Files.newOutputStream(path)) {
            writer.summary(dispatch, review, exportedAt, coverage);
            writer.table("Itens", "Itens processados",
                    "Uma linha por documento, com a situação da comunicação correspondente quando ela existir.",
                    ReportRows.items(dispatch, review), "tblItens", coverage, BRAND_GOLD,
                    Set.of("Operador", "Estabelecimento", "Evidência disponível", "Nível de atenção", "Serviço de e-mail",
                            "Observação", "Versão do aplicativo", "ID interno do lote", "ID interno do item",
                            "ID interno do grupo", "ID interno do estabelecimento", "Hash abreviado",
                            "Destinatário original", "ID da mensagem no serviço", "ID do rascunho no serviço",
                            "Código técnico"), ReportRows.STATUS);
            writer.table("Erros", "Erros e bloqueios",
                    "Somente situações que exigem correção ou conferência antes de continuar.",
                    ReportRows.errors(dispatch, review), "tblErros", coverage, RED_TEXT,
                    Set.of("Código técnico", "ID interno do lote", "ID interno do item", "ID interno do grupo"),
                    ReportRows.STATUS);
            writer.table("Duplicados", "Documentos repetidos",
                    "Arquivos impedidos de seguir por repetição de conteúdo ou identidade documental.",
                    ReportRows.duplicates(review), "tblDuplicados", coverage, GRAY_TEXT,
                    Set.of("Hash abreviado", "Chave técnica de repetição", "ID interno do documento"), ReportRows.STATUS);
            writer.table("Auditoria", "Histórico de ações",
                    "Registro cronológico das principais ações do operador e do aplicativo.",
                    ReportRows.audit(dispatch, review), "tblAuditoria", coverage, BLUE_TEXT,
                    Set.of("Código técnico", "ID interno do lote", "ID interno do item", "ID interno do documento",
                            "ID interno do grupo", "Correlação técnica", "Valor anterior técnico", "Valor novo técnico"),
                    "Resultado");
            wb.write(out);
        }
    }

    // ------------------------------------------------------------------ estilos

    private XSSFColor color(String hex) {
        return new XSSFColor(new byte[] {(byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16), (byte) Integer.parseInt(hex.substring(4, 6), 16)}, null);
    }

    private XSSFCellStyle style(String key, String fill, String font, boolean bold, double size, HorizontalAlignment h,
            boolean wrap, String dateFormat) {
        return styles.computeIfAbsent(key, k -> {
            var s = wb.createCellStyle();
            if (fill != null) {
                s.setFillForegroundColor(color(fill));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            XSSFFont f = wb.createFont();
            f.setFontName("Aptos");
            f.setFontHeight(size);
            f.setBold(bold);
            f.setColor(color(font));
            s.setFont(f);
            s.setAlignment(h);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            s.setWrapText(wrap);
            s.setBorderBottom(BorderStyle.HAIR);
            s.setBorderTop(BorderStyle.HAIR);
            s.setBorderLeft(BorderStyle.HAIR);
            s.setBorderRight(BorderStyle.HAIR);
            var border = color(BORDER);
            s.setBottomBorderColor(border);
            s.setTopBorderColor(border);
            s.setLeftBorderColor(border);
            s.setRightBorderColor(border);
            if (dateFormat != null) {
                s.setDataFormat(wb.createDataFormat().getFormat(dateFormat));
            }
            return s;
        });
    }

    private void configure(XSSFSheet sheet, String tab, int zoom) {
        sheet.setTabColor(color(tab));
        sheet.setDisplayGridlines(false);
        sheet.setZoom(zoom);
        sheet.getPrintSetup().setLandscape(true);
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setMargin(PageMargin.LEFT, 0.25);
        sheet.setMargin(PageMargin.RIGHT, 0.25);
        sheet.setMargin(PageMargin.TOP, 0.4);
        sheet.setMargin(PageMargin.BOTTOM, 0.4);
    }

    private void merged(XSSFSheet sheet, int r1, int c1, int r2, int c2, Object value, XSSFCellStyle style) {
        for (int r = r1; r <= r2; r++) {
            var row = sheet.getRow(r) == null ? sheet.createRow(r) : sheet.getRow(r);
            for (int c = c1; c <= c2; c++) {
                var cell = row.getCell(c) == null ? row.createCell(c) : row.getCell(c);
                cell.setCellStyle(style);
            }
        }
        var cell = sheet.getRow(r1).getCell(c1);
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof java.time.LocalDateTime t) {
            cell.setCellValue(t);
        } else if (value != null) {
            cell.setCellValue(ReportText.sanitize(value.toString()));
        }
        if (r1 != r2 || c1 != c2) {
            sheet.addMergedRegion(new CellRangeAddress(r1, r2, c1, c2));
        }
    }

    // ------------------------------------------------------------------ resumo

    private void summary(DispatchWorkspace dispatch, DocumentReviewWorkspace review, OffsetDateTime exportedAt,
            String coverage) {
        var sheet = wb.createSheet("Resumo");
        configure(sheet, BRAND_GOLD, 90);
        var title = style("title", BRAND_BLACK, BRAND_GOLD, true, 18, HorizontalAlignment.LEFT, false, null);
        var subtitle = style("subtitle", WARM_WHITE, MUTED, false, 11, HorizontalAlignment.LEFT, true, null);
        var label = style("cardLabel", BRAND_BLACK, BRAND_GOLD, true, 9, HorizontalAlignment.CENTER, true, null);
        var value = style("cardValue", "FFFFFF", TEXT, true, 22, HorizontalAlignment.CENTER, true, null);
        var valueText = style("cardValueText", "FFFFFF", TEXT, true, 11, HorizontalAlignment.CENTER, true, null);
        var valueDate = style("cardValueDate", "FFFFFF", TEXT, true, 11, HorizontalAlignment.CENTER, true,
                "dd/MM/yyyy HH:mm");
        merged(sheet, 0, 0, 1, 7, "Folhas da Michelly — Relatório operacional", title);
        merged(sheet, 2, 0, 2, 7,
                "Visão simples do período selecionado. As abas seguintes permitem filtrar e conferir cada registro.",
                subtitle);
        merged(sheet, 4, 0, 4, 1, "Competência / período", label);
        merged(sheet, 5, 0, 6, 1, coverage, valueText);
        merged(sheet, 4, 2, 4, 3, "Gerado em (UTC)", label);
        merged(sheet, 5, 2, 6, 3, exportedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), valueDate);
        var ps = dispatch.items().stream()
                .map(i -> DispatchOutcomePresenter.present(i, ReportRows.latestAttempt(dispatch, i.id()))).toList();
        var failures = dispatch.items().stream().filter(i -> i.state() == DispatchItemState.FAILED).count();
        var uncertain = dispatch.items().stream().filter(i -> i.state() == DispatchItemState.AMBIGUOUS
                || i.state() == DispatchItemState.SENDING || i.state() == DispatchItemState.DRAFT_CREATING).count();
        card(sheet, 4, 4, "Mensagens", dispatch.items().size(), label, value);
        card(sheet, 4, 6, "Documentos no relatório", ReportRows.inventory(dispatch, review).size(), label, value);
        card(sheet, 8, 0, "Simulações locais", ps.stream().filter(p -> p.simulation() && p.technicalSuccess()).count(),
                label, value);
        card(sheet, 8, 2, "Rascunhos criados", ReportRows.drafts(dispatch), label, value);
        card(sheet, 8, 4, "Aceitas pelo serviço", ps.stream().filter(p -> !p.simulation()
                && p.operationResult().toLowerCase(Locale.ROOT).contains("aceit")).count(), label, value);
        card(sheet, 8, 6, "Falhas ou incertas", failures + uncertain, label, value);
        card(sheet, 12, 0, "Conjuntos para mensagem", ReportRows.groupCount(dispatch, review), label, value);
        card(sheet, 12, 2, "Documentos repetidos", review.documents().stream()
                .filter(d -> d.state() == ReviewDocumentState.DUPLICATE).count(), label, value);
        merged(sheet, 12, 4, 12, 7, "Como interpretar",
                style("infoLabel", BLUE_TEXT, BRAND_GOLD, true, 10, HorizontalAlignment.CENTER, false, null));
        merged(sheet, 13, 4, 14, 7, "O relatório separa simulação, rascunho, aceitação técnica, falha e resultado incerto. "
                + "Aceitação pelo serviço nunca comprova entrega ou leitura.",
                style("infoValue", BLUE, BLUE_TEXT, false, 10, HorizontalAlignment.LEFT, true, null));
        merged(sheet, 16, 0, 16, 7, "Legenda de situações",
                style("section", BRAND_BLACK, BRAND_GOLD, true, 10, HorizontalAlignment.CENTER, false, null));
        legend(sheet, 0, "Sucesso ou conclusão", GREEN, GREEN_TEXT);
        legend(sheet, 2, "Atenção ou resultado incerto", YELLOW, YELLOW_TEXT);
        legend(sheet, 4, "Falha ou bloqueio", RED, RED_TEXT);
        legend(sheet, 6, "Rascunho ou processamento", BLUE, BLUE_TEXT);
        merged(sheet, 20, 0, 21, 7, "Os códigos internos permanecem nas outras abas para suporte e auditoria, mas estão "
                + "recolhidos. Para vê-los, reexiba as colunas ocultas no Excel.",
                style("note", GRAY, GRAY_TEXT, false, 10, HorizontalAlignment.LEFT, true, null));
        var scope = sheet.createRow(23);
        scope.createCell(0).setCellValue("Escopo interno");
        scope.createCell(1).setCellValue(ReportText.sanitize(dispatch.scopeKey()));
        scope.setZeroHeight(true);
        sheet.createFreezePane(0, 3);
        for (int c = 0; c < 8; c++) {
            sheet.setColumnWidth(c, 16 * 256);
        }
        int[][] heights = {{0, 28}, {1, 12}, {2, 26}, {4, 22}, {5, 30}, {6, 30}, {8, 22}, {9, 24}, {10, 24}, {12, 22},
                {13, 26}, {14, 26}, {16, 24}, {17, 22}, {18, 22}, {20, 22}, {21, 22}};
        for (var h : heights) {
            sheet.getRow(h[0]).setHeightInPoints(h[1]);
        }
    }

    private void card(XSSFSheet sheet, int row, int col, String label, long value, XSSFCellStyle ls, XSSFCellStyle vs) {
        merged(sheet, row, col, row, col + 1, label, ls);
        merged(sheet, row + 1, col, row + 2, col + 1, value, vs);
    }

    private void legend(XSSFSheet sheet, int col, String label, String bg, String fg) {
        merged(sheet, 17, col, 18, col + 1, label,
                style("legend" + bg, bg, fg, true, 10, HorizontalAlignment.CENTER, true, null));
    }

    // ------------------------------------------------------------------ tabelas

    private void table(String name, String title, String description, List<String[]> rows, String tableName,
            String coverage, String tab, Set<String> hidden, String statusHeader) {
        var sheet = wb.createSheet(name);
        configure(sheet, tab, 85);
        var headers = rows.getFirst();
        var lastColumn = headers.length - 1;
        var lastVisible = 0;
        for (int i = 0; i < headers.length; i++) {
            if (!hidden.contains(headers[i])) {
                lastVisible = i;
            }
        }
        var records = rows.size() - 1;
        merged(sheet, 0, 0, 0, lastVisible, title,
                style("tTitle", BRAND_BLACK, BRAND_GOLD, true, 18, HorizontalAlignment.CENTER, false, null));
        merged(sheet, 1, 0, 1, lastVisible, description,
                style("tSubtitle", WARM_WHITE, MUTED, false, 11, HorizontalAlignment.CENTER, true, null));
        merged(sheet, 2, 0, 2, lastVisible, records == 0
                ? "Competência / período: " + coverage + " • Nenhum registro encontrado neste recorte."
                : "Competência / período: " + coverage + " • " + records + " " + ReportText.plural(records, "registro",
                        "registros") + " • Use as setas do cabeçalho para filtrar ou ordenar.",
                records == 0 ? style("ctxEmpty", GRAY, GRAY_TEXT, true, 10, HorizontalAlignment.CENTER, true, null)
                        : style("ctx", BLUE, BLUE_TEXT, true, 10, HorizontalAlignment.CENTER, true, null));
        var header = style("header", BRAND_BLACK, "FFFFFF", true, 10, HorizontalAlignment.CENTER, true, null);
        var headerRow = sheet.createRow(HEADER_ROW - 1);
        headerRow.setHeightInPoints(36);
        for (int c = 0; c < headers.length; c++) {
            var cell = headerRow.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(header);
        }
        var timestampColumn = indexOf(headers, ReportRows.TIMESTAMP);
        var statusColumn = indexOf(headers, statusHeader);
        for (int r = 1; r < rows.size(); r++) {
            var row = sheet.createRow(HEADER_ROW - 1 + r);
            var zebra = r % 2 == 0;
            var lines = 1;
            for (int c = 0; c < headers.length; c++) {
                var text = ReportText.sanitize(rows.get(r)[c]);
                var cell = row.createCell(c);
                var wrap = wraps(headers[c]);
                var align = centered(headers[c]) ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT;
                if (c == timestampColumn && !text.isEmpty()) {
                    try {
                        cell.setCellValue(OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
                        cell.setCellStyle(style("date" + zebra, zebra ? WARM_WHITE : null, TEXT, false, 10,
                                HorizontalAlignment.CENTER, false, "dd/MM/yyyy HH:mm"));
                        continue;
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // mantém como texto
                    }
                }
                if (!text.isEmpty()) {
                    cell.setCellValue(text);
                }
                if (c == statusColumn) {
                    var colors = statusColors(text);
                    cell.setCellStyle(style("status" + colors[0], colors[0], colors[1], true, 10, align, true, null));
                } else {
                    cell.setCellStyle(style("body" + zebra + wrap + align, zebra ? WARM_WHITE : null, TEXT, false, 10,
                            align, wrap, null));
                }
                if (wrap && !hidden.contains(headers[c])) {
                    var usable = Math.max(10, (int) Math.floor(width(headers[c]) - 2));
                    var needed = 0;
                    for (var part : text.split("\n", -1)) {
                        needed += Math.max(1, (int) Math.ceil(part.length() / (double) usable));
                    }
                    lines = Math.max(lines, Math.min(needed, 4));
                }
            }
            row.setHeightInPoints(21 + (lines - 1) * 12);
        }
        for (int c = 0; c < headers.length; c++) {
            sheet.setColumnWidth(c, (int) (width(headers[c]) * 256));
            sheet.setColumnHidden(c, hidden.contains(headers[c]));
        }
        var lastRow = Math.max(HEADER_ROW, HEADER_ROW - 1 + rows.size() - 1);
        if (rows.size() == 1) {
            sheet.createRow(HEADER_ROW);
        }
        var area = new AreaReference(new CellReference(HEADER_ROW - 1, 0), new CellReference(lastRow, lastColumn),
                SpreadsheetVersion.EXCEL2007);
        var t = sheet.createTable(area);
        t.setName(tableName);
        t.setDisplayName(tableName);
        t.getCTTable().addNewAutoFilter().setRef(area.formatAsString());
        for (int c = 0; c < headers.length; c++) {
            t.getCTTable().getTableColumns().getTableColumnArray(c).setName(headers[c]);
        }
        sheet.createFreezePane(3, HEADER_ROW);
        sheet.getRow(0).setHeightInPoints(32);
        sheet.getRow(1).setHeightInPoints(25);
        sheet.getRow(2).setHeightInPoints(25);
        sheet.createRow(3).setHeightInPoints(8);
    }

    private static int indexOf(String[] headers, String header) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equals(header)) {
                return i;
            }
        }
        return -1;
    }

    static double width(String header) {
        return switch (header) {
            case ReportRows.TIMESTAMP, "Operador" -> 20;
            case "Cliente" -> 30;
            case "Estabelecimento" -> 22;
            case "Documento", "Arquivo" -> 36;
            case "Tipo de documento", "Operação", "Área" -> header.equals("Área") ? 23 : 24;
            case "Competência / período" -> 21;
            case "Destinatário", "Destinatário original" -> 34;
            case ReportRows.STATUS, "Resultado", "Nível de atenção" -> 27;
            case "Entrega ao destinatário", "Evidência disponível" -> 38;
            case "Próxima ação" -> 42;
            case "Serviço de e-mail" -> 30;
            case "Observação", "Descrição", "Detalhes" -> 44;
            case "Ação" -> 32;
            case "Versão do aplicativo" -> 18;
            default -> 24;
        };
    }

    static boolean wraps(String h) {
        return switch (h) {
            case "Cliente", "Documento", "Arquivo", "Tipo de documento", "Destinatário", "Operação", "Observação",
                    "Descrição", "Detalhes", "Ação", "Entrega ao destinatário", "Evidência disponível", "Próxima ação",
                    "Serviço de e-mail", ReportRows.STATUS, "Resultado" -> true;
            default -> false;
        };
    }

    static boolean centered(String h) {
        return switch (h) {
            case ReportRows.TIMESTAMP, "Competência / período", "Operação", ReportRows.STATUS, "Nível de atenção",
                    "Serviço de e-mail", "Versão do aplicativo", "Resultado" -> true;
            default -> false;
        };
    }

    static String[] statusColors(String value) {
        var v = value.toLowerCase(Locale.ROOT);
        if (v.contains("aceito") || v.contains("aprovado") || v.contains("concluído") || v.contains("conferida")) {
            return new String[] {GREEN, GREEN_TEXT};
        }
        if (v.contains("falha") || v.contains("bloque") || v.contains("não concluído") || v.contains("correção")
                || v.contains("ação obrigatória")) {
            return new String[] {RED, RED_TEXT};
        }
        if (v.contains("atenção") || v.contains("incerto") || v.contains("repetido")) {
            return new String[] {YELLOW, YELLOW_TEXT};
        }
        if (v.contains("rascunho") || v.contains("process") || v.contains("enviando") || v.contains("pronto")) {
            return new String[] {BLUE, BLUE_TEXT};
        }
        return new String[] {GRAY, GRAY_TEXT};
    }
}
