using FolhasDaMichelly.Application.History;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Tests;

public sealed class HistoryFilteringTests
{
    private static readonly TimeZoneInfo FortalezaTimeZone = TimeZoneInfo.CreateCustomTimeZone(
        "America-Fortaleza-Test",
        TimeSpan.FromHours(-3),
        "Fortaleza",
        "Fortaleza");

    [Fact]
    public void DayWindowUsesLocalCalendarBoundariesAndAnExclusiveEnd()
    {
        var window = HistoryTimeWindow.ForDay(new DateOnly(2026, 8, 25), FortalezaTimeZone);

        Assert.False(window.Contains(new DateTimeOffset(2026, 8, 25, 2, 59, 59, TimeSpan.Zero)));
        Assert.True(window.Contains(new DateTimeOffset(2026, 8, 25, 3, 0, 0, TimeSpan.Zero)));
        Assert.True(window.Contains(new DateTimeOffset(2026, 8, 26, 2, 59, 59, TimeSpan.Zero)));
        Assert.False(window.Contains(new DateTimeOffset(2026, 8, 26, 3, 0, 0, TimeSpan.Zero)));
    }

    [Fact]
    public void MonthAndYearWindowsRespectTheChosenLocalPeriod()
    {
        var august = HistoryTimeWindow.ForMonth(2026, 8, FortalezaTimeZone);
        var year = HistoryTimeWindow.ForYear(2026, FortalezaTimeZone);

        Assert.True(august.Contains(new DateTimeOffset(2026, 8, 1, 3, 0, 0, TimeSpan.Zero)));
        Assert.False(august.Contains(new DateTimeOffset(2026, 9, 1, 3, 0, 0, TimeSpan.Zero)));
        Assert.True(year.Contains(new DateTimeOffset(2026, 12, 31, 23, 59, 59, TimeSpan.Zero)));
        Assert.False(year.Contains(new DateTimeOffset(2027, 1, 1, 3, 0, 0, TimeSpan.Zero)));
    }

    [Fact]
    public void CustomIntervalCanSpanHoursAndDaysAndRejectsAnInvertedRange()
    {
        var window = HistoryTimeWindow.ForLocalInterval(
            new DateOnly(2026, 8, 25),
            new TimeOnly(13, 30),
            new DateOnly(2026, 8, 27),
            new TimeOnly(9, 15),
            FortalezaTimeZone);

        Assert.False(window.Contains(new DateTimeOffset(2026, 8, 25, 16, 29, 59, TimeSpan.Zero)));
        Assert.True(window.Contains(new DateTimeOffset(2026, 8, 25, 16, 30, 0, TimeSpan.Zero)));
        Assert.True(window.Contains(new DateTimeOffset(2026, 8, 27, 12, 14, 59, TimeSpan.Zero)));
        Assert.False(window.Contains(new DateTimeOffset(2026, 8, 27, 12, 15, 0, TimeSpan.Zero)));

        Assert.Throws<ArgumentOutOfRangeException>(() => HistoryTimeWindow.ForLocalInterval(
            new DateOnly(2026, 8, 27),
            new TimeOnly(9, 15),
            new DateOnly(2026, 8, 25),
            new TimeOnly(13, 30),
            FortalezaTimeZone));
    }

    [Fact]
    public void FiltersComposeClientCompetenceDocumentAndAccentInsensitiveText()
    {
        var clientId = Guid.NewGuid();
        var documentId = Guid.NewGuid();
        var matching = new HistoryFilterEntry(
            Guid.NewGuid(),
            new DateTimeOffset(2026, 8, 25, 12, 0, 0, TimeSpan.Zero),
            clientId,
            "Boreal Tecnologia Sintética Ltda",
            [new HistoryCompetence(2026, 8)],
            [new HistoryDocumentReference(documentId, RecognizedDocumentType.Payroll, "folha-agosto.pdf")],
            "Documento conferido e aprovado");
        var other = matching with
        {
            EventId = Guid.NewGuid(),
            ClientId = Guid.NewGuid(),
            ClientName = "Outro cliente",
        };
        var criteria = new HistoryFilterCriteria(
            HistoryTimeWindow.All,
            2026,
            8,
            clientId,
            documentId,
            RecognizedDocumentType.Payroll,
            "sintetica folha");

        var result = HistoryFilterEngine.Apply([matching, other], item => item, criteria).ToArray();

        Assert.Equal([matching], result);
    }

    [Fact]
    public void ReviewProjectionReadsAggregateClientPeriodsAndDocumentTypeFromAuditMetadata()
    {
        var clientId = Guid.NewGuid();
        var index = HistoryProjectionIndex.Create([], [], []);
        var audit = new ReviewAuditEvent(
            Guid.NewGuid(),
            "local",
            "operator",
            new DateTimeOffset(2026, 8, 25, 12, 0, 0, TimeSpan.Zero),
            "client.groups_approved",
            null,
            null,
            null,
            $"client:{clientId:N};client-name:Boreal Tecnologia;periods:month:2026-08|month:2026-09;documents:3;groups:2;type:Payroll",
            null,
            "correlation");

        var entry = index.Project(audit);

        Assert.Equal(clientId, entry.ClientId);
        Assert.Equal("Boreal Tecnologia", entry.ClientName);
        Assert.Contains(new HistoryCompetence(2026, 8), entry.Competences);
        Assert.Contains(new HistoryCompetence(2026, 9), entry.Competences);
        var document = Assert.Single(entry.Documents);
        Assert.Equal(RecognizedDocumentType.Payroll, document.DocumentType);
    }

    [Fact]
    public void ReviewProjectionMakesEveryDocumentInTheGroupFilterable()
    {
        var clientId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var payroll = Document(Guid.NewGuid(), groupId, clientId, "folha.pdf", RecognizedDocumentType.Payroll, 8);
        var fgts = Document(Guid.NewGuid(), groupId, clientId, "fgts.pdf", RecognizedDocumentType.FgtsDigital, 8);
        var group = Group(groupId, clientId, [payroll.Id, fgts.Id], 8);
        var index = HistoryProjectionIndex.Create(
            [payroll, fgts],
            [group],
            [],
            [KeyValuePair.Create(clientId, "Boreal Tecnologia")]);
        var audit = new ReviewAuditEvent(
            Guid.NewGuid(),
            "local",
            "operator",
            DateTimeOffset.UtcNow,
            "group.approved",
            null,
            groupId,
            null,
            null,
            null,
            "correlation");

        var entry = index.Project(audit);

        Assert.Equal(2, entry.Documents.Count);
        Assert.True(HistoryFilterEngine.Matches(
            entry,
            new HistoryFilterCriteria(
                HistoryTimeWindow.All,
                CompetenceYear: 2026,
                CompetenceMonth: 8,
                ClientId: clientId,
                DocumentId: fgts.Id,
                DocumentType: RecognizedDocumentType.FgtsDigital)));
    }

    [Fact]
    public void RemovedDocumentKeepsItsOwnIdentityAndDoesNotInheritCurrentGroupMembers()
    {
        var clientId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var removedDocumentId = Guid.NewGuid();
        var remaining = Document(
            Guid.NewGuid(),
            groupId,
            clientId,
            "folha-atual.pdf",
            RecognizedDocumentType.Payroll,
            9);
        var group = Group(groupId, clientId, [remaining.Id], 9);
        var index = HistoryProjectionIndex.Create([remaining], [group], []);
        var audit = new ReviewAuditEvent(
            Guid.NewGuid(),
            "local",
            "operator",
            new DateTimeOffset(2026, 8, 25, 12, 0, 0, TimeSpan.Zero),
            "document.removed_from_review",
            removedDocumentId,
            groupId,
            $"client:{clientId:N};client-name:Boreal Tecnologia;type:FgtsDigital;period:month:2026-08",
            "period:month:2026-09",
            "Documento retirado",
            "correlation");

        var entry = index.Project(audit);

        Assert.Equal(clientId, entry.ClientId);
        Assert.Contains(new HistoryCompetence(2026, 8), entry.Competences);
        Assert.Contains(new HistoryCompetence(2026, 9), entry.Competences);
        var reference = Assert.Single(entry.Documents);
        Assert.Equal(removedDocumentId, reference.DocumentId);
        Assert.Equal(RecognizedDocumentType.FgtsDigital, reference.DocumentType);
        Assert.True(HistoryFilterEngine.Matches(
            entry,
            new HistoryFilterCriteria(HistoryTimeWindow.All, DocumentId: removedDocumentId)));
        Assert.False(HistoryFilterEngine.Matches(
            entry,
            new HistoryFilterCriteria(HistoryTimeWindow.All, DocumentId: remaining.Id)));
    }

    [Fact]
    public void DispatchProjectionUsesTheCurrentMessageAttachmentsForDocumentFiltering()
    {
        var clientId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var payrollId = Guid.NewGuid();
        var item = DispatchItemWithAttachment(
            Guid.NewGuid(),
            groupId,
            clientId,
            payrollId,
            RecognizedDocumentType.Payroll);
        var index = HistoryProjectionIndex.Create([], [], [item]);
        var audit = new DispatchAuditEvent(
            Guid.NewGuid(),
            "local",
            "operator",
            DateTimeOffset.UtcNow,
            "dispatch_completed",
            item.BatchId,
            item.Id,
            groupId,
            "completed",
            null,
            "correlation");

        var entry = index.Project(audit);

        Assert.Equal(clientId, entry.ClientId);
        Assert.Contains(new HistoryCompetence(2026, 8), entry.Competences);
        var document = Assert.Single(entry.Documents);
        Assert.Equal(payrollId, document.DocumentId);
        Assert.Equal(RecognizedDocumentType.Payroll, document.DocumentType);
    }

    [Fact]
    public void CatalogProjectionSupportsClientAndTimeFiltersButNotDocumentFilters()
    {
        var clientId = Guid.NewGuid();
        var index = HistoryProjectionIndex.Create([], [], []);
        var audit = new AuditEventModel(
            Guid.NewGuid(),
            "Client",
            clientId.ToString(),
            "client.updated",
            "Cadastro",
            "Info",
            "{}",
            new DateTimeOffset(2026, 8, 25, 12, 0, 0, TimeSpan.Zero),
            Guid.NewGuid());
        var entry = index.Project(audit, clientId, "Boreal Tecnologia");

        Assert.True(HistoryFilterEngine.Matches(
            entry,
            new HistoryFilterCriteria(HistoryTimeWindow.All, ClientId: clientId)));
        Assert.False(HistoryFilterEngine.Matches(
            entry,
            new HistoryFilterCriteria(
                HistoryTimeWindow.All,
                ClientId: clientId,
                DocumentType: RecognizedDocumentType.Payroll)));
    }

    private static ReviewDocument Document(
        Guid id,
        Guid groupId,
        Guid clientId,
        string fileName,
        RecognizedDocumentType documentType,
        int month)
    {
        var timestamp = new DateTimeOffset(2026, month, 20, 12, 0, 0, TimeSpan.Zero);
        return new ReviewDocument(
            id,
            $"/synthetic/{fileName}",
            fileName,
            $"HASH-{id:N}",
            1,
            1,
            documentType,
            "test-v1",
            clientId,
            null,
            "Boreal Tecnologia",
            "**.***.***/****-**",
            ClientResolutionMethod.ExactClientTaxId,
            1m,
            [],
            [],
            [],
            [],
            new DocumentPeriod(DocumentPeriodKind.Monthly, month, 2026, null, null, null, $"{month:00}/2026"),
            $"semantic-{id:N}",
            ReviewDocumentState.Grouped,
            1,
            groupId,
            [],
            timestamp,
            timestamp);
    }

    private static DocumentDispatchGroup Group(
        Guid groupId,
        Guid clientId,
        IReadOnlyList<Guid> documentIds,
        int month)
    {
        var timestamp = new DateTimeOffset(2026, month, 20, 12, 0, 0, TimeSpan.Zero);
        return new DocumentDispatchGroup(
            groupId,
            $"group-{groupId:N}",
            "monthly",
            "v1",
            clientId,
            null,
            "Boreal Tecnologia",
            $"2026-{month:00}",
            $"{month:00}/2026",
            ReviewGroupState.ReadyForReview,
            1,
            documentIds,
            [],
            null,
            timestamp,
            timestamp);
    }

    private static DispatchItem DispatchItemWithAttachment(
        Guid itemId,
        Guid groupId,
        Guid clientId,
        Guid documentId,
        RecognizedDocumentType documentType)
    {
        var timestamp = new DateTimeOffset(2026, 8, 25, 12, 0, 0, TimeSpan.Zero);
        var message = new RenderedMessageSnapshot(
            Guid.NewGuid(),
            1,
            "Assunto padrão",
            Guid.NewGuid(),
            1,
            "Corpo padrão",
            "fake.local",
            [],
            [],
            ["teste@example.invalid"],
            [],
            "Documentos",
            "Corpo",
            "<p>Corpo</p>",
            [new DispatchAttachmentSnapshot(
                documentId,
                "/synthetic/folha.pdf",
                "folha.pdf",
                "HASH",
                1,
                documentType)],
            "fingerprint",
            timestamp);
        return new DispatchItem(
            itemId,
            Guid.NewGuid(),
            groupId,
            clientId,
            "Boreal Tecnologia",
            null,
            "08/2026",
            DispatchOperationMode.Test,
            FakeDeliveryScenario.Success,
            "teste@example.invalid",
            DispatchItemState.Completed,
            1,
            message,
            null,
            [],
            timestamp,
            timestamp);
    }
}
