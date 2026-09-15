using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

internal static class DispatchReportDocumentInventory
{
    public static IReadOnlyList<DispatchReportDocumentEntry> Build(
        DispatchWorkspace dispatch,
        DocumentReviewWorkspace review)
    {
        var entries = review.Documents.ToDictionary(
            document => document.Id,
            document => new DispatchReportDocumentEntry(
                document.Id,
                document.GroupId,
                document.ClientId,
                document.ClientDisplayName ?? "Cliente não identificado",
                document.EstablishmentId,
                document.FileName,
                document.Sha256,
                document.DocumentType,
                document.Period.DisplayLabel,
                document.State,
                document.ValidatedAtUtc,
                IsMessageSnapshotOnly: false));

        foreach (var item in dispatch.Items)
        {
            foreach (var attachment in item.Message?.Attachments ?? [])
            {
                entries.TryAdd(
                    attachment.DocumentId,
                    new DispatchReportDocumentEntry(
                        attachment.DocumentId,
                        item.GroupId,
                        item.ClientId,
                        item.ClientDisplayName,
                        item.EstablishmentId,
                        attachment.FileName,
                        attachment.Sha256,
                        attachment.DocumentType,
                        item.PeriodLabel,
                        null,
                        item.UpdatedAtUtc,
                        IsMessageSnapshotOnly: true));
            }
        }

        return entries.Values
            .OrderBy(entry => entry.ClientDisplayName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(entry => entry.PeriodLabel, StringComparer.Ordinal)
            .ThenBy(entry => entry.FileName, StringComparer.CurrentCultureIgnoreCase)
            .ToArray();
    }
}

internal sealed record DispatchReportDocumentEntry(
    Guid DocumentId,
    Guid? GroupId,
    Guid? ClientId,
    string ClientDisplayName,
    Guid? EstablishmentId,
    string FileName,
    string Sha256,
    RecognizedDocumentType DocumentType,
    string PeriodLabel,
    ReviewDocumentState? ReviewState,
    DateTimeOffset RecordedAtUtc,
    bool IsMessageSnapshotOnly);
