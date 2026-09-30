package br.com.contadoresassociados.folhas.infrastructure.reports;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchOutcomePresentation;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchOutcomePresenter;
import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * PDF operacional com a identidade da versão .NET (cabeçalho preto/dourado, cartões de métricas,
 * resumo por cliente, tabela de comunicações, anexos, inventário e rodapé). Mesmas coordenadas
 * (A4, margem 38, origem no topo), agora com medição real de texto em vez de estimativa.
 */
final class BrandedPdfReportWriter {

    static final float W = PDRectangle.A4.getWidth();
    static final float H = PDRectangle.A4.getHeight();
    static final float M = 38;
    static final float CW = W - M * 2;

    static final Color BLACK = hex("1C1A17");
    static final Color GOLD = hex("E1AA00");
    static final Color GOLD_TEXT = hex("8C691B");
    static final Color GOLD_BORDER = hex("D8B85A");
    static final Color WHITE = Color.WHITE;
    static final Color MUTED = hex("6F685D");
    static final Color BORDER = hex("DED8CC");
    static final Color SOFT_GRAY = hex("F4F1EB");
    static final Color PALE_GOLD = hex("FFF6DE");
    static final Color PALE_RED = hex("FCE8E6");
    static final Color GREEN = hex("5B8A58");
    static final Color GREEN_TEXT = hex("2E6A37");
    static final Color RED = hex("B94A3B");
    static final Color RED_TEXT = hex("9A352A");
    static final Color BLUE = hex("4F74A8");

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final PDDocument doc = new PDDocument();
    private final List<Canvas> pages = new ArrayList<>();

    private record Row(DispatchItem item, DispatchOutcomePresentation p) {
    }

    static void write(Path path, DispatchWorkspace dispatch, DocumentReviewWorkspace review, OffsetDateTime exportedAt,
            String coverage, ZoneId zone) throws IOException {
        var w = new BrandedPdfReportWriter();
        try (var doc = w.doc) {
            w.render(dispatch, review, exportedAt, coverage, zone);
            var info = new PDDocumentInformation();
            info.setTitle("Folhas da Michelly — Relatório operacional");
            info.setCreator("Folhas da Michelly");
            doc.setDocumentInformation(info);
            doc.save(path.toFile());
        }
    }

    private void render(DispatchWorkspace dispatch, DocumentReviewWorkspace review, OffsetDateTime exportedAt,
            String coverage, ZoneId zone) throws IOException {
        var rows = dispatch.items().stream()
                .sorted(Comparator.comparing(DispatchItem::clientDisplayName, ReportRows.PT)
                        .thenComparing(i -> i.periodLabel() == null ? "" : i.periodLabel()))
                .map(i -> new Row(i, DispatchOutcomePresenter.present(i, ReportRows.latestAttempt(dispatch, i.id()))))
                .toList();
        var inventory = ReportRows.inventory(dispatch, review);
        var page = addPage("RELATÓRIO OPERACIONAL");
        float y = 102;
        page.text("Folhas da Michelly", M, y, 22, true, BLACK);
        y += 30;
        y = page.wrapped(coverage, M, y, CW, 13, 17, true, GOLD_TEXT);
        y += 4;
        page.text("Gerado em " + exportedAt.atZoneSameInstant(zone)
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR"))), M, y, 9,
                false, MUTED);
        y += 25;

        record ClientRow(String name, int communications, int documents, int attention) {
        }
        var clientMap = new LinkedHashMap<String, int[]>();
        var clientNames = new LinkedHashMap<String, String>();
        for (var r : rows) {
            var key = clientKey(r.item().clientId(), r.item().clientDisplayName());
            clientNames.putIfAbsent(key, r.item().clientDisplayName());
            var c = clientMap.computeIfAbsent(key, k -> new int[3]);
            c[0]++;
            if (r.p().needsAttention()) {
                c[2]++;
            }
        }
        for (var d : inventory) {
            var key = clientKey(d.clientId(), d.clientDisplayName());
            clientNames.putIfAbsent(key, d.clientDisplayName());
            clientMap.computeIfAbsent(key, k -> new int[3])[1]++;
        }
        var clients = clientMap.entrySet().stream().map(e -> new ClientRow(clientNames.get(e.getKey()), e.getValue()[0],
                e.getValue()[1], e.getValue()[2])).sorted(Comparator.comparing(ClientRow::name, ReportRows.PT)).toList();

        var ps = rows.stream().map(Row::p).toList();
        record Metric(String label, long value, Color accent) {
        }
        var metrics = List.of(new Metric("DOCUMENTOS", inventory.size(), BLUE), new Metric("CLIENTES", clients.size(), BLUE),
                new Metric("SIMULAÇÕES", ps.stream().filter(p -> p.simulation() && p.technicalSuccess()).count(), GREEN),
                new Metric("RASCUNHOS", rows.stream().filter(r -> !r.p().simulation()
                        && r.item().mode() == DispatchOperationMode.DRAFT
                        && r.item().state() == DispatchItemState.DRAFT_CREATED).count(), BLUE),
                new Metric("ACEITAS PELO SERVIÇO", ps.stream().filter(p -> !p.simulation()
                        && p.operationResult().toLowerCase(Locale.ROOT).contains("aceit")).count(), GREEN),
                new Metric("FALHAS / INCERTAS", ps.stream().filter(DispatchOutcomePresentation::needsAttention).count(), RED));
        float gap = 9;
        float mw = (CW - gap * 2) / 3;
        for (int i = 0; i < metrics.size(); i++) {
            var x = M + (i % 3) * (mw + gap);
            var top = y + (i / 3) * (62 + gap);
            page.panel(x, top, mw, 62, WHITE, BORDER);
            page.rect(x, top, 5, 62, metrics.get(i).accent(), null, 0);
            page.text(metrics.get(i).label(), x + 15, top + 16, 7.5f, true, MUTED);
            page.text(Long.toString(metrics.get(i).value()), x + 15, top + 34, 18, true, BLACK);
        }
        y += (62 + gap) * 2 + 18;

        page.panel(M, y, CW, 84, PALE_GOLD, GOLD_BORDER);
        page.text("COMO INTERPRETAR", M + 14, y + 15, 9, true, GOLD_TEXT);
        page.wrapped("Simulação local não envia e-mail. Rascunho também não envia. Quando o serviço aceita uma "
                + "solicitação, isso é apenas um protocolo técnico: a entrega e a leitura pelo destinatário não estão "
                + "confirmadas.", M + 14, y + 31, CW - 28, 9.5f, 13, false, BLACK);
        y += 102;

        if (y + 90 > 775) {
            page = addPage("RELATÓRIO OPERACIONAL");
            y = 105;
        }
        page.text("Resumo por cliente", M, y, 15, true, BLACK);
        y += 24;
        if (clients.isEmpty()) {
            page.panel(M, y, CW, 46, SOFT_GRAY, BORDER);
            page.text("Nenhuma comunicação preparada neste recorte.", M + 14, y + 22, 9.5f, false, MUTED);
            y += 54;
        }
        for (var c : clients) {
            var name = Canvas.wrap(c.name(), 282, 9.5f, true);
            var h = Math.max(44, 18 + name.size() * 11);
            if (y + h > 775) {
                page = addPage("RELATÓRIO OPERACIONAL");
                y = 105;
            }
            page.line(M, y + h - 1, M + CW, y + h - 1, BORDER, 0.5f);
            page.lines(name, M + 8, y + 14, 9.5f, 11, true, BLACK);
            page.text(c.documents() + " documento(s)  •  " + c.communications() + " mensagem(ns)", M + 315, y + 15, 8.5f,
                    false, MUTED);
            page.text(c.attention() == 0 ? "Sem pendência crítica" : c.attention() + " para conferir", M + 315, y + 31,
                    8.5f, true, c.attention() == 0 ? GREEN_TEXT : RED_TEXT);
            y += h;
        }

        y += 18;
        if (y + 110 > 775) {
            page = addPage("RELATÓRIO OPERACIONAL");
            y = 105;
        }
        page.text("Situação das comunicações", M, y, 15, true, BLACK);
        page.wrapped("Cada linha corresponde a uma mensagem preparada. A coluna de entrega é a referência correta para "
                + "saber o que foi ou não comprovado.", M, y + 23, CW, 9, 12, false, MUTED);
        y += 50;
        if (rows.isEmpty()) {
            page.panel(M, y, CW, 58, SOFT_GRAY, BORDER);
            page.text("Nenhuma mensagem preparada para este recorte.", M + 14, y + 25, 10, false, MUTED);
            y += 66;
        } else {
            y = tableHeader(page, y);
            for (var r : rows) {
                var cl = Canvas.wrap(r.item().clientDisplayName(), 116, 8.5f, true);
                var rl = Canvas.wrap(r.p().operationResult(), 142, 8.2f, true);
                var dl = Canvas.wrap(r.p().deliveryStatus(), 176, 8.2f, false);
                var h = Math.max(45, 16 + Math.max(cl.size(), Math.max(rl.size(), dl.size())) * 11);
                if (y + h > 770) {
                    page = addPage("SITUAÇÃO DAS COMUNICAÇÕES");
                    y = tableHeader(page, 102);
                }
                var bg = r.p().needsAttention() ? PALE_RED : r.p().simulation() ? PALE_GOLD : WHITE;
                page.rect(M, y, CW, h, bg, BORDER, 0.45f);
                page.lines(cl, M + 7, y + 13, 8.5f, 11, true, BLACK);
                page.text(r.item().periodLabel(), M + 132, y + 14, 8, false, MUTED);
                page.lines(rl, M + 193, y + 13, 8.2f, 11, true, BLACK);
                page.lines(dl, M + 344, y + 13, 8.2f, 11, false, r.p().needsAttention() ? RED_TEXT : BLACK);
                y += h;
            }
        }

        y += 18;
        if (y + 130 > 775) {
            page = addPage("RELATÓRIO OPERACIONAL");
            y = 105;
        }
        page.text("Arquivos de cada comunicação", M, y, 15, true, BLACK);
        page.wrapped("Confira abaixo quais documentos compõem cada mensagem. Os nomes apresentados são os arquivos "
                + "anexados, sem códigos internos.", M, y + 23, CW, 9, 12, false, MUTED);
        y += 50;
        if (rows.isEmpty()) {
            page.panel(M, y, CW, 46, SOFT_GRAY, BORDER);
            page.text("Nenhuma comunicação preparada neste recorte.", M + 14, y + 22, 9.5f, false, MUTED);
            y += 54;
        }
        for (var r : rows) {
            var atts = r.item().message() == null ? List.<br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAttachmentSnapshot>of()
                    : r.item().message().attachments();
            var lines = new ArrayList<String>();
            atts.forEach(a -> lines.addAll(Canvas.wrap("- " + DocumentPresentation.label(a.documentType()) + " — "
                    + a.fileName(), CW - 32, 8.8f, false)));
            if (lines.isEmpty()) {
                lines.add("Nenhum documento anexado a esta mensagem.");
            }
            var name = Canvas.wrap(r.item().clientDisplayName(), CW - 145, 9.5f, true);
            var metaTop = 18 + name.size() * 11;
            var filesTop = metaTop + 17;
            var h = filesTop + lines.size() * 12 + 8;
            if (y + h > 770) {
                page = addPage("ARQUIVOS DAS COMUNICAÇÕES");
                y = 102;
            }
            page.panel(M, y, CW, h, SOFT_GRAY, BORDER);
            page.lines(name, M + 14, y + 14, 9.5f, 11, true, BLACK);
            page.text(r.item().periodLabel(), M + CW - 74, y + 17, 8.5f, true, GOLD_TEXT);
            page.text("Conjunto " + shortRef(r.item().groupId()) + "  •  " + (atts.size() == 1
                    ? "1 documento na mensagem" : atts.size() + " documentos na mensagem"), M + 14, y + metaTop, 8,
                    false, MUTED);
            page.lines(lines, M + 14, y + filesTop, 8.8f, 12, false, atts.isEmpty() ? MUTED : BLACK);
            y += h + 8;
        }

        y += 18;
        if (y + 130 > 775) {
            page = addPage("RELATÓRIO OPERACIONAL");
            y = 105;
        }
        page.text("Todos os documentos do recorte", M, y, 15, true, BLACK);
        page.wrapped("Esta relação inclui também documentos que ainda não viraram mensagem, organizados por conjunto, "
                + "cliente e período.", M, y + 23, CW, 9, 12, false, MUTED);
        y += 50;
        if (inventory.isEmpty()) {
            page.panel(M, y, CW, 46, SOFT_GRAY, BORDER);
            page.text("Nenhum documento encontrado neste recorte.", M + 14, y + 22, 9.5f, false, MUTED);
            y += 54;
        }
        var groups = new LinkedHashMap<UUID, List<ReportRows.DocumentEntry>>();
        inventory.forEach(d -> groups.computeIfAbsent(d.groupId() != null ? d.groupId() : d.documentId(),
                k -> new ArrayList<>()).add(d));
        var ordered = groups.values().stream().sorted(Comparator.comparing(
                (List<ReportRows.DocumentEntry> g) -> g.getFirst().clientDisplayName(), ReportRows.PT)
                .thenComparing(g -> g.getFirst().periodLabel())).toList();
        for (var g : ordered) {
            var first = g.getFirst();
            var lines = new ArrayList<String>();
            g.stream().sorted(Comparator.comparing(ReportRows.DocumentEntry::fileName, ReportRows.PT))
                    .forEach(d -> lines.addAll(Canvas.wrap("- " + DocumentPresentation.label(d.documentType()) + " — "
                            + d.fileName() + " • " + (d.reviewState() == null ? "Registrado na mensagem atual"
                                    : inventoryState(d.reviewState())), CW - 32, 8.8f, false)));
            var name = Canvas.wrap(first.clientDisplayName(), CW - 145, 9.5f, true);
            var metaTop = 18 + name.size() * 11;
            var filesTop = metaTop + 17;
            var h = filesTop + lines.size() * 12 + 8;
            if (y + h > 770) {
                page = addPage("DOCUMENTOS DO RECORTE");
                y = 102;
            }
            page.panel(M, y, CW, h, WHITE, BORDER);
            page.lines(name, M + 14, y + 14, 9.5f, 11, true, BLACK);
            page.text(first.periodLabel(), M + CW - 74, y + 17, 8.5f, true, GOLD_TEXT);
            page.text(first.groupId() != null ? "Conjunto " + shortRef(first.groupId()) + "  •  " + g.size()
                    + " documento(s)" : "Ainda sem conjunto", M + 14, y + metaTop, 8, false, MUTED);
            page.lines(lines, M + 14, y + filesTop, 8.8f, 12, false, BLACK);
            y += h + 8;
        }

        y += 18;
        if (y + 115 > 775) {
            page = addPage("RELATÓRIO OPERACIONAL");
            y = 105;
        }
        page.text("Documentos e pendências", M, y, 15, true, BLACK);
        y += 26;
        page.panel(M, y, CW, 74, SOFT_GRAY, BORDER);
        page.text("Documentos no recorte: " + inventory.size(), M + 14, y + 18, 10, true, BLACK);
        page.text("Prontos/aprovados: " + inventory.stream().filter(d -> d.messageSnapshotOnly()
                || d.reviewState() == ReviewDocumentState.READY || d.reviewState() == ReviewDocumentState.GROUPED
                || d.reviewState() == ReviewDocumentState.APPROVED).count(), M + 14, y + 37, 9, false, BLACK);
        page.text("Precisam de correção: " + review.documents().stream()
                .filter(d -> d.state() == ReviewDocumentState.BLOCKED).count() + "   •   Repetidos: "
                + review.documents().stream().filter(d -> d.state() == ReviewDocumentState.DUPLICATE).count(),
                M + 250, y + 37, 9, false, BLACK);

        for (int i = 0; i < pages.size(); i++) {
            var p = pages.get(i);
            p.line(M, 802, W - M, 802, BORDER, 0.5f);
            p.text("Aceitação pelo serviço de e-mail não comprova entrega nem leitura pelo destinatário.", M, 817, 6.8f,
                    false, MUTED);
            p.text((i + 1) + "/" + pages.size(), W - M - 20, 817, 6.8f, true, MUTED);
            p.close();
        }
    }

    private float tableHeader(Canvas page, float y) throws IOException {
        page.rect(M, y, CW, 27, BLACK, null, 0);
        page.text("CLIENTE", M + 7, y + 17, 7.5f, true, WHITE);
        page.text("PERÍODO", M + 132, y + 17, 7.5f, true, WHITE);
        page.text("RESULTADO", M + 193, y + 17, 7.5f, true, WHITE);
        page.text("ENTREGA AO DESTINATÁRIO", M + 344, y + 17, 7.5f, true, WHITE);
        return y + 27;
    }

    private Canvas addPage(String label) throws IOException {
        var page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        var canvas = new Canvas(new PDPageContentStream(doc, page));
        pages.add(canvas);
        canvas.rect(0, 0, W, 72, BLACK, null, 0);
        canvas.rect(0, 68, W, 4, GOLD, null, 0);
        canvas.text("FOLHAS DA MICHELLY", M, 30, 13, true, GOLD);
        canvas.text(label, M, 51, 7.5f, false, WHITE);
        canvas.text("PÁGINA " + pages.size(), W - M - 54, 43, 7, true, WHITE);
        return canvas;
    }

    private static String inventoryState(ReviewDocumentState s) {
        return switch (s) {
            case BLOCKED -> "Precisa de correção";
            case READY -> "Pronto para organizar";
            case GROUPED -> "Pronto para revisar";
            case APPROVED -> "Liberado para mensagem";
            case DUPLICATE -> "Documento repetido";
        };
    }

    private static String clientKey(UUID id, String name) {
        return id != null ? id.toString() : "name:" + (name == null ? "" : name.strip().toUpperCase(Locale.ROOT));
    }

    private static String shortRef(UUID id) {
        return ("#" + id.toString().replace("-", "")).substring(0, 7).toUpperCase(Locale.ROOT);
    }

    private static Color hex(String h) {
        return new Color(Integer.parseInt(h, 16));
    }

    /** Página com coordenadas "a partir do topo", como no .NET. */
    static final class Canvas {
        private final PDPageContentStream cs;

        Canvas(PDPageContentStream cs) {
            this.cs = cs;
        }

        void text(String text, float x, float top, float size, boolean bold, Color color) throws IOException {
            cs.beginText();
            cs.setFont(bold ? BOLD : REGULAR, size);
            cs.setNonStrokingColor(color);
            cs.newLineAtOffset(x, H - top - size);
            cs.showText(winAnsi(text));
            cs.endText();
        }

        float wrapped(String text, float x, float top, float width, float size, float lh, boolean bold, Color color)
                throws IOException {
            var lines = wrap(text, width, size, bold);
            lines(lines, x, top, size, lh, bold, color);
            return top + lines.size() * lh;
        }

        void lines(List<String> lines, float x, float top, float size, float lh, boolean bold, Color color)
                throws IOException {
            for (int i = 0; i < lines.size(); i++) {
                text(lines.get(i), x, top + i * lh, size, bold, color);
            }
        }

        void rect(float x, float top, float w, float h, Color fill, Color stroke, float strokeWidth) throws IOException {
            var y = H - top - h;
            if (fill != null) {
                cs.setNonStrokingColor(fill);
                cs.addRect(x, y, w, h);
                cs.fill();
            }
            if (stroke != null) {
                cs.setStrokingColor(stroke);
                cs.setLineWidth(strokeWidth);
                cs.addRect(x, y, w, h);
                cs.stroke();
            }
        }

        void panel(float x, float top, float w, float h, Color fill, Color stroke) throws IOException {
            rect(x, top, w, h, fill, stroke, 0.7f);
        }

        void line(float x1, float t1, float x2, float t2, Color color, float width) throws IOException {
            cs.setStrokingColor(color);
            cs.setLineWidth(width);
            cs.moveTo(x1, H - t1);
            cs.lineTo(x2, H - t2);
            cs.stroke();
        }

        void close() throws IOException {
            cs.close();
        }

        static List<String> wrap(String value, float width, float size, boolean bold) {
            var text = value == null || value.isBlank() ? "—" : winAnsi(value);
            var font = bold ? BOLD : REGULAR;
            var lines = new ArrayList<String>();
            for (var paragraph : text.split("\n")) {
                var current = new StringBuilder();
                for (var word : paragraph.split(" ")) {
                    if (word.isEmpty()) {
                        continue;
                    }
                    var candidate = current.isEmpty() ? word : current + " " + word;
                    if (measure(font, candidate, size) <= width) {
                        current.setLength(0);
                        current.append(candidate);
                        continue;
                    }
                    if (!current.isEmpty()) {
                        lines.add(current.toString());
                        current.setLength(0);
                    }
                    if (measure(font, word, size) <= width) {
                        current.append(word);
                    } else {
                        var piece = new StringBuilder();
                        for (var ch : word.toCharArray()) {
                            if (measure(font, piece.toString() + ch, size) > width && !piece.isEmpty()) {
                                lines.add(piece.toString());
                                piece.setLength(0);
                            }
                            piece.append(ch);
                        }
                        current.append(piece);
                    }
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                }
            }
            return lines.isEmpty() ? List.of("—") : lines;
        }

        static float measure(PDType1Font font, String text, float size) {
            try {
                return font.getStringWidth(text) / 1000f * size;
            } catch (IOException | IllegalArgumentException e) {
                return text.length() * size * 0.52f;
            }
        }

        /** Mesmas trocas tipográficas da versão .NET; o resto fora de WinAnsi vira '?'. */
        static String winAnsi(String value) {
            var t = (value == null ? "" : value).replace('\u2013', '-').replace('\u2014', '-').replace('\u2018', '\'')
                    .replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"').replace("…", "...")
                    .replace('\u00A0', ' ').replace('•', '-').replace('\t', ' ').replace('\r', ' ');
            var sb = new StringBuilder(t.length());
            t.chars().forEach(c -> sb.append(c <= 0xFF && (c >= 0x20 || c == '\n') ? (char) c : '?'));
            return sb.toString();
        }
    }
}
