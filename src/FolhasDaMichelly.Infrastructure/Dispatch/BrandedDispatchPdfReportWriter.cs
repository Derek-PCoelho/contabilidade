using System.Globalization;
using System.Text;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

internal static class BrandedDispatchPdfReportWriter
{
    private const float PageWidth = 595;
    private const float PageHeight = 842;
    private const float Margin = 38;
    private const float ContentWidth = PageWidth - (Margin * 2);

    public static void Write(
        string path,
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review,
        DispatchReportFilter filter,
        DateTimeOffset exportedAtUtc,
        string coverage)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var rows = dispatch.Items
            .OrderBy(item => item.ClientDisplayName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(item => item.PeriodLabel, StringComparer.Ordinal)
            .Select(item =>
            {
                var attempt = dispatch.Attempts
                    .Where(current => current.DispatchItemId == item.Id)
                    .OrderByDescending(current => current.AttemptNumber)
                    .FirstOrDefault();
                return new CommunicationRow(item, DispatchOutcomePresenter.Present(item, attempt));
            })
            .ToArray();
        var inventory = DispatchReportDocumentInventory.Build(dispatch, review);

        var report = new ReportDocument();
        var page = report.AddPage();
        DrawHeader(page, "RELATÓRIO OPERACIONAL", 1);
        var y = 102f;
        page.Text("Folhas da Michelly", Margin, y, 22, bold: true, Color.Black);
        y += 30;
        y = page.WrappedText(
            coverage,
            Margin,
            y,
            ContentWidth,
            13,
            17,
            bold: true,
            Color.GoldText);
        y += 4;
        page.Text(
            $"Gerado em {exportedAtUtc.ToLocalTime():dd/MM/yyyy 'às' HH:mm}",
            Margin,
            y,
            9,
            false,
            Color.Muted);
        y += 25;

        var presentations = rows.Select(row => row.Presentation).ToArray();
        var clientRows = rows
            .Select(row => new ClientSummarySource(
                ClientKey(row.Item.ClientId, row.Item.ClientDisplayName),
                row.Item.ClientDisplayName,
                row.Item.Id,
                null,
                row.Presentation.NeedsAttention))
            .Concat(inventory.Select(document => new ClientSummarySource(
                ClientKey(document.ClientId, document.ClientDisplayName),
                document.ClientDisplayName,
                null,
                document.DocumentId,
                NeedsAttention: false)))
            .GroupBy(source => source.Key, StringComparer.Ordinal)
            .Select(group => new ClientSummaryRow(
                group.First().Name,
                group.Where(source => source.CommunicationId.HasValue)
                    .Select(source => source.CommunicationId!.Value)
                    .Distinct()
                    .Count(),
                group.Where(source => source.DocumentId.HasValue)
                    .Select(source => source.DocumentId!.Value)
                    .Distinct()
                    .Count(),
                group.Count(source => source.CommunicationId.HasValue && source.NeedsAttention)))
            .OrderBy(item => item.Name, StringComparer.CurrentCultureIgnoreCase)
            .ToArray();
        var metrics = new[]
        {
            new Metric("DOCUMENTOS", inventory.Count, Color.Blue),
            new Metric("CLIENTES", clientRows.Length, Color.Blue),
            new Metric("SIMULAÇÕES", presentations.Count(item => item.IsSimulation && item.IsTechnicalSuccess), Color.Green),
            new Metric("RASCUNHOS", rows.Count(row => !row.Presentation.IsSimulation && row.Item.Mode == DispatchOperationMode.Draft && row.Item.State == DispatchItemState.DraftCreated), Color.Blue),
            new Metric("ACEITAS PELO SERVIÇO", presentations.Count(item => !item.IsSimulation && item.OperationResult.Contains("aceit", StringComparison.OrdinalIgnoreCase)), Color.Green),
            new Metric("FALHAS / INCERTAS", presentations.Count(item => item.NeedsAttention), Color.Red),
        };
        y = DrawMetrics(page, metrics, y);
        y += 18;

        page.RoundedPanel(Margin, y, ContentWidth, 84, Color.PaleGold, Color.GoldBorder);
        page.Text("COMO INTERPRETAR", Margin + 14, y + 15, 9, true, Color.GoldText);
        page.WrappedText(
            "Simulação local não envia e-mail. Rascunho também não envia. Quando o serviço aceita uma solicitação, isso é apenas um protocolo técnico: a entrega e a leitura pelo destinatário não estão confirmadas.",
            Margin + 14,
            y + 31,
            ContentWidth - 28,
            9.5f,
            13,
            false,
            Color.Black);
        y += 102;

        y = EnsureSpace(report, page, y, 90, out page);
        page.Text("Resumo por cliente", Margin, y, 15, true, Color.Black);
        y += 24;
        y = DrawClientSummary(report, ref page, clientRows, y);

        y = EnsureSpace(report, page, y + 18, 110, out page);
        page.Text("Situação das comunicações", Margin, y, 15, true, Color.Black);
        y += 8;
        page.WrappedText(
            "Cada linha corresponde a uma mensagem preparada. A coluna de entrega é a referência correta para saber o que foi ou não comprovado.",
            Margin,
            y + 15,
            ContentWidth,
            9,
            12,
            false,
            Color.Muted);
        y += 50;
        y = DrawCommunicationTable(report, ref page, rows, y);

        // Keep the section heading, its explanation and the first attachment card
        // on the same page so the reader never sees an orphaned heading.
        y = EnsureSpace(report, page, y + 18, 130, out page);
        page.Text("Arquivos de cada comunicação", Margin, y, 15, true, Color.Black);
        y += 8;
        page.WrappedText(
            "Confira abaixo quais documentos compõem cada mensagem. Os nomes apresentados são os arquivos anexados, sem códigos internos.",
            Margin,
            y + 15,
            ContentWidth,
            9,
            12,
            false,
            Color.Muted);
        y += 50;
        y = DrawCommunicationAttachments(report, ref page, rows, y);

        y = EnsureSpace(report, page, y + 18, 130, out page);
        page.Text("Todos os documentos do recorte", Margin, y, 15, true, Color.Black);
        y += 8;
        page.WrappedText(
            "Esta relação inclui também documentos que ainda não viraram mensagem, organizados por conjunto, cliente e período.",
            Margin,
            y + 15,
            ContentWidth,
            9,
            12,
            false,
            Color.Muted);
        y += 50;
        y = DrawDocumentInventory(report, ref page, inventory, y);

        y = EnsureSpace(report, page, y + 18, 115, out page);
        page.Text("Documentos e pendências", Margin, y, 15, true, Color.Black);
        y += 26;
        page.RoundedPanel(Margin, y, ContentWidth, 74, Color.SoftGray, Color.Border);
        page.Text($"Documentos no recorte: {inventory.Count}", Margin + 14, y + 18, 10, true, Color.Black);
        page.Text(
            $"Prontos/aprovados: {inventory.Count(item => item.IsMessageSnapshotOnly || item.ReviewState is ReviewDocumentState.Ready or ReviewDocumentState.Grouped or ReviewDocumentState.Approved)}",
            Margin + 14,
            y + 37,
            9,
            false,
            Color.Black);
        page.Text(
            $"Precisam de correção: {review.Documents.Count(item => item.State == ReviewDocumentState.Blocked)}   •   Repetidos: {review.Documents.Count(item => item.State == ReviewDocumentState.Duplicate)}",
            Margin + 250,
            y + 37,
            9,
            false,
            Color.Black);

        report.AddFooters(
            "Aceitação pelo serviço de e-mail não comprova entrega nem leitura pelo destinatário.");
        report.Save(path);
    }

    private static float DrawMetrics(PageCanvas page, IReadOnlyList<Metric> metrics, float y)
    {
        const float gap = 9;
        var width = (ContentWidth - (gap * 2)) / 3;
        const float height = 62;
        for (var index = 0; index < metrics.Count; index++)
        {
            var row = index / 3;
            var column = index % 3;
            var x = Margin + (column * (width + gap));
            var top = y + (row * (height + gap));
            var metric = metrics[index];
            page.RoundedPanel(x, top, width, height, Color.White, Color.Border);
            page.Rectangle(x, top, 5, height, metric.Accent);
            page.Text(metric.Label, x + 15, top + 16, 7.5f, true, Color.Muted);
            page.Text(metric.Value.ToString(CultureInfo.InvariantCulture), x + 15, top + 34, 18, true, Color.Black);
        }

        return y + ((height + gap) * 2);
    }

    private static float DrawClientSummary(
        ReportDocument report,
        ref PageCanvas page,
        IEnumerable<ClientSummaryRow> clientRows,
        float y)
    {
        var rows = clientRows.ToArray();
        if (rows.Length == 0)
        {
            page.RoundedPanel(Margin, y, ContentWidth, 46, Color.SoftGray, Color.Border);
            page.Text("Nenhuma comunicação preparada neste recorte.", Margin + 14, y + 22, 9.5f, false, Color.Muted);
            return y + 54;
        }

        foreach (var row in rows)
        {
            y = EnsureSpace(report, page, y, 32, out page);
            var nameLines = PageCanvas.Wrap(row.Name, 282, 9.5f);
            var height = Math.Max(44, 18 + (nameLines.Count * 11));
            y = EnsureSpace(report, page, y, height, out page);
            page.Line(Margin, y + height - 1, Margin + ContentWidth, y + height - 1, Color.Border, 0.5f);
            page.TextLines(nameLines, Margin + 8, y + 14, 9.5f, 11, true, Color.Black);
            page.Text(
                $"{row.Documents} documento(s)  •  {row.Communications} mensagem(ns)",
                Margin + 315,
                y + 15,
                8.5f,
                false,
                Color.Muted);
            page.Text(
                row.Attention == 0 ? "Sem pendência crítica" : $"{row.Attention} para conferir",
                Margin + 315,
                y + 31,
                8.5f,
                true,
                row.Attention == 0 ? Color.GreenText : Color.RedText);
            y += height;
        }

        return y;
    }

    private static float DrawCommunicationTable(
        ReportDocument report,
        ref PageCanvas page,
        IReadOnlyList<CommunicationRow> rows,
        float y)
    {
        if (rows.Count == 0)
        {
            page.RoundedPanel(Margin, y, ContentWidth, 58, Color.SoftGray, Color.Border);
            page.Text("Nenhuma mensagem preparada para este recorte.", Margin + 14, y + 25, 10, false, Color.Muted);
            return y + 66;
        }

        y = DrawTableHeader(page, y);
        foreach (var row in rows)
        {
            var clientLines = PageCanvas.Wrap(row.Item.ClientDisplayName, 116, 8.5f);
            var resultLines = PageCanvas.Wrap(row.Presentation.OperationResult, 142, 8.2f);
            var deliveryLines = PageCanvas.Wrap(row.Presentation.DeliveryStatus, 176, 8.2f);
            var lineCount = Math.Max(clientLines.Count, Math.Max(resultLines.Count, deliveryLines.Count));
            var height = Math.Max(45, 16 + (lineCount * 11));
            if (y + height > 770)
            {
                page = report.AddPage();
                DrawHeader(page, "SITUAÇÃO DAS COMUNICAÇÕES", report.PageCount);
                y = DrawTableHeader(page, 102);
            }

            var background = row.Presentation.NeedsAttention
                ? Color.PaleRed
                : row.Presentation.IsSimulation
                    ? Color.PaleGold
                    : Color.White;
            page.Rectangle(Margin, y, ContentWidth, height, background);
            page.Rectangle(Margin, y, ContentWidth, height, Color.Transparent, Color.Border, 0.45f);
            page.TextLines(clientLines, Margin + 7, y + 13, 8.5f, 11, true, Color.Black);
            page.Text(row.Item.PeriodLabel, Margin + 132, y + 14, 8, false, Color.Muted);
            page.TextLines(resultLines, Margin + 193, y + 13, 8.2f, 11, true, Color.Black);
            page.TextLines(
                deliveryLines,
                Margin + 344,
                y + 13,
                8.2f,
                11,
                false,
                row.Presentation.NeedsAttention ? Color.RedText : Color.Black);
            y += height;
        }

        return y;
    }

    private static float DrawCommunicationAttachments(
        ReportDocument report,
        ref PageCanvas page,
        IReadOnlyList<CommunicationRow> rows,
        float y)
    {
        if (rows.Count == 0)
        {
            page.RoundedPanel(Margin, y, ContentWidth, 46, Color.SoftGray, Color.Border);
            page.Text("Nenhuma comunicação preparada neste recorte.", Margin + 14, y + 22, 9.5f, false, Color.Muted);
            return y + 54;
        }

        foreach (var row in rows)
        {
            var attachments = row.Item.Message?.Attachments ?? [];
            var attachmentLines = attachments
                .Select(attachment =>
                    $"- {DocumentPresentation.ToPortugueseLabel(attachment.DocumentType)} — {attachment.FileName}")
                .SelectMany(line => PageCanvas.Wrap(line, ContentWidth - 32, 8.8f))
                .ToArray();
            var contentLines = attachmentLines.Length == 0
                ? ["Nenhum documento anexado a esta mensagem."]
                : attachmentLines;
            var clientLines = PageCanvas.Wrap(row.Item.ClientDisplayName, ContentWidth - 145, 9.5f);
            var metadataTop = 18 + (clientLines.Count * 11);
            var filesTop = metadataTop + 17;
            var height = filesTop + (contentLines.Length * 12) + 8;
            if (y + height > 770)
            {
                page = report.AddPage();
                DrawHeader(page, "ARQUIVOS DAS COMUNICAÇÕES", report.PageCount);
                y = 102;
            }

            page.RoundedPanel(Margin, y, ContentWidth, height, Color.SoftGray, Color.Border);
            page.TextLines(clientLines, Margin + 14, y + 14, 9.5f, 11, true, Color.Black);
            page.Text(row.Item.PeriodLabel, Margin + ContentWidth - 74, y + 17, 8.5f, true, Color.GoldText);
            page.Text(
                $"Conjunto {ShortReference(row.Item.GroupId)}  •  " +
                (attachments.Count == 1 ? "1 documento na mensagem" : $"{attachments.Count} documentos na mensagem"),
                Margin + 14,
                y + metadataTop,
                8,
                false,
                Color.Muted);
            page.TextLines(
                contentLines.ToList(),
                Margin + 14,
                y + filesTop,
                8.8f,
                12,
                false,
                attachments.Count == 0 ? Color.Muted : Color.Black);
            y += height + 8;
        }

        return y;
    }

    private static float DrawDocumentInventory(
        ReportDocument report,
        ref PageCanvas page,
        IReadOnlyList<DispatchReportDocumentEntry> documents,
        float y)
    {
        if (documents.Count == 0)
        {
            page.RoundedPanel(Margin, y, ContentWidth, 46, Color.SoftGray, Color.Border);
            page.Text("Nenhum documento encontrado neste recorte.", Margin + 14, y + 22, 9.5f, false, Color.Muted);
            return y + 54;
        }

        var groups = documents
            .GroupBy(document => document.GroupId ?? document.DocumentId)
            .Select(group =>
            {
                var first = group.First();
                return new DocumentInventoryRow(
                    first.GroupId,
                    first.ClientDisplayName ?? "Cliente não identificado",
                    first.PeriodLabel,
                    group.OrderBy(document => document.FileName, StringComparer.CurrentCultureIgnoreCase).ToArray());
            })
            .OrderBy(group => group.ClientName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(group => group.PeriodLabel, StringComparer.Ordinal)
            .ThenBy(group => group.GroupId)
            .ToArray();

        foreach (var group in groups)
        {
            var documentLines = group.Documents
                .Select(document =>
                    $"- {DocumentPresentation.ToPortugueseLabel(document.DocumentType)} — {document.FileName} • {FriendlyInventoryState(document)}")
                .SelectMany(line => PageCanvas.Wrap(line, ContentWidth - 32, 8.8f))
                .ToArray();
            var clientLines = PageCanvas.Wrap(group.ClientName, ContentWidth - 145, 9.5f);
            var metadataTop = 18 + (clientLines.Count * 11);
            var filesTop = metadataTop + 17;
            var height = filesTop + (documentLines.Length * 12) + 8;
            if (y + height > 770)
            {
                page = report.AddPage();
                DrawHeader(page, "DOCUMENTOS DO RECORTE", report.PageCount);
                y = 102;
            }

            page.RoundedPanel(Margin, y, ContentWidth, height, Color.White, Color.Border);
            page.TextLines(clientLines, Margin + 14, y + 14, 9.5f, 11, true, Color.Black);
            page.Text(group.PeriodLabel, Margin + ContentWidth - 74, y + 17, 8.5f, true, Color.GoldText);
            page.Text(
                group.GroupId.HasValue
                    ? $"Conjunto {ShortReference(group.GroupId.Value)}  •  {group.Documents.Count} documento(s)"
                    : "Ainda sem conjunto",
                Margin + 14,
                y + metadataTop,
                8,
                false,
                Color.Muted);
            page.TextLines(documentLines.ToList(), Margin + 14, y + filesTop, 8.8f, 12, false, Color.Black);
            y += height + 8;
        }

        return y;
    }

    private static string FriendlyReviewState(ReviewDocumentState state) => state switch
    {
        ReviewDocumentState.Blocked => "Precisa de correção",
        ReviewDocumentState.Ready => "Pronto para organizar",
        ReviewDocumentState.Grouped => "Pronto para revisar",
        ReviewDocumentState.Approved => "Liberado para mensagem",
        ReviewDocumentState.Duplicate => "Documento repetido",
        _ => "Importado",
    };

    private static string FriendlyInventoryState(DispatchReportDocumentEntry entry) =>
        entry.ReviewState is { } state
            ? FriendlyReviewState(state)
            : "Registrado na mensagem atual";

    private static string ClientKey(Guid? clientId, string name) =>
        clientId is { } value
            ? value.ToString("N")
            : $"name:{name.Trim().ToUpperInvariant()}";

    private static string ShortReference(Guid value) =>
        $"#{value:N}"[..7].ToUpperInvariant();

    private static float DrawTableHeader(PageCanvas page, float y)
    {
        page.Rectangle(Margin, y, ContentWidth, 27, Color.Black);
        page.Text("CLIENTE", Margin + 7, y + 17, 7.5f, true, Color.White);
        page.Text("PERÍODO", Margin + 132, y + 17, 7.5f, true, Color.White);
        page.Text("RESULTADO", Margin + 193, y + 17, 7.5f, true, Color.White);
        page.Text("ENTREGA AO DESTINATÁRIO", Margin + 344, y + 17, 7.5f, true, Color.White);
        return y + 27;
    }

    private static float EnsureSpace(
        ReportDocument report,
        PageCanvas current,
        float y,
        float required,
        out PageCanvas page)
    {
        if (y + required <= 775)
        {
            page = current;
            return y;
        }

        page = report.AddPage();
        DrawHeader(page, "RELATÓRIO OPERACIONAL", report.PageCount);
        return 105;
    }

    private static void DrawHeader(PageCanvas page, string label, int pageNumber)
    {
        page.Rectangle(0, 0, PageWidth, 72, Color.Black);
        page.Rectangle(0, 68, PageWidth, 4, Color.Gold);
        page.Text("FOLHAS DA MICHELLY", Margin, 30, 13, true, Color.Gold);
        page.Text(label, Margin, 51, 7.5f, false, Color.White);
        page.Text($"PÁGINA {pageNumber}", PageWidth - Margin - 54, 43, 7, true, Color.White);
    }

    private sealed record Metric(string Label, int Value, Color Accent);

    private sealed record CommunicationRow(
        DispatchItem Item,
        DispatchOutcomePresentation Presentation);

    private sealed record ClientSummarySource(
        string Key,
        string Name,
        Guid? CommunicationId,
        Guid? DocumentId,
        bool NeedsAttention);

    private sealed record ClientSummaryRow(
        string Name,
        int Communications,
        int Documents,
        int Attention);

    private sealed record DocumentInventoryRow(
        Guid? GroupId,
        string ClientName,
        string PeriodLabel,
        IReadOnlyList<DispatchReportDocumentEntry> Documents);

    private sealed class ReportDocument
    {
        private readonly List<PageCanvas> pages = [];

        public int PageCount => pages.Count;

        public PageCanvas AddPage()
        {
            var page = new PageCanvas();
            pages.Add(page);
            return page;
        }

        public void AddFooters(string notice)
        {
            for (var index = 0; index < pages.Count; index++)
            {
                var page = pages[index];
                page.Line(Margin, 802, PageWidth - Margin, 802, Color.Border, 0.5f);
                page.Text(notice, Margin, 817, 6.8f, false, Color.Muted);
                page.Text($"{index + 1}/{pages.Count}", PageWidth - Margin - 20, 817, 6.8f, true, Color.Muted);
            }
        }

        public void Save(string path) => MinimalPdfWriter.Write(path, pages.Select(page => page.Content).ToArray());
    }

    private sealed class PageCanvas
    {
        private readonly StringBuilder content = new();

        public string Content => content.ToString();

        public void Text(string text, float x, float top, float size, bool bold, Color color)
        {
            var baseline = PageHeight - top - size;
            content.Append("BT /").Append(bold ? "F2" : "F1").Append(' ')
                .Append(Number(size)).Append(" Tf ")
                .Append(color.RgbCommand).Append(" rg 1 0 0 1 ")
                .Append(Number(x)).Append(' ').Append(Number(baseline))
                .Append(" Tm <").Append(ToWinAnsiHex(text)).Append("> Tj ET\n");
        }

        public float WrappedText(
            string text,
            float x,
            float top,
            float width,
            float size,
            float lineHeight,
            bool bold,
            Color color)
        {
            var lines = Wrap(text, width, size);
            TextLines(lines, x, top, size, lineHeight, bold, color);
            return top + (lines.Count * lineHeight);
        }

        public void TextLines(
            List<string> lines,
            float x,
            float top,
            float size,
            float lineHeight,
            bool bold,
            Color color)
        {
            for (var index = 0; index < lines.Count; index++)
            {
                Text(lines[index], x, top + (index * lineHeight), size, bold, color);
            }
        }

        public void Rectangle(
            float x,
            float top,
            float width,
            float height,
            Color fill,
            Color? stroke = null,
            float strokeWidth = 1)
        {
            var y = PageHeight - top - height;
            if (fill != Color.Transparent)
            {
                content.Append(fill.RgbCommand).Append(" rg ")
                    .Append(Number(x)).Append(' ').Append(Number(y)).Append(' ')
                    .Append(Number(width)).Append(' ').Append(Number(height)).Append(" re f\n");
            }

            if (stroke is not null)
            {
                content.Append(stroke.RgbCommand).Append(" RG ")
                    .Append(Number(strokeWidth)).Append(" w ")
                    .Append(Number(x)).Append(' ').Append(Number(y)).Append(' ')
                    .Append(Number(width)).Append(' ').Append(Number(height)).Append(" re S\n");
            }
        }

        public void RoundedPanel(float x, float top, float width, float height, Color fill, Color stroke) =>
            Rectangle(x, top, width, height, fill, stroke, 0.7f);

        public void Line(float x1, float top1, float x2, float top2, Color color, float width)
        {
            content.Append(color.RgbCommand).Append(" RG ").Append(Number(width)).Append(" w ")
                .Append(Number(x1)).Append(' ').Append(Number(PageHeight - top1)).Append(" m ")
                .Append(Number(x2)).Append(' ').Append(Number(PageHeight - top2)).Append(" l S\n");
        }

        public static List<string> Wrap(string? value, float width, float fontSize)
        {
            var text = string.IsNullOrWhiteSpace(value) ? "—" : Normalize(value);
            var lines = new List<string>();
            foreach (var paragraph in text.Split('\n'))
            {
                var current = new StringBuilder();
                foreach (var word in paragraph.Split(' ', StringSplitOptions.RemoveEmptyEntries))
                {
                    var candidate = current.Length == 0 ? word : $"{current} {word}";
                    if (Measure(candidate, fontSize) <= width)
                    {
                        current.Clear().Append(candidate);
                        continue;
                    }

                    if (current.Length > 0)
                    {
                        lines.Add(current.ToString());
                        current.Clear();
                    }

                    if (Measure(word, fontSize) <= width)
                    {
                        current.Append(word);
                    }
                    else
                    {
                        var maximum = Math.Max(3, (int)(width / (fontSize * 0.52f)));
                        for (var offset = 0; offset < word.Length; offset += maximum)
                        {
                            lines.Add(word.Substring(offset, Math.Min(maximum, word.Length - offset)));
                        }
                    }
                }

                if (current.Length > 0)
                {
                    lines.Add(current.ToString());
                }
            }

            return lines.Count == 0 ? ["—"] : lines;
        }

        private static float Measure(string text, float size) => text.Length * size * 0.52f;
    }

    private sealed record Color(float R, float G, float B)
    {
        public static readonly Color Transparent = new(-1, -1, -1);
        public static readonly Color Black = Hex("#1C1A17");
        public static readonly Color Gold = Hex("#E1AA00");
        public static readonly Color GoldText = Hex("#8C691B");
        public static readonly Color GoldBorder = Hex("#D8B85A");
        public static readonly Color White = Hex("#FFFFFF");
        public static readonly Color Muted = Hex("#6F685D");
        public static readonly Color Border = Hex("#DED8CC");
        public static readonly Color SoftGray = Hex("#F4F1EB");
        public static readonly Color PaleGold = Hex("#FFF6DE");
        public static readonly Color PaleRed = Hex("#FCE8E6");
        public static readonly Color Green = Hex("#5B8A58");
        public static readonly Color GreenText = Hex("#2E6A37");
        public static readonly Color Red = Hex("#B94A3B");
        public static readonly Color RedText = Hex("#9A352A");
        public static readonly Color Blue = Hex("#4F74A8");

        public string RgbCommand =>
            $"{Number(R)} {Number(G)} {Number(B)}";

        private static Color Hex(string value) => new(
            Convert.ToInt32(value[1..3], 16) / 255f,
            Convert.ToInt32(value[3..5], 16) / 255f,
            Convert.ToInt32(value[5..7], 16) / 255f);
    }

    private static class MinimalPdfWriter
    {
        public static void Write(string path, string[] pageContents)
        {
            var objects = new List<byte[]>();
            var kids = string.Join(' ', Enumerable.Range(0, pageContents.Length).Select(index => $"{5 + (index * 2)} 0 R"));
            objects.Add(Ascii("<< /Type /Catalog /Pages 2 0 R >>"));
            objects.Add(Ascii($"<< /Type /Pages /Count {pageContents.Length} /Kids [{kids}] >>"));
            objects.Add(Ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
            objects.Add(Ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
            for (var index = 0; index < pageContents.Length; index++)
            {
                var pageId = 5 + (index * 2);
                var contentId = pageId + 1;
                objects.Add(Ascii(
                    $"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {Number(PageWidth)} {Number(PageHeight)}] " +
                    $"/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents {contentId} 0 R >>"));
                var stream = Ascii(pageContents[index]);
                using var contentObject = new MemoryStream();
                WriteAscii(contentObject, $"<< /Length {stream.Length} >>\nstream\n");
                contentObject.Write(stream);
                WriteAscii(contentObject, "\nendstream");
                objects.Add(contentObject.ToArray());
            }

            using var output = new MemoryStream();
            output.Write(Encoding.Latin1.GetBytes("%PDF-1.7\n%âãÏÓ\n"));
            var offsets = new List<long> { 0 };
            for (var index = 0; index < objects.Count; index++)
            {
                offsets.Add(output.Position);
                WriteAscii(output, $"{index + 1} 0 obj\n");
                output.Write(objects[index]);
                WriteAscii(output, "\nendobj\n");
            }

            var xrefOffset = output.Position;
            WriteAscii(output, $"xref\n0 {objects.Count + 1}\n");
            WriteAscii(output, "0000000000 65535 f \n");
            foreach (var offset in offsets.Skip(1))
            {
                WriteAscii(output, $"{offset:0000000000} 00000 n \n");
            }

            WriteAscii(
                output,
                $"trailer\n<< /Size {objects.Count + 1} /Root 1 0 R >>\nstartxref\n{xrefOffset}\n%%EOF\n");
            File.WriteAllBytes(path, output.ToArray());
        }

        private static byte[] Ascii(string value) => Encoding.ASCII.GetBytes(value);

        private static void WriteAscii(Stream stream, string value) => stream.Write(Ascii(value));
    }

    private static string ToWinAnsiHex(string value) =>
        Convert.ToHexString(Encoding.Latin1.GetBytes(Normalize(value)));

    private static string Normalize(string? value)
    {
        var text = (value ?? string.Empty)
            .Replace('\u2013', '-')
            .Replace('\u2014', '-')
            .Replace('\u2018', '\'')
            .Replace('\u2019', '\'')
            .Replace('\u201C', '"')
            .Replace('\u201D', '"')
            .Replace("…", "...", StringComparison.Ordinal)
            .Replace('\u00A0', ' ')
            .Replace('•', '-');
        return new string(text.Select(character => character <= byte.MaxValue ? character : '?').ToArray());
    }

    private static string Number(float value) => value.ToString("0.###", CultureInfo.InvariantCulture);
}
