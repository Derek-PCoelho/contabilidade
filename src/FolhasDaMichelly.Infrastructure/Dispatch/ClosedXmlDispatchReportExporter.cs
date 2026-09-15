using System.Globalization;
using System.Text;
using ClosedXML.Excel;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class ClosedXmlDispatchReportExporter(IClock clock) : IDispatchReportExporter
{
    private const int TableHeaderRow = 5;
    private const string TimestampHeader = "Data e hora (UTC)";
    private const string StatusHeader = "Situação";

    private static readonly XLColor BrandBlack = XLColor.FromHtml("#1C1A17");
    private static readonly XLColor BrandGold = XLColor.FromHtml("#E1AA00");
    private static readonly XLColor White = XLColor.White;
    private static readonly XLColor WarmWhite = XLColor.FromHtml("#FAF8F3");
    private static readonly XLColor Border = XLColor.FromHtml("#DED8CC");
    private static readonly XLColor Text = XLColor.FromHtml("#28251F");
    private static readonly XLColor MutedText = XLColor.FromHtml("#6F685D");
    private static readonly XLColor Green = XLColor.FromHtml("#E6F4EA");
    private static readonly XLColor GreenText = XLColor.FromHtml("#1E6A36");
    private static readonly XLColor Red = XLColor.FromHtml("#FCE8E6");
    private static readonly XLColor RedText = XLColor.FromHtml("#A7352A");
    private static readonly XLColor Yellow = XLColor.FromHtml("#FFF4CE");
    private static readonly XLColor YellowText = XLColor.FromHtml("#805800");
    private static readonly XLColor Blue = XLColor.FromHtml("#E8F0FE");
    private static readonly XLColor BlueText = XLColor.FromHtml("#315D9B");
    private static readonly XLColor Gray = XLColor.FromHtml("#F0EEE9");
    private static readonly XLColor GrayText = XLColor.FromHtml("#625D54");

    private static readonly string[] ItemHeaders =
    [
        TimestampHeader,
        "Operador",
        "Cliente",
        "Estabelecimento",
        "Documento",
        "Tipo de documento",
        "Competência / período",
        "Destinatário",
        "Operação",
        StatusHeader,
        "Entrega ao destinatário",
        "Evidência disponível",
        "Próxima ação",
        "Nível de atenção",
        "Serviço de e-mail",
        "Observação",
        "Versão do aplicativo",
        "ID interno do lote",
        "ID interno do item",
        "ID interno do grupo",
        "ID interno do estabelecimento",
        "Hash abreviado",
        "Destinatário original",
        "ID da mensagem no serviço",
        "ID do rascunho no serviço",
        "Código técnico",
    ];

    private static readonly string[] ErrorHeaders =
    [
        TimestampHeader,
        "Área",
        "Descrição",
        StatusHeader,
        "Código técnico",
        "ID interno do lote",
        "ID interno do item",
        "ID interno do grupo",
    ];

    private static readonly string[] DuplicateHeaders =
    [
        "Arquivo",
        "Cliente",
        "Tipo de documento",
        "Competência / período",
        StatusHeader,
        "Hash abreviado",
        "Chave técnica de repetição",
        "ID interno do documento",
    ];

    private static readonly string[] AuditHeaders =
    [
        TimestampHeader,
        "Área",
        "Operador",
        "Ação",
        "Resultado",
        "Detalhes",
        "Código técnico",
        "ID interno do lote",
        "ID interno do item",
        "ID interno do documento",
        "ID interno do grupo",
        "Correlação técnica",
        "Valor anterior técnico",
        "Valor novo técnico",
    ];

    public Task<DispatchReportResult> ExportAsync(
        DispatchWorkspace dispatchWorkspace,
        DocumentReviewWorkspace reviewWorkspace,
        string directory,
        CancellationToken cancellationToken) =>
        ExportAsync(
            dispatchWorkspace,
            reviewWorkspace,
            DispatchReportFilter.AllPeriods,
            directory,
            cancellationToken);

    public Task<DispatchReportResult> ExportAsync(
        DispatchWorkspace dispatchWorkspace,
        DocumentReviewWorkspace reviewWorkspace,
        DispatchReportFilter filter,
        string directory,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(dispatchWorkspace);
        ArgumentNullException.ThrowIfNull(reviewWorkspace);
        ArgumentException.ThrowIfNullOrWhiteSpace(directory);
        cancellationToken.ThrowIfCancellationRequested();
        Directory.CreateDirectory(directory);
        var exportedAt = clock.UtcNow;
        var suffix = exportedAt.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
        var xlsxPath = Path.Combine(directory, $"folhas-da-michelly-{suffix}.xlsx");
        var pdfPath = Path.Combine(directory, $"folhas-da-michelly-{suffix}.pdf");
        var coverage = CoverageLabel(dispatchWorkspace, reviewWorkspace, filter);

        using var workbook = new XLWorkbook();
        WriteSummary(workbook, dispatchWorkspace, reviewWorkspace, exportedAt, coverage);
        WriteItems(workbook, dispatchWorkspace, reviewWorkspace, coverage);
        WriteErrors(workbook, dispatchWorkspace, reviewWorkspace, coverage);
        WriteDuplicates(workbook, reviewWorkspace, coverage);
        WriteAudit(workbook, dispatchWorkspace, reviewWorkspace, coverage);
        workbook.SaveAs(xlsxPath, validate: true, evaluateFormulae: true);
        BrandedDispatchPdfReportWriter.Write(
            pdfPath,
            dispatchWorkspace,
            reviewWorkspace,
            filter,
            exportedAt,
            coverage);

        var csvDirectory = Path.Combine(directory, $"folhas-da-michelly-{suffix}-csv");
        Directory.CreateDirectory(csvDirectory);
        var csvPaths = new[]
        {
            WriteCsv(csvDirectory, "Resumo.csv", SummaryRows(dispatchWorkspace, reviewWorkspace, exportedAt, coverage)),
            WriteCsv(csvDirectory, "Itens.csv", ItemRows(dispatchWorkspace, reviewWorkspace)),
            WriteCsv(csvDirectory, "Erros.csv", ErrorRows(dispatchWorkspace, reviewWorkspace)),
            WriteCsv(csvDirectory, "Duplicados.csv", DuplicateRows(reviewWorkspace)),
            WriteCsv(csvDirectory, "Auditoria.csv", AuditRows(dispatchWorkspace, reviewWorkspace)),
        };

        return Task.FromResult(new DispatchReportResult(
            xlsxPath,
            csvPaths,
            dispatchWorkspace.Items.Count,
            exportedAt,
            pdfPath));
    }

    private static void WriteSummary(
        XLWorkbook workbook,
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review,
        DateTimeOffset exportedAt,
        string coverage)
    {
        var sheet = workbook.Worksheets.Add("Resumo");
        ConfigureSheet(sheet, BrandGold, 90);
        sheet.Range("A1:H2").Merge();
        sheet.Cell("A1").Value = "Folhas da Michelly — Relatório operacional";
        StyleTitle(sheet.Range("A1:H2"));
        sheet.Range("A3:H3").Merge();
        sheet.Cell("A3").Value = "Visão simples do período selecionado. As abas seguintes permitem filtrar e conferir cada registro.";
        StyleSubtitle(sheet.Range("A3:H3"));

        sheet.Cell("A5").Value = "Competência / período";
        sheet.Range("A5:B5").Merge();
        sheet.Range("A6:B7").Merge();
        sheet.Cell("A6").SetValue(Sanitize(coverage));
        StyleMetricCard(sheet.Range("A5:B5"), sheet.Range("A6:B7"), isNumeric: false);

        sheet.Cell("C5").Value = "Gerado em (UTC)";
        sheet.Range("C5:D5").Merge();
        sheet.Range("C6:D7").Merge();
        sheet.Cell("C6").SetValue(exportedAt.UtcDateTime);
        sheet.Cell("C6").Style.DateFormat.Format = "dd/MM/yyyy HH:mm";
        StyleMetricCard(sheet.Range("C5:D5"), sheet.Range("C6:D7"), isNumeric: false);

        WriteMetricCard(sheet, "E5:F5", "E6:F7", "Mensagens", dispatch.Items.Count);
        WriteMetricCard(
            sheet,
            "G5:H5",
            "G6:H7",
            "Documentos no relatório",
            CountReportDocuments(dispatch, review));

        var presentations = dispatch.Items
            .Select(item => DispatchOutcomePresenter.Present(item, LatestAttempt(dispatch, item.Id)))
            .ToArray();
        var simulations = presentations.Count(item => item.IsSimulation && item.IsTechnicalSuccess);
        var accepted = presentations.Count(item =>
            !item.IsSimulation && item.OperationResult.Contains("aceit", StringComparison.OrdinalIgnoreCase));
        var drafts = dispatch.Items.Count(item =>
            !DispatchOutcomePresenter.Present(item, LatestAttempt(dispatch, item.Id)).IsSimulation &&
            item.Mode == DispatchOperationMode.Draft && item.State == DispatchItemState.DraftCreated);
        var failures = dispatch.Items.Count(item => item.State == DispatchItemState.Failed);
        var uncertain = dispatch.Items.Count(item => item.State is
            DispatchItemState.Ambiguous or DispatchItemState.Sending or DispatchItemState.DraftCreating);
        WriteMetricCard(sheet, "A9:B9", "A10:B11", "Simulações locais", simulations);
        WriteMetricCard(sheet, "C9:D9", "C10:D11", "Rascunhos criados", drafts);
        WriteMetricCard(sheet, "E9:F9", "E10:F11", "Aceitas pelo serviço", accepted);
        WriteMetricCard(sheet, "G9:H9", "G10:H11", "Falhas ou incertas", failures + uncertain);
        WriteMetricCard(
            sheet,
            "A13:B13",
            "A14:B15",
            "Conjuntos para mensagem",
            CountReportGroups(dispatch, review));
        WriteMetricCard(
            sheet,
            "C13:D13",
            "C14:D15",
            "Documentos repetidos",
            review.Documents.Count(document => document.State == ReviewDocumentState.Duplicate));

        sheet.Range("E13:H13").Merge();
        sheet.Cell("E13").Value = "Como interpretar";
        sheet.Range("E14:H15").Merge();
        sheet.Cell("E14").Value =
            "O relatório separa simulação, rascunho, aceitação técnica, falha e resultado incerto. Aceitação pelo serviço nunca comprova entrega ou leitura.";
        StyleInformationPanel(sheet.Range("E13:H13"), sheet.Range("E14:H15"));

        sheet.Range("A17:H17").Merge();
        sheet.Cell("A17").Value = "Legenda de situações";
        StyleSectionHeading(sheet.Range("A17:H17"));
        StyleLegendCard(sheet, "A18:B19", "Sucesso ou conclusão", Green, GreenText);
        StyleLegendCard(sheet, "C18:D19", "Atenção ou resultado incerto", Yellow, YellowText);
        StyleLegendCard(sheet, "E18:F19", "Falha ou bloqueio", Red, RedText);
        StyleLegendCard(sheet, "G18:H19", "Rascunho ou processamento", Blue, BlueText);

        sheet.Range("A21:H22").Merge();
        sheet.Cell("A21").Value =
            "Os códigos internos permanecem nas outras abas para suporte e auditoria, mas estão recolhidos. Para vê-los, reexiba as colunas ocultas no Excel.";
        sheet.Range("A21:H22").Style.Fill.BackgroundColor = Gray;
        sheet.Range("A21:H22").Style.Font.FontColor = GrayText;
        sheet.Range("A21:H22").Style.Alignment.WrapText = true;
        sheet.Range("A21:H22").Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        ApplyOutline(sheet.Range("A21:H22"));

        sheet.Cell("A24").Value = "Escopo interno";
        sheet.Cell("B24").SetValue(Sanitize(dispatch.ScopeKey));
        sheet.Row(24).Hide();
        var itemDetailRows = ItemRows(dispatch, review).Length - 1;
        var lastItemRow = TableHeaderRow + Math.Max(1, itemDetailRows);
        sheet.Cell("A25").Value = "Verificação: linhas detalhadas";
        sheet.Cell("B25").FormulaA1 = $"COUNTA(Itens!A{TableHeaderRow + 1}:A{lastItemRow})";
        sheet.Row(25).Hide();
        sheet.SheetView.FreezeRows(3);
        for (var column = 1; column <= 8; column++)
        {
            sheet.Column(column).Width = 16;
        }

        sheet.Row(1).Height = 28;
        sheet.Row(2).Height = 12;
        sheet.Row(3).Height = 26;
        sheet.Row(5).Height = 22;
        sheet.Row(6).Height = 30;
        sheet.Row(7).Height = 30;
        sheet.Row(9).Height = 22;
        sheet.Row(10).Height = 24;
        sheet.Row(11).Height = 24;
        sheet.Row(13).Height = 22;
        sheet.Row(14).Height = 26;
        sheet.Row(15).Height = 26;
        sheet.Row(17).Height = 24;
        sheet.Row(18).Height = 22;
        sheet.Row(19).Height = 22;
        sheet.Row(21).Height = 22;
        sheet.Row(22).Height = 22;
        sheet.RangeUsed()!.Style.Font.FontName = "Aptos";
    }

    private static void WriteItems(
        XLWorkbook workbook,
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review,
        string coverage)
    {
        var rows = ItemRows(dispatch, review);
        var sheet = WriteReportTable(
            workbook,
            "Itens",
            "Itens processados",
            "Uma linha por documento, com a situação da comunicação correspondente quando ela existir.",
            rows,
            "tblItens",
            coverage,
            BrandGold,
            [
                "Operador",
                "Estabelecimento",
                "Evidência disponível",
                "Nível de atenção",
                "Serviço de e-mail",
                "Observação",
                "Versão do aplicativo",
                "ID interno do lote",
                "ID interno do item",
                "ID interno do grupo",
                "ID interno do estabelecimento",
                "Hash abreviado",
                "Destinatário original",
                "ID da mensagem no serviço",
                "ID do rascunho no serviço",
                "Código técnico",
            ]);
        FormatTimestampColumn(sheet, rows, TimestampHeader);
        ColorStatusCells(sheet, rows, StatusHeader);
    }

    private static void WriteErrors(
        XLWorkbook workbook,
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review,
        string coverage)
    {
        var rows = ErrorRows(dispatch, review);
        var sheet = WriteReportTable(
            workbook,
            "Erros",
            "Erros e bloqueios",
            "Somente situações que exigem correção ou conferência antes de continuar.",
            rows,
            "tblErros",
            coverage,
            RedText,
            ["Código técnico", "ID interno do lote", "ID interno do item", "ID interno do grupo"]);
        FormatTimestampColumn(sheet, rows, TimestampHeader);
        ColorStatusCells(sheet, rows, StatusHeader);
    }

    private static void WriteDuplicates(XLWorkbook workbook, DocumentReviewWorkspace review, string coverage)
    {
        var rows = DuplicateRows(review);
        var sheet = WriteReportTable(
            workbook,
            "Duplicados",
            "Documentos repetidos",
            "Arquivos impedidos de seguir por repetição de conteúdo ou identidade documental.",
            rows,
            "tblDuplicados",
            coverage,
            GrayText,
            ["Hash abreviado", "Chave técnica de repetição", "ID interno do documento"]);
        ColorStatusCells(sheet, rows, StatusHeader);
    }

    private static void WriteAudit(
        XLWorkbook workbook,
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review,
        string coverage)
    {
        var rows = AuditRows(dispatch, review);
        var sheet = WriteReportTable(
            workbook,
            "Auditoria",
            "Histórico de ações",
            "Registro cronológico das principais ações do operador e do aplicativo.",
            rows,
            "tblAuditoria",
            coverage,
            BlueText,
            [
                "Código técnico",
                "ID interno do lote",
                "ID interno do item",
                "ID interno do documento",
                "ID interno do grupo",
                "Correlação técnica",
                "Valor anterior técnico",
                "Valor novo técnico",
            ]);
        FormatTimestampColumn(sheet, rows, TimestampHeader);
        ColorStatusCells(sheet, rows, "Resultado");
    }

    private static IXLWorksheet WriteReportTable(
        XLWorkbook workbook,
        string sheetName,
        string title,
        string description,
        string[][] rows,
        string tableName,
        string coverage,
        XLColor tabColor,
        IReadOnlyCollection<string> hiddenHeaders)
    {
        var sheet = workbook.Worksheets.Add(sheetName);
        ConfigureSheet(sheet, tabColor, 85);
        var lastColumn = rows[0].Length;
        var lastVisibleColumn = Enumerable.Range(0, rows[0].Length)
            .Where(index => !hiddenHeaders.Contains(rows[0][index], StringComparer.Ordinal))
            .Select(index => index + 1)
            .DefaultIfEmpty(1)
            .Max();
        sheet.Range(1, 1, 1, lastVisibleColumn).Merge();
        sheet.Cell(1, 1).Value = title;
        StyleTitle(sheet.Range(1, 1, 1, lastVisibleColumn));
        sheet.Range(1, 1, 1, lastVisibleColumn).Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        sheet.Range(2, 1, 2, lastVisibleColumn).Merge();
        sheet.Cell(2, 1).Value = description;
        StyleSubtitle(sheet.Range(2, 1, 2, lastVisibleColumn));
        sheet.Range(2, 1, 2, lastVisibleColumn).Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        sheet.Range(3, 1, 3, lastVisibleColumn).Merge();
        var recordCount = rows.Length - 1;
        sheet.Cell(3, 1).Value = recordCount == 0
            ? $"Competência / período: {Sanitize(coverage)} • Nenhum registro encontrado neste recorte."
            : $"Competência / período: {Sanitize(coverage)} • {recordCount} {Pluralize(recordCount, "registro", "registros")} • Use as setas do cabeçalho para filtrar ou ordenar.";
        StyleContextLine(sheet.Range(3, 1, 3, lastVisibleColumn), recordCount == 0);
        sheet.Range(3, 1, 3, lastVisibleColumn).Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        sheet.Row(4).Height = 8;

        for (var rowIndex = 0; rowIndex < rows.Length; rowIndex++)
        {
            for (var columnIndex = 0; columnIndex < rows[rowIndex].Length; columnIndex++)
            {
                var value = Sanitize(rows[rowIndex][columnIndex]);
                if (value.Length > 0)
                {
                    sheet.Cell(rowIndex + TableHeaderRow, columnIndex + 1).SetValue(value);
                }
            }
        }

        var lastRow = Math.Max(TableHeaderRow + 1, TableHeaderRow + rows.Length - 1);
        var tableRange = sheet.Range(TableHeaderRow, 1, lastRow, lastColumn);
        var table = tableRange.CreateTable(tableName);
        table.Theme = XLTableTheme.None;
        StyleHeader(sheet.Range(TableHeaderRow, 1, TableHeaderRow, lastColumn));
        StyleTableBody(sheet, lastRow, lastColumn);
        ApplyColumnPresentation(sheet, rows[0], hiddenHeaders);
        sheet.Range(1, 1, 1, lastVisibleColumn).Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        sheet.Range(2, 1, 2, lastVisibleColumn).Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        sheet.Range(3, 1, 3, lastVisibleColumn).Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        sheet.SheetView.FreezeRows(TableHeaderRow);
        sheet.SheetView.FreezeColumns(3);
        AdjustDataRowHeights(sheet, rows, hiddenHeaders);
        sheet.Row(1).Height = 32;
        sheet.Row(2).Height = 25;
        sheet.Row(3).Height = 25;
        sheet.Row(TableHeaderRow).Height = 36;
        return sheet;
    }

    private static string[][] SummaryRows(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review,
        DateTimeOffset exportedAt,
        string coverage) =>
    [
        ["Indicador", "Valor"],
        ["Competência / período", coverage],
        ["Gerado em (UTC)", exportedAt.ToString("O", CultureInfo.InvariantCulture)],
        ["Mensagens", dispatch.Items.Count.ToString(CultureInfo.InvariantCulture)],
        ["Documentos", CountReportDocuments(dispatch, review).ToString(CultureInfo.InvariantCulture)],
        ["Simulações locais concluídas", dispatch.Items.Count(item => DispatchOutcomePresenter.Present(item, LatestAttempt(dispatch, item.Id)) is { IsSimulation: true, IsTechnicalSuccess: true }).ToString(CultureInfo.InvariantCulture)],
        ["Aceitas pelo serviço", dispatch.Items.Count(item => DispatchOutcomePresenter.Present(item, LatestAttempt(dispatch, item.Id)) is { IsSimulation: false } presentation && presentation.OperationResult.Contains("aceit", StringComparison.OrdinalIgnoreCase)).ToString(CultureInfo.InvariantCulture)],
        ["Rascunhos salvos", dispatch.Items.Count(item =>
            !DispatchOutcomePresenter.Present(item, LatestAttempt(dispatch, item.Id)).IsSimulation &&
            item.Mode == DispatchOperationMode.Draft &&
            item.State == DispatchItemState.DraftCreated).ToString(CultureInfo.InvariantCulture)],
        ["Não concluídas", dispatch.Items.Count(item => item.State == DispatchItemState.Failed).ToString(CultureInfo.InvariantCulture)],
        ["Precisam de atenção", dispatch.Items.Count(item => item.State is DispatchItemState.Blocked or DispatchItemState.Ambiguous or DispatchItemState.Sending or DispatchItemState.DraftCreating).ToString(CultureInfo.InvariantCulture)],
        ["Documentos repetidos", review.Documents.Count(document => document.State == ReviewDocumentState.Duplicate).ToString(CultureInfo.InvariantCulture)],
        ["Conjuntos para mensagem", CountReportGroups(dispatch, review).ToString(CultureInfo.InvariantCulture)],
        ["Escopo interno", dispatch.ScopeKey],
    ];

    private static int CountReportGroups(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review) => review.Groups
        .Select(group => group.Id)
        .Concat(dispatch.Items.Select(item => item.GroupId))
        .Distinct()
        .Count();

    private static int CountReportDocuments(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review) =>
        DispatchReportDocumentInventory.Build(dispatch, review).Count;

    private static string[][] ItemRows(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review)
    {
        var rows = new List<string[]> { ItemHeaders };
        var attachedDocumentIds = dispatch.Items
            .SelectMany(item => item.Message?.Attachments ?? [])
            .Select(attachment => attachment.DocumentId)
            .ToHashSet();
        var reviewById = review.Documents.ToDictionary(document => document.Id);
        foreach (var item in dispatch.Items.OrderBy(item => item.CreatedAtUtc))
        {
            var batch = dispatch.Batches.SingleOrDefault(batch => batch.Id == item.BatchId);
            var attempt = dispatch.Attempts
                .Where(attempt => attempt.DispatchItemId == item.Id)
                .OrderByDescending(attempt => attempt.AttemptNumber)
                .FirstOrDefault();
            var attachments = item.Message?.Attachments ?? [];
            if (attachments.Count == 0)
            {
                rows.Add(ItemRow(item, batch, attempt, null, null));
            }
            else
            {
                rows.AddRange(attachments.Select(attachment => ItemRow(
                    item,
                    batch,
                    attempt,
                    reviewById.GetValueOrDefault(attachment.DocumentId),
                    attachment)));
            }
        }

        rows.AddRange(review.Documents
            .Where(document => !attachedDocumentIds.Contains(document.Id))
            .OrderBy(document => document.ImportedAtUtc)
            .ThenBy(document => document.ClientDisplayName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(document => document.FileName, StringComparer.CurrentCultureIgnoreCase)
            .Select(UnpreparedDocumentRow));

        return rows.ToArray();
    }

    private static string[] UnpreparedDocumentRow(ReviewDocument document)
    {
        var findings = document.Findings.Where(finding => !finding.IsResolved).ToArray();
        var severity = findings.Length == 0
            ? ValidationSeverity.Info
            : findings.Max(finding => finding.Severity);
        var nextAction = document.State switch
        {
            ReviewDocumentState.Approved => "Preparar e conferir a mensagem deste cliente.",
            ReviewDocumentState.Ready or ReviewDocumentState.Grouped =>
                "Conferir e liberar os documentos deste cliente.",
            ReviewDocumentState.Blocked => "Corrigir as pendências indicadas em Documentos.",
            ReviewDocumentState.Duplicate => "Manter fora da aprovação ou retirar o arquivo repetido.",
            _ => "Conferir o documento no aplicativo.",
        };

        return
        [
            document.ValidatedAtUtc.ToString("O", CultureInfo.InvariantCulture),
            "Aplicativo",
            document.ClientDisplayName ?? "Cliente não identificado",
            document.EstablishmentId is null ? "Matriz / geral" : "Filial / estabelecimento",
            document.FileName,
            FriendlyDocumentType(document.DocumentType),
            document.Period.DisplayLabel,
            string.Empty,
            "Documento ainda sem comunicação",
            document.State == ReviewDocumentState.Approved
                ? "Nenhuma mensagem preparada"
                : FriendlyReviewState(document.State),
            "Não houve envio",
            "Situação documental registrada somente neste aplicativo",
            nextAction,
            FriendlySeverity(severity),
            "Serviço ainda não definido",
            string.Join("; ", findings.Select(finding => finding.Message)),
            DispatchWorkflowOptions.CurrentApplicationVersion,
            string.Empty,
            string.Empty,
            document.GroupId?.ToString("D") ?? string.Empty,
            document.EstablishmentId?.ToString("D") ?? string.Empty,
            document.Sha256[..Math.Min(12, document.Sha256.Length)],
            string.Empty,
            string.Empty,
            string.Empty,
            string.Join("; ", findings.Select(finding => finding.RuleCode)),
        ];
    }

    private static string[] ItemRow(
        DispatchItem item,
        ProcessingBatch? batch,
        DeliveryAttempt? attempt,
        ReviewDocument? document,
        DispatchAttachmentSnapshot? attachment)
    {
        var establishmentId = document?.EstablishmentId ?? item.EstablishmentId;
        var documentType = document?.DocumentType ?? attachment?.DocumentType;
        var documentHash = document?.Sha256 ?? attachment?.Sha256;
        var errorCode = attempt?.ErrorCode ?? string.Join("; ", item.Blocks.Select(block => block.Code));
        var observation = attempt?.RedactedError;
        var presentation = DispatchOutcomePresenter.Present(item, attempt);
        if (string.IsNullOrWhiteSpace(observation))
        {
            observation = string.Join("; ", item.Blocks.Select(block => block.Message));
        }

        if (string.IsNullOrWhiteSpace(observation) && !string.IsNullOrWhiteSpace(errorCode))
        {
            observation = FriendlyError(errorCode);
        }

        return
        [
            (attempt?.CompletedAtUtc ?? item.UpdatedAtUtc).ToString("O", CultureInfo.InvariantCulture),
            FriendlyActor(batch?.CreatedBy),
            item.ClientDisplayName,
            establishmentId is null ? "Matriz / geral" : "Filial / estabelecimento",
            document?.FileName ?? attachment?.FileName ?? string.Empty,
            documentType is null ? string.Empty : FriendlyDocumentType(documentType.Value),
            item.PeriodLabel,
            string.Join("; ", item.Message?.EffectiveTo ?? []),
            FriendlyMode(item.Mode),
            presentation.OperationResult,
            presentation.DeliveryStatus,
            presentation.Evidence,
            presentation.NextAction,
            FriendlySeverity(MaxSeverity(item.Blocks)),
            FriendlyProvider(attempt?.ProviderKey, item.Message?.SenderAccountId),
            observation ?? string.Empty,
            DispatchWorkflowOptions.CurrentApplicationVersion,
            item.BatchId.ToString("D"),
            item.Id.ToString("D"),
            item.GroupId.ToString("D"),
            establishmentId?.ToString("D") ?? string.Empty,
            string.IsNullOrWhiteSpace(documentHash)
                ? string.Empty
                : documentHash[..Math.Min(12, documentHash.Length)],
            string.Join("; ", item.Message?.OriginalTo.Select(recipient => recipient.Email) ?? []),
            attempt?.ProviderMessageId ?? string.Empty,
            attempt?.ProviderDraftId ?? string.Empty,
            errorCode,
        ];
    }

    private static string[][] ErrorRows(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review)
    {
        var rows = new List<string[]> { ErrorHeaders };
        rows.AddRange(dispatch.Attempts
            .Where(attempt => attempt.ErrorCode is not null)
            .Select(attempt => new[]
            {
                (attempt.CompletedAtUtc ?? attempt.StartedAtUtc).ToString("O", CultureInfo.InvariantCulture),
                "Mensagens e envios",
                string.IsNullOrWhiteSpace(attempt.RedactedError)
                    ? FriendlyError(attempt.ErrorCode)
                    : attempt.RedactedError,
                FriendlyAttemptState(attempt.State),
                attempt.ErrorCode ?? string.Empty,
                attempt.BatchId.ToString("D"),
                attempt.DispatchItemId.ToString("D"),
                attempt.GroupId.ToString("D"),
            }));
        rows.AddRange(dispatch.Items.SelectMany(item => item.Blocks.Select(block => new[]
        {
            item.UpdatedAtUtc.ToString("O", CultureInfo.InvariantCulture),
            "Preparação da mensagem",
            block.Message,
            FriendlySeverity(block.Severity),
            block.Code,
            item.BatchId.ToString("D"),
            item.Id.ToString("D"),
            item.GroupId.ToString("D"),
        })));
        rows.AddRange(review.Documents.SelectMany(document => document.Findings
            .Where(finding => !finding.IsResolved && finding.Severity is
                ValidationSeverity.Error or ValidationSeverity.Blocker)
            .Select(finding => new[]
            {
                document.ValidatedAtUtc.ToString("O", CultureInfo.InvariantCulture),
                "Documentos",
                $"{document.FileName}: {finding.Message}",
                FriendlySeverity(finding.Severity),
                finding.RuleCode,
                string.Empty,
                string.Empty,
                document.GroupId?.ToString("D") ?? string.Empty,
            })));
        return rows.OrderByHeaderThenTimestamp().ToArray();
    }

    private static string[][] DuplicateRows(DocumentReviewWorkspace review)
    {
        var rows = new List<string[]> { DuplicateHeaders };
        rows.AddRange(review.Documents
            .Where(document => document.State == ReviewDocumentState.Duplicate)
            .OrderBy(document => document.ClientDisplayName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(document => document.Period.DisplayLabel, StringComparer.Ordinal)
            .ThenBy(document => document.FileName, StringComparer.CurrentCultureIgnoreCase)
            .Select(document => new[]
            {
                document.FileName,
                document.ClientDisplayName ?? "Cliente não identificado",
                FriendlyDocumentType(document.DocumentType),
                document.Period.DisplayLabel,
                FriendlyReviewState(document.State),
                document.Sha256[..Math.Min(12, document.Sha256.Length)],
                document.SemanticDuplicateKey,
                document.Id.ToString("D"),
            }));
        return rows.ToArray();
    }

    private static string[][] AuditRows(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review)
    {
        var rows = new List<string[]> { AuditHeaders };
        rows.AddRange(dispatch.AuditEvents.Select(item => new[]
        {
            item.TimestampUtc.ToString("O", CultureInfo.InvariantCulture),
            "Mensagens e envios",
            FriendlyActor(item.ActorId),
            FriendlyAction(item.Action),
            FriendlyOutcome(item.Outcome),
            string.IsNullOrWhiteSpace(item.ErrorCode) ? string.Empty : FriendlyError(item.ErrorCode),
            item.ErrorCode ?? string.Empty,
            item.BatchId?.ToString("D") ?? string.Empty,
            item.DispatchItemId?.ToString("D") ?? string.Empty,
            string.Empty,
            item.GroupId?.ToString("D") ?? string.Empty,
            item.CorrelationId,
            string.Empty,
            FriendlyOutcome(item.Outcome),
        }));
        rows.AddRange(review.AuditEvents.Select(item => new[]
        {
            item.TimestampUtc.ToString("O", CultureInfo.InvariantCulture),
            "Documentos",
            FriendlyActor(item.ActorId),
            FriendlyAction(item.Action),
            FriendlyOutcome(item.NewValue),
            item.Reason ?? string.Empty,
            string.Empty,
            string.Empty,
            string.Empty,
            item.DocumentId?.ToString("D") ?? string.Empty,
            item.GroupId?.ToString("D") ?? string.Empty,
            item.CorrelationId,
            FriendlyOutcome(item.PreviousValue),
            FriendlyOutcome(item.NewValue),
        }));
        return [AuditHeaders, .. rows.Skip(1).OrderBy(row => row[0], StringComparer.Ordinal)];
    }

    private static string WriteCsv(string directory, string fileName, string[][] rows)
    {
        var path = Path.Combine(directory, fileName);
        using var writer = new StreamWriter(path, false, new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
        foreach (var row in rows)
        {
            writer.WriteLine(string.Join(';', row.Select(CsvField)));
        }

        return path;
    }

    private static string CsvField(string value)
    {
        var safe = Sanitize(value);
        return $"\"{safe.Replace("\"", "\"\"", StringComparison.Ordinal)}\"";
    }

    private static string Sanitize(string? value)
    {
        var text = value ?? string.Empty;
        return text.Length > 0 && text[0] is '=' or '+' or '-' or '@'
            ? $"'{text}"
            : text;
    }

    private static string CoverageLabel(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review,
        DispatchReportFilter? filter = null)
    {
        if (filter is null)
        {
            var periods = dispatch.Items.Select(item => item.PeriodLabel)
                .Concat(review.Documents.Select(document => document.Period.DisplayLabel))
                .Where(period => !string.IsNullOrWhiteSpace(period))
                .Distinct(StringComparer.CurrentCultureIgnoreCase)
                .OrderBy(period => period, StringComparer.CurrentCultureIgnoreCase)
                .ToArray();
            return periods.Length switch
            {
                0 => "Sem registros no recorte selecionado",
                1 => periods[0],
                <= 3 => string.Join(", ", periods),
                _ => $"{periods.Length} competências incluídas",
            };
        }

        var periodCoverage = filter.Scope switch
        {
            DispatchReportScope.Month => $"competência {filter.Month:00}/{filter.Year:0000}",
            DispatchReportScope.Year => $"ano {filter.Year:0000}",
            DispatchReportScope.Range =>
                $"período de {filter.StartMonth:00}/{filter.StartYear:0000} a {filter.EndMonth:00}/{filter.EndYear:0000}",
            _ => "todos os períodos",
        };
        var hasClientFilter = filter.ClientId is not null || filter.Scope == DispatchReportScope.Client;
        if (hasClientFilter)
        {
            var client = filter.ClientDisplayName ??
                (dispatch.Items.Count > 0 ? dispatch.Items[0].ClientDisplayName : null) ??
                (review.Groups.Count > 0 ? review.Groups[0].ClientDisplayName : null) ??
                (review.Documents.Count > 0 ? review.Documents[0].ClientDisplayName : null) ??
                "Cliente selecionado";
            return $"Cliente: {client} • {periodCoverage}";
        }

        if (filter.Scope == DispatchReportScope.AllPeriods)
        {
            return "Todos os períodos e clientes";
        }

        return $"{char.ToUpperInvariant(periodCoverage[0])}{periodCoverage[1..]} • todos os clientes";
    }

    private static void ConfigureSheet(IXLWorksheet sheet, XLColor tabColor, int zoom)
    {
        sheet.TabColor = tabColor;
        sheet.ShowGridLines = false;
        sheet.SheetView.ZoomScale = zoom;
        sheet.Style.Font.FontName = "Aptos";
        sheet.Style.Font.FontSize = 10;
        sheet.Style.Font.FontColor = Text;
        sheet.PageSetup.PageOrientation = XLPageOrientation.Landscape;
        sheet.PageSetup.FitToPages(1, 0);
        sheet.PageSetup.Margins.SetLeft(0.25);
        sheet.PageSetup.Margins.SetRight(0.25);
        sheet.PageSetup.Margins.SetTop(0.4);
        sheet.PageSetup.Margins.SetBottom(0.4);
    }

    private static void StyleTitle(IXLRange range)
    {
        range.Style.Fill.BackgroundColor = BrandBlack;
        range.Style.Font.FontColor = BrandGold;
        range.Style.Font.Bold = true;
        range.Style.Font.FontSize = 18;
        range.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Left;
        range.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
    }

    private static void StyleSubtitle(IXLRange range)
    {
        range.Style.Fill.BackgroundColor = WarmWhite;
        range.Style.Font.FontColor = MutedText;
        range.Style.Font.FontSize = 11;
        range.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        range.Style.Alignment.WrapText = true;
        range.Style.Border.BottomBorder = XLBorderStyleValues.Thin;
        range.Style.Border.BottomBorderColor = Border;
    }

    private static void StyleContextLine(IXLRange range, bool empty)
    {
        range.Style.Fill.BackgroundColor = empty ? Gray : Blue;
        range.Style.Font.FontColor = empty ? GrayText : BlueText;
        range.Style.Font.Bold = true;
        range.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        range.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        range.Style.Alignment.WrapText = true;
        ApplyOutline(range);
    }

    private static void StyleHeader(IXLRange range)
    {
        range.Style.Fill.BackgroundColor = BrandBlack;
        range.Style.Font.FontColor = White;
        range.Style.Font.Bold = true;
        range.Style.Font.FontSize = 10;
        range.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        range.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        range.Style.Alignment.WrapText = true;
        range.Style.Border.BottomBorder = XLBorderStyleValues.Medium;
        range.Style.Border.BottomBorderColor = BrandGold;
    }

    private static void StyleTableBody(IXLWorksheet sheet, int lastRow, int lastColumn)
    {
        var body = sheet.Range(TableHeaderRow + 1, 1, lastRow, lastColumn);
        body.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        body.Style.Border.InsideBorder = XLBorderStyleValues.Hair;
        body.Style.Border.InsideBorderColor = Border;
        body.Style.Border.OutsideBorder = XLBorderStyleValues.Thin;
        body.Style.Border.OutsideBorderColor = Border;
        for (var row = TableHeaderRow + 1; row <= lastRow; row++)
        {
            sheet.Row(row).Height = 25;
            if ((row - TableHeaderRow) % 2 == 0)
            {
                sheet.Range(row, 1, row, lastColumn).Style.Fill.BackgroundColor = WarmWhite;
            }
        }
    }

    private static void ApplyColumnPresentation(
        IXLWorksheet sheet,
        string[] headers,
        IReadOnlyCollection<string> hiddenHeaders)
    {
        for (var index = 0; index < headers.Length; index++)
        {
            var header = headers[index];
            var column = sheet.Column(index + 1);
            column.Width = ColumnWidth(header);
            column.Style.Alignment.WrapText = ShouldWrap(header);
            column.Style.Alignment.Horizontal = IsCentered(header)
                ? XLAlignmentHorizontalValues.Center
                : XLAlignmentHorizontalValues.Left;
            if (hiddenHeaders.Contains(header, StringComparer.Ordinal))
            {
                column.Hide();
            }
        }
    }

    private static double ColumnWidth(string header) => header switch
    {
        TimestampHeader => 20,
        "Operador" => 20,
        "Cliente" => 30,
        "Estabelecimento" => 22,
        "Documento" or "Arquivo" => 36,
        "Tipo de documento" => 24,
        "Competência / período" => 21,
        "Destinatário" or "Destinatário original" => 34,
        "Operação" => 24,
        StatusHeader or "Resultado" or "Nível de atenção" => 27,
        "Entrega ao destinatário" or "Evidência disponível" => 38,
        "Próxima ação" => 42,
        "Serviço de e-mail" => 30,
        "Observação" or "Descrição" or "Detalhes" => 44,
        "Ação" => 32,
        "Área" => 23,
        "Versão do aplicativo" => 18,
        _ => 24,
    };

    private static bool ShouldWrap(string header) => header is
        "Cliente" or "Documento" or "Arquivo" or "Tipo de documento" or
        "Destinatário" or "Operação" or "Observação" or "Descrição" or
        "Detalhes" or "Ação" or "Entrega ao destinatário" or
        "Evidência disponível" or "Próxima ação" or "Serviço de e-mail" or
        StatusHeader or "Resultado";

    private static bool IsCentered(string header) => header is
        TimestampHeader or "Competência / período" or "Operação" or StatusHeader or
        "Nível de atenção" or "Serviço de e-mail" or "Versão do aplicativo" or "Resultado";

    private static void AdjustDataRowHeights(
        IXLWorksheet sheet,
        string[][] rows,
        IReadOnlyCollection<string> hiddenHeaders)
    {
        for (var rowIndex = 1; rowIndex < rows.Length; rowIndex++)
        {
            var maximumLines = 1;
            for (var columnIndex = 0; columnIndex < rows[rowIndex].Length; columnIndex++)
            {
                var header = rows[0][columnIndex];
                if (!ShouldWrap(header) || hiddenHeaders.Contains(header, StringComparer.Ordinal))
                {
                    continue;
                }

                var text = rows[rowIndex][columnIndex];
                var usableCharacters = Math.Max(10, (int)Math.Floor(ColumnWidth(header) - 2));
                var lines = text.Split('\n')
                    .Sum(part => Math.Max(1, (int)Math.Ceiling(part.Length / (double)usableCharacters)));
                maximumLines = Math.Max(maximumLines, Math.Min(lines, 4));
            }

            sheet.Row(TableHeaderRow + rowIndex).Height = 21 + ((maximumLines - 1) * 12);
        }
    }

    private static void FormatTimestampColumn(IXLWorksheet sheet, string[][] rows, string header)
    {
        var column = Array.IndexOf(rows[0], header) + 1;
        if (column <= 0)
        {
            return;
        }

        for (var row = 1; row < rows.Length; row++)
        {
            if (DateTimeOffset.TryParse(
                    rows[row][column - 1],
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.RoundtripKind,
                    out var timestamp))
            {
                var cell = sheet.Cell(TableHeaderRow + row, column);
                cell.SetValue(timestamp.UtcDateTime);
                cell.Style.DateFormat.Format = "dd/MM/yyyy HH:mm";
            }
        }
    }

    private static void ColorStatusCells(IXLWorksheet sheet, string[][] rows, string header)
    {
        var statusColumn = Array.IndexOf(rows[0], header) + 1;
        if (statusColumn <= 0)
        {
            return;
        }

        for (var row = 1; row < rows.Length; row++)
        {
            var cell = sheet.Cell(TableHeaderRow + row, statusColumn);
            var value = cell.GetString();
            var (background, foreground) = StatusColors(value);
            cell.Style.Fill.BackgroundColor = background;
            cell.Style.Font.FontColor = foreground;
            cell.Style.Font.Bold = true;
        }
    }

    private static (XLColor Background, XLColor Foreground) StatusColors(string value)
    {
        if (value.Contains("Aceito", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("Aprovado", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("Concluído", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("conferida", StringComparison.OrdinalIgnoreCase))
        {
            return (Green, GreenText);
        }

        if (value.Contains("falha", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("bloque", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("Não concluído", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("correção", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("Ação obrigatória", StringComparison.OrdinalIgnoreCase))
        {
            return (Red, RedText);
        }

        if (value.Contains("Atenção", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("incerto", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("repetido", StringComparison.OrdinalIgnoreCase))
        {
            return (Yellow, YellowText);
        }

        if (value.Contains("Rascunho", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("process", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("Enviando", StringComparison.OrdinalIgnoreCase) ||
            value.Contains("Pronto", StringComparison.OrdinalIgnoreCase))
        {
            return (Blue, BlueText);
        }

        return (Gray, GrayText);
    }

    private static void WriteMetricCard(
        IXLWorksheet sheet,
        string labelAddress,
        string valueAddress,
        string label,
        int value)
    {
        sheet.Range(labelAddress).Merge();
        sheet.Range(valueAddress).Merge();
        sheet.Cell(labelAddress.Split(':')[0]).Value = label;
        sheet.Cell(valueAddress.Split(':')[0]).SetValue(value);
        StyleMetricCard(sheet.Range(labelAddress), sheet.Range(valueAddress), isNumeric: true);
    }

    private static void StyleMetricCard(IXLRange labelRange, IXLRange valueRange, bool isNumeric)
    {
        labelRange.Style.Fill.BackgroundColor = BrandGold;
        labelRange.Style.Font.FontColor = BrandBlack;
        labelRange.Style.Font.Bold = true;
        labelRange.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        labelRange.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        valueRange.Style.Fill.BackgroundColor = White;
        valueRange.Style.Font.FontColor = Text;
        valueRange.Style.Font.Bold = true;
        valueRange.Style.Font.FontSize = isNumeric ? 22 : 11;
        valueRange.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        valueRange.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        valueRange.Style.Alignment.WrapText = true;
        ApplyOutline(labelRange);
        ApplyOutline(valueRange);
    }

    private static void StyleInformationPanel(IXLRange labelRange, IXLRange valueRange)
    {
        labelRange.Style.Fill.BackgroundColor = BlueText;
        labelRange.Style.Font.FontColor = BrandGold;
        labelRange.Style.Font.Bold = true;
        labelRange.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        labelRange.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        valueRange.Style.Fill.BackgroundColor = Blue;
        valueRange.Style.Font.FontColor = BlueText;
        valueRange.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        valueRange.Style.Alignment.WrapText = true;
        ApplyOutline(labelRange);
        ApplyOutline(valueRange);
    }

    private static void StyleSectionHeading(IXLRange range)
    {
        range.Style.Fill.BackgroundColor = BrandBlack;
        range.Style.Font.FontColor = BrandGold;
        range.Style.Font.Bold = true;
        range.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        range.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
    }

    private static void StyleLegendCard(
        IXLWorksheet sheet,
        string address,
        string label,
        XLColor background,
        XLColor foreground)
    {
        var range = sheet.Range(address);
        range.Merge();
        range.FirstCell().Value = label;
        range.Style.Fill.BackgroundColor = background;
        range.Style.Font.FontColor = foreground;
        range.Style.Font.Bold = true;
        range.Style.Alignment.Horizontal = XLAlignmentHorizontalValues.Center;
        range.Style.Alignment.Vertical = XLAlignmentVerticalValues.Center;
        range.Style.Alignment.WrapText = true;
        ApplyOutline(range);
    }

    private static void ApplyOutline(IXLRange range)
    {
        range.Style.Border.OutsideBorder = XLBorderStyleValues.Thin;
        range.Style.Border.OutsideBorderColor = Border;
    }

    private static ValidationSeverity MaxSeverity(IReadOnlyList<DispatchBlock> blocks) =>
        blocks.Count == 0 ? ValidationSeverity.Info : blocks.Max(block => block.Severity);

    private static string FriendlyMode(DispatchOperationMode mode) => mode switch
    {
        DispatchOperationMode.Test => "Teste seguro (sem envio real)",
        DispatchOperationMode.Draft => "Salvar como rascunho",
        DispatchOperationMode.Send => "Envio aos destinatários",
        _ => mode.ToString(),
    };

    private static string FriendlyState(DispatchItemState state) => state switch
    {
        DispatchItemState.Blocked => "Precisa de correção",
        DispatchItemState.ReadyForApproval => "Pronto para conferir",
        DispatchItemState.Approved => "Aprovado",
        DispatchItemState.DraftCreating => "Criando rascunho",
        DispatchItemState.DraftCreated => "Rascunho salvo",
        DispatchItemState.Sending => "Enviando",
        DispatchItemState.AcceptedByProvider => "Aceito pelo serviço de e-mail",
        DispatchItemState.Failed => "Não concluído",
        DispatchItemState.Ambiguous => "Resultado incerto — conferir",
        DispatchItemState.Reconciled => "Situação conferida",
        DispatchItemState.Completed => "Concluído",
        DispatchItemState.Cancelled => "Cancelado",
        _ => state.ToString(),
    };

    private static string FriendlyAttemptState(DeliveryAttemptState state) => state switch
    {
        DeliveryAttemptState.Pending => "Em processamento",
        DeliveryAttemptState.DraftCreated => "Rascunho salvo",
        DeliveryAttemptState.AcceptedByProvider => "Aceito pelo serviço de e-mail",
        DeliveryAttemptState.FailedTransient => "Falha temporária",
        DeliveryAttemptState.FailedPermanent => "Falha permanente",
        DeliveryAttemptState.Ambiguous => "Resultado incerto — conferir",
        DeliveryAttemptState.Reconciled => "Situação conferida",
        _ => state.ToString(),
    };

    private static string FriendlyReviewState(ReviewDocumentState state) => state switch
    {
        ReviewDocumentState.Blocked => "Precisa de correção",
        ReviewDocumentState.Ready => "Pronto para organizar",
        ReviewDocumentState.Grouped => "Pronto para revisar",
        ReviewDocumentState.Approved => "Aprovado",
        ReviewDocumentState.Duplicate => "Documento repetido",
        _ => state.ToString(),
    };

    private static string FriendlySeverity(ValidationSeverity severity) => severity switch
    {
        ValidationSeverity.Info => "Informação",
        ValidationSeverity.Warning => "Atenção",
        ValidationSeverity.Error => "Corrigir antes de continuar",
        ValidationSeverity.Blocker => "Ação obrigatória",
        _ => severity.ToString(),
    };

    private static string FriendlyDocumentType(RecognizedDocumentType type) =>
        DocumentPresentation.ToPortugueseLabel(type);

    private static DeliveryAttempt? LatestAttempt(DispatchWorkspace workspace, Guid dispatchItemId) =>
        workspace.Attempts
            .Where(attempt => attempt.DispatchItemId == dispatchItemId)
            .OrderByDescending(attempt => attempt.AttemptNumber)
            .FirstOrDefault();

    private static string FriendlyProvider(string? provider, string? senderAccountId)
    {
        if (!string.IsNullOrWhiteSpace(provider))
        {
            return FriendlyProviderKey(provider);
        }

        if (string.IsNullOrWhiteSpace(senderAccountId))
        {
            return "Serviço ainda não definido";
        }

        var normalizedAccount = senderAccountId.Trim().ToLowerInvariant();
        if (normalizedAccount.StartsWith("google-gmail://", StringComparison.Ordinal) ||
            normalizedAccount is "gmail" or "google.gmail")
        {
            return "Google Gmail";
        }

        if (normalizedAccount.StartsWith("microsoft-graph://", StringComparison.Ordinal) ||
            normalizedAccount is "graph" or "microsoft.graph")
        {
            return "Microsoft 365 / Outlook";
        }

        if (normalizedAccount.StartsWith("fake://", StringComparison.Ordinal) ||
            normalizedAccount == DispatchWorkflowOptions.FakeProviderKey)
        {
            return "Simulação local (sem envio real)";
        }

        return $"Conta configurada: {senderAccountId.Trim()}";
    }

    private static string FriendlyProviderKey(string provider) => provider.Trim().ToLowerInvariant() switch
    {
        "fake.local" => "Simulação local (sem envio real)",
        "gmail" or "google.gmail" => "Google Gmail",
        "graph" or "microsoft.graph" => "Microsoft 365 / Outlook",
        _ => provider.Trim(),
    };

    private static string FriendlyActor(string? actor)
    {
        if (string.IsNullOrWhiteSpace(actor))
        {
            return "Aplicativo";
        }

        return actor.StartsWith("unauthenticated-", StringComparison.OrdinalIgnoreCase) ||
               actor.StartsWith("local-", StringComparison.OrdinalIgnoreCase)
            ? "Operador local"
            : actor;
    }

    private static string FriendlyAction(string action) => action switch
    {
        "document.imported" or "document_imported" => "Documento importado",
        "document.validated" => "Documento conferido",
        "document.grouped" => "Documento organizado em conjunto",
        "document.period_corrected" => "Competência corrigida",
        "document.period_restored" => "Competência original restaurada",
        "document.client_overridden" => "Cliente corrigido manualmente",
        "document.removed_from_review" => "Documento retirado da revisão",
        "workspace.revalidated" or "workspace_revalidated" => "Documentos conferidos novamente",
        "group.approved" or "group_approved" => "Conjunto liberado para mensagem",
        "group.approval_invalidated" => "Liberação do conjunto revogada após alteração",
        "group.split" or "group_split" => "Conjunto separado",
        "group.merged" or "groups_merged" => "Conjuntos unidos",
        "group.empty_removed" => "Conjunto vazio retirado",
        "groups.selection_approved" => "Conjuntos selecionados liberados",
        "groups.bulk_approved" => "Conjuntos prontos liberados em sequência",
        "dispatch_composed" or "dispatch_prepared" => "Mensagem preparada",
        "dispatch_approved" => "Mensagem aprovada",
        "dispatch_bulk_approved" => "Mensagens aprovadas em lote",
        "dispatch_approval_invalidated" => "Aprovação da mensagem invalidada",
        "provider_call_started" => "Operação de e-mail iniciada",
        "provider_call_completed" or "dispatch_completed" => "Operação de e-mail concluída",
        "provider_reconciled" or "dispatch_reconciled" => "Resultado conferido",
        "reports_exported" => "Relatório exportado",
        _ => FriendlyFallback(action),
    };

    private static string FriendlyOutcome(string? outcome)
    {
        if (string.IsNullOrWhiteSpace(outcome))
        {
            return "Registrado";
        }

        if (Enum.TryParse<DispatchItemState>(outcome, out var dispatchState))
        {
            return FriendlyState(dispatchState);
        }

        if (Enum.TryParse<DeliveryAttemptState>(outcome, out var attemptState))
        {
            return FriendlyAttemptState(attemptState);
        }

        if (Enum.TryParse<ReviewDocumentState>(outcome, out var reviewState))
        {
            return FriendlyReviewState(reviewState);
        }

        return outcome.ToLowerInvariant() switch
        {
            "completed" => "Concluído",
            "pending" => "Em processamento",
            "failed" => "Não concluído",
            "cancelled" => "Cancelado",
            _ => FriendlyFallback(outcome),
        };
    }

    private static string FriendlyError(string? code) => code switch
    {
        "FAKE_TRANSIENT_FAILURE" => "O teste local simulou uma falha temporária.",
        "FAKE_PERMANENT_FAILURE" => "O teste local simulou uma falha permanente.",
        "FAKE_TIMEOUT_AFTER_ACCEPTANCE" => "O teste ficou sem confirmação final e precisa ser conferido antes de repetir.",
        "FAKE_AMBIGUOUS_RESULT" => "O resultado do teste ficou incerto e precisa ser conferido antes de repetir.",
        null or "" => string.Empty,
        _ => "Consulte o suporte usando o código técnico recolhido nesta linha.",
    };

    private static string FriendlyFallback(string value)
    {
        var text = value.Replace('_', ' ').Replace('.', ' ').Trim();
        return text.Length == 0
            ? "Registrado"
            : char.ToUpper(text[0], CultureInfo.GetCultureInfo("pt-BR")) + text[1..];
    }

    private static string Pluralize(int count, string singular, string plural) =>
        count == 1 ? singular : plural;
}

internal static class DispatchReportRowExtensions
{
    public static IEnumerable<string[]> OrderByHeaderThenTimestamp(this IReadOnlyCollection<string[]> rows)
    {
        var header = rows.First();
        return new[] { header }.Concat(rows.Skip(1).OrderBy(row => row[0], StringComparer.Ordinal));
    }
}
