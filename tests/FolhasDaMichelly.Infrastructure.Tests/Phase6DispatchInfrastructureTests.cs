using ClosedXML.Excel;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Dispatch;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using UglyToad.PdfPig;
using UglyToad.PdfPig.DocumentLayoutAnalysis.TextExtractor;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase6DispatchInfrastructureTests : IDisposable
{
    private readonly string temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        "folhas-phase6-infrastructure",
        Guid.NewGuid().ToString("N"));

    public Phase6DispatchInfrastructureTests()
    {
        Directory.CreateDirectory(temporaryDirectory);
    }

    [Fact]
    public async Task FakeProviderPersistsIdempotentReceiptAndReconcilesTimeoutWithoutNetworkDependency()
    {
        var provider = new FakeEmailProvider(new FakeEmailProviderOptions
        {
            OutputDirectory = Path.Combine(temporaryDirectory, "outbox"),
            SimulatedLatencyMilliseconds = 0,
        });
        var envelope = new EmailEnvelope(
            Guid.NewGuid(),
            "phase6:synthetic:idempotency-key",
            DispatchOperationMode.Test,
            new string('a', 64),
            "fake://local",
            ["auditoria@example.invalid"],
            [],
            "Assunto sintético",
            "Corpo sintético",
            "<div>Corpo sintético</div>",
            [],
            FakeDeliveryScenario.Timeout);

        var first = await provider.SendAsync(envelope, CancellationToken.None);
        var attempt = new DeliveryAttempt(
            Guid.NewGuid(),
            Guid.NewGuid(),
            envelope.DispatchItemId,
            Guid.NewGuid(),
            1,
            envelope.OperationMode,
            DeliveryAttemptState.Ambiguous,
            DispatchWorkflowOptions.FakeProviderKey,
            envelope.IdempotencyKey,
            envelope.DispatchFingerprint,
            null,
            null,
            null,
            null,
            DateTimeOffset.UtcNow,
            null);
        var reconciled = await provider.ReconcileAsync(attempt, CancellationToken.None);
        var duplicateCall = await provider.SendAsync(envelope, CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.Ambiguous, first.State);
        Assert.Equal("FAKE_TIMEOUT_AFTER_ACCEPTANCE", first.ErrorCode);
        Assert.Equal(DeliveryAttemptState.AcceptedByProvider, reconciled.State);
        Assert.Equal(reconciled, duplicateCall);
        Assert.Single(Directory.GetFiles(Path.Combine(temporaryDirectory, "outbox"), "*.json"));
        var scenarios = new[]
        {
            (FakeDeliveryScenario.TransientFailure, DeliveryAttemptState.FailedTransient, "FAKE_TRANSIENT_FAILURE"),
            (FakeDeliveryScenario.PermanentFailure, DeliveryAttemptState.FailedPermanent, "FAKE_PERMANENT_FAILURE"),
            (FakeDeliveryScenario.Ambiguous, DeliveryAttemptState.Ambiguous, "FAKE_AMBIGUOUS_RESULT"),
        };
        foreach (var (scenario, state, errorCode) in scenarios)
        {
            var result = await provider.SendAsync(
                envelope with
                {
                    IdempotencyKey = $"phase6:synthetic:{scenario}",
                    Scenario = scenario,
                },
                CancellationToken.None);
            Assert.Equal(state, result.State);
            Assert.Equal(errorCode, result.ErrorCode);
        }

        Assert.Equal(4, Directory.GetFiles(Path.Combine(temporaryDirectory, "outbox"), "*.json").Length);
        Assert.All(
            typeof(FakeEmailProvider).GetConstructors().SelectMany(constructor => constructor.GetParameters()),
            parameter => Assert.NotEqual(typeof(HttpClient), parameter.ParameterType));
    }

    [Fact]
    public async Task PendingAttemptRecoversFromSqliteAfterContextRestartAndRemainsScopeIsolated()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
            .UseSqlite(connection)
            .Options;
        var workspace = CreateWorkspace("organization-a", DeliveryAttemptState.Pending);
        await using (var first = new LocalCacheDbContext(options))
        {
            await first.Database.EnsureCreatedAsync();
            var store = new SqliteDispatchWorkflowStore(first);
            await store.SaveAsync(workspace, CancellationToken.None);
        }

        await using (var restarted = new LocalCacheDbContext(options))
        {
            var store = new SqliteDispatchWorkflowStore(restarted);
            var recovered = await store.LoadAsync("organization-a", CancellationToken.None);
            Assert.Single(recovered.Batches);
            Assert.Single(recovered.Items);
            Assert.Equal(DeliveryAttemptState.Pending, Assert.Single(recovered.Attempts).State);
            Assert.Equal(1, recovered.RecoveryRequiredCount);

            var isolated = await store.LoadAsync("organization-b", CancellationToken.None);
            Assert.Empty(isolated.Batches);
            Assert.Empty(isolated.Items);
            Assert.Empty(isolated.Attempts);
        }
    }

    [Fact]
    public async Task XlsxPdfAndCsvReportHaveAuditableViewsHonestDeliveryStatusAndFormulaProtection()
    {
        var dispatch = CreateWorkspace("synthetic-scope", DeliveryAttemptState.AcceptedByProvider);
        var review = DocumentReviewWorkspace.Empty("synthetic-scope");
        var exporter = new ClosedXmlDispatchReportExporter(new FixedClock());
        var validationDirectory = System.Environment.GetEnvironmentVariable("FOLHAS_REPORT_VALIDATION_DIR");
        var reportDirectory = string.IsNullOrWhiteSpace(validationDirectory)
            ? Path.Combine(temporaryDirectory, "reports")
            : validationDirectory;

        var result = await exporter.ExportAsync(
            dispatch,
            review,
            reportDirectory,
            CancellationToken.None);

        Assert.True(File.Exists(result.XlsxPath));
        var pdfPath = Assert.IsType<string>(result.PdfPath);
        Assert.True(File.Exists(pdfPath));
        Assert.True(new FileInfo(pdfPath).Length > 1_000);
        var pdfSignature = new byte[5];
        await using (var pdf = File.OpenRead(pdfPath))
        {
            await pdf.ReadExactlyAsync(pdfSignature, CancellationToken.None);
        }

        Assert.Equal("%PDF-", System.Text.Encoding.ASCII.GetString(pdfSignature));
        Assert.Equal(5, result.CsvPaths.Count);
        Assert.All(result.CsvPaths, path => Assert.True(File.Exists(path)));
        using var workbook = new XLWorkbook(result.XlsxPath);
        Assert.Equal(
            ["Resumo", "Itens", "Erros", "Duplicados", "Auditoria"],
            workbook.Worksheets.Select(sheet => sheet.Name).ToArray());
        var summary = workbook.Worksheet("Resumo");
        Assert.Equal("Folhas da Michelly — Relatório operacional", summary.Cell("A1").GetString());
        Assert.Equal(XLColor.FromHtml("#1C1A17"), summary.Cell("A1").Style.Fill.BackgroundColor);
        Assert.Equal(XLColor.FromHtml("#E1AA00"), summary.Cell("A1").Style.Font.FontColor);
        Assert.Equal("Todos os períodos e clientes", summary.Cell("A6").GetString());
        Assert.Equal(1, summary.Cell("E6").GetValue<int>());
        Assert.Equal(0, summary.Cell("G6").GetValue<int>());
        Assert.Equal("Conjuntos para mensagem", summary.Cell("A13").GetString());
        Assert.Equal("COUNTA(Itens!A6:A6)", summary.Cell("B25").FormulaA1);
        Assert.Equal(1, summary.Cell("B25").GetValue<int>());
        Assert.False(summary.ShowGridLines);
        Assert.Single(workbook.Worksheet("Itens").Tables);
        Assert.Single(workbook.Worksheet("Erros").Tables);
        Assert.Single(workbook.Worksheet("Duplicados").Tables);
        Assert.Single(workbook.Worksheet("Auditoria").Tables);
        Assert.Equal(
            XLAlignmentHorizontalValues.Center,
            workbook.Worksheet("Duplicados").Cell("A1").Style.Alignment.Horizontal);
        var items = workbook.Worksheet("Itens");
        Assert.Equal(5, items.SheetView.SplitRow);
        Assert.Equal(3, items.SheetView.SplitColumn);
        Assert.Equal("Data e hora (UTC)", items.Cell("A5").GetString());
        Assert.Equal(XLColor.FromHtml("#1C1A17"), items.Cell("A5").Style.Fill.BackgroundColor);
        Assert.Equal(XLDataType.DateTime, items.Cell("A6").DataType);
        Assert.Equal("Operador local", items.Cell("B6").GetString());
        Assert.Equal("Teste seguro (sem envio real)", items.Cell("I6").GetString());
        Assert.Equal("Simulação local concluída", items.Cell("J6").GetString());
        Assert.Equal("Nenhum e-mail real foi enviado", items.Cell("K6").GetString());
        Assert.Equal("Resultado registrado somente neste aplicativo", items.Cell("L6").GetString());
        Assert.Equal("Nenhuma conferência de caixa postal é necessária.", items.Cell("M6").GetString());
        Assert.Equal("Simulação local (sem envio real)", items.Cell("O6").GetString());
        Assert.All(Enumerable.Range(18, 9), column => Assert.True(items.Column(column).IsHidden));
        Assert.True(items.Table("tblItens").ShowAutoFilter);
        Assert.DoesNotContain(
            items.Range("A1:Q6").CellsUsed(),
            cell => cell.GetString().Contains("AcceptedByProvider", StringComparison.Ordinal) ||
                    cell.GetString().Contains("unauthenticated-", StringComparison.OrdinalIgnoreCase) ||
                    Guid.TryParse(cell.GetString(), out _));
        var audit = workbook.Worksheet("Auditoria");
        Assert.Equal("Operador local", audit.Cell("C6").GetString());
        Assert.All(Enumerable.Range(7, 8), column => Assert.True(audit.Column(column).IsHidden));
        Assert.DoesNotContain(
            audit.Range("A1:F6").CellsUsed(),
            cell => cell.GetString().Contains("AcceptedByProvider", StringComparison.Ordinal) ||
                    cell.GetString().Contains("unauthenticated-", StringComparison.OrdinalIgnoreCase) ||
                    Guid.TryParse(cell.GetString(), out _));
        var protectedCell = items.Cell("C6");
        Assert.Equal("=@cliente sintético", protectedCell.GetString());
        Assert.True(string.IsNullOrEmpty(protectedCell.FormulaA1));
        var itensCsv = await File.ReadAllTextAsync(result.CsvPaths.Single(path => path.EndsWith("Itens.csv", StringComparison.Ordinal)));
        Assert.Contains("'=@cliente sintético", itensCsv, StringComparison.Ordinal);
        Assert.Contains("Teste seguro (sem envio real)", itensCsv, StringComparison.Ordinal);
        Assert.Contains("Simulação local concluída", itensCsv, StringComparison.Ordinal);
        Assert.Contains("Nenhum e-mail real foi enviado", itensCsv, StringComparison.Ordinal);
        Assert.DoesNotContain("AcceptedByProvider", itensCsv, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ComposedClientAndRangeCoverageNamesEveryCommunicationAttachmentInTheXlsxAndPdf()
    {
        var original = CreateWorkspace("synthetic-scope", DeliveryAttemptState.AcceptedByProvider);
        var item = Assert.Single(original.Items);
        var attachedDocument = CreateReviewDocument(
            Guid.NewGuid(),
            item.GroupId,
            item.ClientId,
            "Cliente Alfa sintético",
            "folha-agosto.pdf",
            RecognizedDocumentType.Payroll,
            8,
            ReviewDocumentState.Approved);
        var message = item.Message! with
        {
            Attachments =
            [
                new DispatchAttachmentSnapshot(
                    attachedDocument.Id,
                    attachedDocument.LocalPath,
                    attachedDocument.FileName,
                    attachedDocument.Sha256,
                    attachedDocument.FileSizeBytes,
                    attachedDocument.DocumentType),
            ],
        };
        var dispatch = original with
        {
            Items =
            [
                item with
                {
                    ClientDisplayName = "Cliente Alfa sintético",
                    Message = message,
                },
            ],
        };
        var review = new DocumentReviewWorkspace(
            original.ScopeKey,
            [attachedDocument],
            [],
            []);
        var filter = DispatchReportFilter.ForClientInRange(
            item.ClientId,
            2026,
            8,
            2026,
            9,
            "Cliente Alfa sintético");
        var exporter = new ClosedXmlDispatchReportExporter(new FixedClock());
        var validationDirectory = System.Environment.GetEnvironmentVariable("FOLHAS_REPORT_VALIDATION_DIR");
        var reportDirectory = string.IsNullOrWhiteSpace(validationDirectory)
            ? Path.Combine(temporaryDirectory, Guid.NewGuid().ToString("N"))
            : validationDirectory;

        var result = await exporter.ExportAsync(
            dispatch,
            review,
            filter,
            reportDirectory,
            CancellationToken.None);

        const string expectedCoverage =
            "Cliente: Cliente Alfa sintético • período de 08/2026 a 09/2026";
        using (var workbook = new XLWorkbook(result.XlsxPath))
        {
            Assert.Equal(expectedCoverage, workbook.Worksheet("Resumo").Cell("A6").GetString());
        }

        using var pdf = PdfDocument.Open(Assert.IsType<string>(result.PdfPath));
        var pdfPageTexts = pdf.GetPages()
            .Select(page => ContentOrderTextExtractor.GetText(page))
            .ToArray();
        var pdfText = string.Join('\n', pdfPageTexts);
        Assert.Contains("Cliente: Cliente Alfa sintético", pdfText, StringComparison.Ordinal);
        Assert.Contains("período de 08/2026 a 09/2026", pdfText, StringComparison.Ordinal);
        Assert.Contains("Arquivos de cada comunicação", pdfText, StringComparison.Ordinal);
        Assert.Contains("Todos os documentos do recorte", pdfText, StringComparison.Ordinal);
        Assert.Contains($"Conjunto #{item.GroupId:N}"[..16], pdfText, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("Folha de pagamento", pdfText, StringComparison.Ordinal);
        Assert.Contains(attachedDocument.FileName, pdfText, StringComparison.Ordinal);
        var attachmentPage = Assert.Single(pdfPageTexts, text =>
            text.Contains("Arquivos de cada comunicação", StringComparison.Ordinal));
        Assert.Contains(attachedDocument.FileName, attachmentPage, StringComparison.Ordinal);
        Assert.DoesNotContain(attachedDocument.Id.ToString(), pdfText, StringComparison.OrdinalIgnoreCase);
        Assert.Contains(
            "Aceitação pelo serviço de e-mail não comprova entrega nem leitura",
            pdfText,
            StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("google-gmail://me", "Google Gmail")]
    [InlineData("microsoft-graph://me", "Microsoft 365 / Outlook")]
    public async Task ReportInfersPreparedEmailServiceWithoutClaimingAnAttempt(
        string senderAccountId,
        string expectedService)
    {
        var original = CreateWorkspace("synthetic-scope", DeliveryAttemptState.AcceptedByProvider);
        var item = Assert.Single(original.Items);
        var preparedOnly = original with
        {
            Batches = original.Batches
                .Select(batch => batch with { State = ProcessingBatchState.Approved })
                .ToArray(),
            Items =
            [
                item with
                {
                    State = DispatchItemState.Approved,
                    Message = item.Message! with { SenderAccountId = senderAccountId },
                },
            ],
            Attempts = [],
            AuditEvents = [],
        };
        var exporter = new ClosedXmlDispatchReportExporter(new FixedClock());

        var result = await exporter.ExportAsync(
            preparedOnly,
            DocumentReviewWorkspace.Empty("synthetic-scope"),
            Path.Combine(temporaryDirectory, Guid.NewGuid().ToString("N")),
            CancellationToken.None);

        using var workbook = new XLWorkbook(result.XlsxPath);
        var items = workbook.Worksheet("Itens");
        Assert.Equal("Mensagem aprovada e ainda não executada", items.Cell("J6").GetString());
        Assert.Equal("Não houve envio", items.Cell("K6").GetString());
        Assert.Equal(expectedService, items.Cell("O6").GetString());
        Assert.NotEqual("Simulação local (sem envio real)", items.Cell("O6").GetString());
        Assert.DoesNotContain(
            "Aceito pelo serviço de e-mail",
            items.Range("A1:Q6").CellsUsed().Select(cell => cell.GetString()));
    }

    [Fact]
    public async Task FakeDraftReportCountsACompletedSimulationButNotAConnectedAccountDraft()
    {
        var original = CreateWorkspace("synthetic-scope", DeliveryAttemptState.AcceptedByProvider);
        var item = Assert.Single(original.Items);
        var attempt = Assert.Single(original.Attempts);
        var fakeDraft = original with
        {
            Batches = original.Batches
                .Select(batch => batch with
                {
                    OperationMode = DispatchOperationMode.Draft,
                    State = ProcessingBatchState.Completed,
                })
                .ToArray(),
            Items =
            [
                item with
                {
                    Mode = DispatchOperationMode.Draft,
                    State = DispatchItemState.DraftCreated,
                },
            ],
            Attempts =
            [
                attempt with
                {
                    Mode = DispatchOperationMode.Draft,
                    State = DeliveryAttemptState.DraftCreated,
                    ProviderMessageId = null,
                    ProviderDraftId = "fake-draft",
                },
            ],
        };
        var exporter = new ClosedXmlDispatchReportExporter(new FixedClock());

        var result = await exporter.ExportAsync(
            fakeDraft,
            DocumentReviewWorkspace.Empty("synthetic-scope"),
            Path.Combine(temporaryDirectory, Guid.NewGuid().ToString("N")),
            CancellationToken.None);

        using var workbook = new XLWorkbook(result.XlsxPath);
        var summary = workbook.Worksheet("Resumo");
        var items = workbook.Worksheet("Itens");
        Assert.Equal(1, summary.Cell("A10").GetValue<int>());
        Assert.Equal(0, summary.Cell("C10").GetValue<int>());
        Assert.Equal("Simulação de rascunho concluída", items.Cell("J6").GetString());
        Assert.Equal("Nenhum e-mail real foi enviado", items.Cell("K6").GetString());
        Assert.Equal("Rascunho registrado somente neste aplicativo", items.Cell("L6").GetString());

        var resumoCsv = await File.ReadAllTextAsync(
            result.CsvPaths.Single(path => path.EndsWith("Resumo.csv", StringComparison.Ordinal)));
        Assert.Contains("\"Simulações locais concluídas\";\"1\"", resumoCsv, StringComparison.Ordinal);
        Assert.Contains("\"Rascunhos salvos\";\"0\"", resumoCsv, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ReportIncludesUnpreparedReviewDocumentOnceWithoutDuplicatingAttachedDocument()
    {
        var original = CreateWorkspace("synthetic-scope", DeliveryAttemptState.AcceptedByProvider);
        var item = Assert.Single(original.Items);
        var attachedDocument = CreateReviewDocument(
            Guid.NewGuid(),
            item.GroupId,
            item.ClientId,
            item.ClientDisplayName,
            "folha-agosto.pdf",
            RecognizedDocumentType.Payroll,
            8,
            ReviewDocumentState.Approved);
        var unpreparedDocument = CreateReviewDocument(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            "Cliente sem mensagem",
            "ferias-setembro.pdf",
            RecognizedDocumentType.Vacation,
            9,
            ReviewDocumentState.Approved);
        var message = item.Message! with
        {
            Attachments =
            [
                new DispatchAttachmentSnapshot(
                    attachedDocument.Id,
                    attachedDocument.LocalPath,
                    attachedDocument.FileName,
                    attachedDocument.Sha256,
                    attachedDocument.FileSizeBytes,
                    attachedDocument.DocumentType),
            ],
        };
        var dispatch = original with { Items = [item with { Message = message }] };
        var review = new DocumentReviewWorkspace(
            original.ScopeKey,
            [attachedDocument, unpreparedDocument],
            [],
            []);
        var exporter = new ClosedXmlDispatchReportExporter(new FixedClock());

        var result = await exporter.ExportAsync(
            dispatch,
            review,
            DispatchReportFilter.AllPeriods,
            Path.Combine(temporaryDirectory, Guid.NewGuid().ToString("N")),
            CancellationToken.None);

        using var workbook = new XLWorkbook(result.XlsxPath);
        var itemTable = workbook.Worksheet("Itens").Table("tblItens");
        var rows = itemTable.DataRange.Rows().ToArray();
        Assert.Equal(2, rows.Length);
        var attachedRow = Assert.Single(rows, row => row.Cell(5).GetString() == attachedDocument.FileName);
        var unpreparedRow = Assert.Single(rows, row => row.Cell(5).GetString() == unpreparedDocument.FileName);
        Assert.Equal("Folha de pagamento", attachedRow.Cell(6).GetString());
        Assert.Equal("Férias", unpreparedRow.Cell(6).GetString());
        Assert.Equal("Nenhuma mensagem preparada", unpreparedRow.Cell(10).GetString());
        Assert.Equal("Não houve envio", unpreparedRow.Cell(11).GetString());

        var itemCsvPath = result.CsvPaths.Single(path =>
            path.EndsWith("Itens.csv", StringComparison.Ordinal));
        var csvLines = await File.ReadAllLinesAsync(itemCsvPath, CancellationToken.None);
        Assert.Equal(3, csvLines.Length);
        Assert.Single(csvLines, line => line.Contains(attachedDocument.FileName, StringComparison.Ordinal));
        var unpreparedCsvRow = Assert.Single(csvLines, line =>
            line.Contains(unpreparedDocument.FileName, StringComparison.Ordinal));
        Assert.Contains("Férias", unpreparedCsvRow, StringComparison.Ordinal);
        Assert.Contains("Nenhuma mensagem preparada", unpreparedCsvRow, StringComparison.Ordinal);
        Assert.Contains("Não houve envio", unpreparedCsvRow, StringComparison.Ordinal);

        using var pdf = PdfDocument.Open(Assert.IsType<string>(result.PdfPath));
        var pdfText = string.Join(
            '\n',
            pdf.GetPages().Select(page => ContentOrderTextExtractor.GetText(page)));
        Assert.Contains("Todos os documentos do recorte", pdfText, StringComparison.Ordinal);
        Assert.Contains(attachedDocument.FileName, pdfText, StringComparison.Ordinal);
        Assert.Contains(unpreparedDocument.FileName, pdfText, StringComparison.Ordinal);
        Assert.Contains("Férias", pdfText, StringComparison.Ordinal);
        Assert.Contains("Liberado para mensagem", pdfText, StringComparison.Ordinal);
    }

    [Fact]
    public async Task BorealReportKeepsThreeMessageDocumentsWhenOneExistsOnlyInTheMessageSnapshot()
    {
        const string clientName = "BOREAL TECNOLOGIA SINTETICA LTDA";
        var original = CreateWorkspace("synthetic-scope", DeliveryAttemptState.AcceptedByProvider);
        var item = Assert.Single(original.Items);
        var first = CreateReviewDocument(
            Guid.NewGuid(),
            item.GroupId,
            item.ClientId,
            clientName,
            "T01_BOREAL_FOLHA_2026-08.pdf",
            RecognizedDocumentType.Payroll,
            8,
            ReviewDocumentState.Approved) with
        {
            Sha256 = new string('a', 64),
        };
        var second = CreateReviewDocument(
            Guid.NewGuid(),
            item.GroupId,
            item.ClientId,
            clientName,
            "T02_BOREAL_FGTS_2026-08.pdf",
            RecognizedDocumentType.FgtsDigital,
            8,
            ReviewDocumentState.Approved) with
        {
            Sha256 = new string('b', 64),
        };
        var snapshotOnlyDocumentId = Guid.NewGuid();
        var snapshotOnlyAttachment = new DispatchAttachmentSnapshot(
            snapshotOnlyDocumentId,
            Path.Combine("synthetic", "T03_BOREAL_FERIAS_2026-08.pdf"),
            "T03_BOREAL_FERIAS_2026-08.pdf",
            new string('c', 64),
            2_048,
            RecognizedDocumentType.Vacation);
        var attachments = new[]
        {
            new DispatchAttachmentSnapshot(
                first.Id,
                first.LocalPath,
                first.FileName,
                first.Sha256,
                first.FileSizeBytes,
                first.DocumentType),
            new DispatchAttachmentSnapshot(
                second.Id,
                second.LocalPath,
                second.FileName,
                second.Sha256,
                second.FileSizeBytes,
                second.DocumentType),
            snapshotOnlyAttachment,
        };
        var dispatch = original with
        {
            Items =
            [
                item with
                {
                    ClientDisplayName = clientName,
                    Message = item.Message! with { Attachments = attachments },
                },
            ],
        };
        var review = new DocumentReviewWorkspace(
            original.ScopeKey,
            [first, second],
            [],
            []);
        var exporter = new ClosedXmlDispatchReportExporter(new FixedClock());
        var validationDirectory = System.Environment.GetEnvironmentVariable("FOLHAS_REPORT_VALIDATION_DIR");
        var reportDirectory = string.IsNullOrWhiteSpace(validationDirectory)
            ? Path.Combine(temporaryDirectory, Guid.NewGuid().ToString("N"))
            : validationDirectory;

        var result = await exporter.ExportAsync(
            dispatch,
            review,
            DispatchReportFilter.ForClientInMonth(item.ClientId, 2026, 8, clientName),
            reportDirectory,
            CancellationToken.None);

        Assert.Equal(1, result.ItemCount);
        using (var workbook = new XLWorkbook(result.XlsxPath))
        {
            var summary = workbook.Worksheet("Resumo");
            Assert.Equal("Documentos no relatório", summary.Cell("G5").GetString());
            Assert.Equal(3, summary.Cell("G6").GetValue<int>());
            Assert.Equal(3, summary.Cell("B25").GetValue<int>());
            Assert.Equal(30, summary.Row(6).Height);
            Assert.Equal(30, summary.Row(7).Height);

            var items = workbook.Worksheet("Itens");
            var rows = items.Table("tblItens").DataRange.Rows().ToArray();
            Assert.Equal(3, rows.Length);
            Assert.Equal(
                [first.FileName, second.FileName, snapshotOnlyAttachment.FileName],
                rows.Select(row => row.Cell(5).GetString()).Order(StringComparer.Ordinal).ToArray());
            Assert.True(items.Row(6).Height > 25);
            Assert.Equal(3, items.SheetView.SplitColumn);
            Assert.All(
                Enumerable.Range(1, 17).Where(column => column is 2 or 4 or 12 or 14 or 15 or 16 or 17),
                column => Assert.True(items.Column(column).IsHidden));
            Assert.All(
                Enumerable.Range(1, 17).Where(column => column is not (2 or 4 or 12 or 14 or 15 or 16 or 17)),
                column => Assert.False(items.Column(column).IsHidden));
        }

        var itemCsvPath = result.CsvPaths.Single(path =>
            path.EndsWith("Itens.csv", StringComparison.Ordinal));
        var csvLines = await File.ReadAllLinesAsync(itemCsvPath, CancellationToken.None);
        Assert.Equal(4, csvLines.Length);
        Assert.Single(csvLines, line => line.Contains(snapshotOnlyAttachment.FileName, StringComparison.Ordinal));

        using var pdf = PdfDocument.Open(Assert.IsType<string>(result.PdfPath));
        var pdfText = string.Join(
            '\n',
            pdf.GetPages().Select(page => ContentOrderTextExtractor.GetText(page)));
        Assert.Contains(clientName, pdfText, StringComparison.Ordinal);
        Assert.Contains(first.FileName, pdfText, StringComparison.Ordinal);
        Assert.Contains(second.FileName, pdfText, StringComparison.Ordinal);
        Assert.Contains(snapshotOnlyAttachment.FileName, pdfText, StringComparison.Ordinal);
        Assert.Contains("3 documentos na mensagem", pdfText, StringComparison.Ordinal);
        Assert.Contains("Registrado na mensagem atual", pdfText, StringComparison.Ordinal);
    }

    public void Dispose()
    {
        if (Directory.Exists(temporaryDirectory))
        {
            Directory.Delete(temporaryDirectory, true);
        }

        GC.SuppressFinalize(this);
    }

    private static DispatchWorkspace CreateWorkspace(string scope, DeliveryAttemptState attemptState)
    {
        var timestamp = new DateTimeOffset(2026, 8, 21, 12, 0, 0, TimeSpan.Zero);
        var batchId = Guid.NewGuid();
        var itemId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var message = new RenderedMessageSnapshot(
            Guid.NewGuid(),
            1,
            "Assunto sintético",
            Guid.NewGuid(),
            1,
            "Corpo sintético",
            "fake://local",
            [new DispatchRecipientSnapshot(Guid.NewGuid(), "Contato sintético", "cliente@example.invalid", "To", null)],
            [],
            ["auditoria@example.invalid"],
            [],
            "=HYPERLINK(\"https://example.invalid\")",
            "Corpo exclusivamente sintético.",
            "<div>Corpo exclusivamente sintético.</div>",
            [],
            new string('a', 64),
            timestamp);
        var itemState = attemptState switch
        {
            DeliveryAttemptState.Pending => DispatchItemState.Sending,
            DeliveryAttemptState.AcceptedByProvider => DispatchItemState.AcceptedByProvider,
            _ => DispatchItemState.Ambiguous,
        };
        var item = new DispatchItem(
            itemId,
            batchId,
            groupId,
            Guid.NewGuid(),
            "=@cliente sintético",
            null,
            "08/2026",
            DispatchOperationMode.Test,
            FakeDeliveryScenario.Success,
            "auditoria@example.invalid",
            itemState,
            1,
            message,
            null,
            [],
            timestamp,
            timestamp);
        var attempt = new DeliveryAttempt(
            Guid.NewGuid(),
            batchId,
            itemId,
            groupId,
            1,
            DispatchOperationMode.Test,
            attemptState,
            "fake.local",
            $"phase6:{itemId:N}",
            message.DispatchFingerprint,
            attemptState == DeliveryAttemptState.AcceptedByProvider ? "fake-message" : null,
            null,
            null,
            null,
            timestamp,
            attemptState == DeliveryAttemptState.Pending ? null : timestamp);
        return new DispatchWorkspace(
            scope,
            [new ProcessingBatch(
                batchId,
                scope,
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                attemptState == DeliveryAttemptState.Pending
                    ? ProcessingBatchState.RecoveryRequired
                    : ProcessingBatchState.Completed,
                [groupId],
                [itemId],
                "unauthenticated-local",
                timestamp,
                timestamp)],
            [item],
            [attempt],
            [new DispatchAuditEvent(
                Guid.NewGuid(),
                scope,
                "unauthenticated-local",
                timestamp,
                "provider_call_completed",
                batchId,
                itemId,
                groupId,
                attemptState.ToString(),
                null,
                Guid.NewGuid().ToString("N"))]);
    }

    private static ReviewDocument CreateReviewDocument(
        Guid documentId,
        Guid groupId,
        Guid clientId,
        string clientDisplayName,
        string fileName,
        RecognizedDocumentType documentType,
        int month,
        ReviewDocumentState state)
    {
        var timestamp = new DateTimeOffset(2026, month, 21, 12, 0, 0, TimeSpan.Zero);
        return new ReviewDocument(
            documentId,
            Path.Combine("synthetic", fileName),
            fileName,
            new string((char)('a' + month), 64),
            1_024,
            1,
            documentType,
            "synthetic-v1",
            clientId,
            null,
            clientDisplayName,
            "11.***.***/****-81",
            ClientResolutionMethod.ExactClientTaxId,
            .99m,
            [],
            [],
            [],
            [],
            new DocumentPeriod(
                DocumentPeriodKind.Monthly,
                month,
                2026,
                null,
                null,
                new DateOnly(2026, month == 12 ? 12 : month + 1, 7),
                $"{month:00}/2026"),
            $"synthetic-document-{documentId:N}",
            state,
            1,
            groupId,
            [],
            timestamp,
            timestamp);
    }

    private sealed class FixedClock : IClock
    {
        public DateTimeOffset UtcNow => new(2026, 8, 21, 13, 0, 0, TimeSpan.Zero);
    }
}
