using System.Security.Cryptography;
using System.Text;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Documents;

public sealed class DocumentReviewService(
    IDocumentReviewStore store,
    IDocumentReviewContextAccessor contextAccessor,
    IDocumentPeriodParser periodParser,
    IEnumerable<IValidationRule<DocumentValidationContext>> validationRules,
    DocumentReviewOptions options,
    IClock clock,
    IClientResolver? clientResolver = null) : IDocumentReviewService, IDisposable
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly IReadOnlyList<IValidationRule<DocumentValidationContext>> rules =
        validationRules.ToArray();

    public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) =>
        ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var result = await RevalidateWorkspaceAsync(workspace, context, false, ct);
                return (result, true);
            },
            cancellationToken);

    public Task<DocumentReviewWorkspace> ImportAsync(
        string localPath,
        DocumentRecognitionResult recognition,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(localPath);
        ArgumentNullException.ThrowIfNull(recognition);
        return ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var now = clock.UtcNow;
                var document = new ReviewDocument(
                    Guid.NewGuid(),
                    Path.GetFullPath(localPath),
                    recognition.FileName,
                    recognition.Sha256.ToUpperInvariant(),
                    recognition.FileSizeBytes,
                    recognition.PageCount,
                    recognition.DocumentType,
                    recognition.ProfileVersion,
                    recognition.Resolution.ClientId,
                    recognition.Resolution.EstablishmentId,
                    recognition.Resolution.ClientDisplayName,
                    recognition.Resolution.ClientTaxIdMasked,
                    recognition.Resolution.Method,
                    recognition.Resolution.Confidence,
                    recognition.Resolution.Alternatives,
                    recognition.Resolution.Blockers,
                    recognition.Fields,
                    recognition.Findings,
                    periodParser.Parse(recognition.Fields),
                    string.Empty,
                    ReviewDocumentState.Blocked,
                    1,
                    null,
                    [],
                    now,
                    now);
                var documents = workspace.Documents.Append(document).ToArray();
                var audits = workspace.AuditEvents.Append(Audit(
                    context,
                    "document.imported",
                    document.Id,
                    null,
                    null,
                    recognition.DocumentType.ToString(),
                    "Importação local; caminho absoluto e conteúdo não são sincronizados."))
                    .ToArray();
                var updated = workspace with { Documents = documents, AuditEvents = audits };
                updated = await RevalidateWorkspaceAsync(updated, context, true, ct);
                return (updated, true);
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) =>
        ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var updated = await RevalidateWorkspaceAsync(workspace, context, true, ct);
                updated = updated with
                {
                    AuditEvents = updated.AuditEvents.Append(Audit(
                        context,
                        "workspace.revalidated",
                        null,
                        null,
                        null,
                        $"documents:{updated.Documents.Count}",
                        null)).ToArray(),
                };
                return (updated, true);
            },
            cancellationToken);

    public Task<DocumentReviewWorkspace> CorrectPeriodAsync(
        Guid documentId,
        DocumentPeriod period,
        string reason,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(period);
        ValidateReason(reason, "period.change_reason_required");
        ValidateCorrectedPeriod(period);
        return ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var documents = workspace.Documents.ToList();
                var groups = workspace.Groups.ToList();
                var audits = workspace.AuditEvents.ToList();
                var index = documents.FindIndex(document => document.Id == documentId);
                if (index < 0)
                {
                    throw new DocumentReviewException(
                        "document.not_found",
                        "O documento selecionado não existe neste espaço de revisão.");
                }

                var document = documents[index];
                var correctedPeriod = period with
                {
                    DueDate = period.DueDate ?? document.Period.DueDate,
                };
                if (PeriodsEquivalent(document.Period, correctedPeriod))
                {
                    throw new DocumentReviewException(
                        "period.correction_unchanged",
                        "A competência informada é igual à competência atual do documento.");
                }

                if (document.GroupId.HasValue)
                {
                    DetachDocumentFromGroup(
                        documents,
                        groups,
                        audits,
                        document.Id,
                        document.GroupId.Value,
                        context,
                        "Competência corrigida manualmente.");
                    document = documents[index];
                }

                documents[index] = document with
                {
                    Period = correctedPeriod,
                    PeriodOverride = correctedPeriod,
                    GroupId = null,
                    Revision = document.Revision + 1,
                    ValidatedAtUtc = clock.UtcNow,
                };
                audits.Add(Audit(
                    context,
                    "document.period_corrected",
                    document.Id,
                    null,
                    document.Period.DisplayLabel,
                    correctedPeriod.DisplayLabel,
                    reason.Trim()));
                var updated = workspace with
                {
                    Documents = documents,
                    Groups = groups,
                    AuditEvents = audits,
                };
                updated = await RevalidateWorkspaceAsync(updated, context, true, ct);
                return (updated, true);
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(
        Guid documentId,
        string reason,
        CancellationToken cancellationToken)
    {
        ValidateReason(reason, "period.change_reason_required");
        return ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var documents = workspace.Documents.ToList();
                var groups = workspace.Groups.ToList();
                var audits = workspace.AuditEvents.ToList();
                var index = documents.FindIndex(document => document.Id == documentId);
                if (index < 0)
                {
                    throw new DocumentReviewException(
                        "document.not_found",
                        "O documento selecionado não existe neste espaço de revisão.");
                }

                var document = documents[index];
                if (document.PeriodOverride is null)
                {
                    throw new DocumentReviewException(
                        "period.override_not_found",
                        "Este documento já usa a competência reconhecida no PDF.");
                }

                var previousOverride = document.PeriodOverride;
                var extractedPeriod = periodParser.Parse(document.Fields);
                if (document.GroupId.HasValue)
                {
                    DetachDocumentFromGroup(
                        documents,
                        groups,
                        audits,
                        document.Id,
                        document.GroupId.Value,
                        context,
                        "Correção manual da competência removida.");
                    document = documents[index];
                }

                documents[index] = document with
                {
                    Period = extractedPeriod,
                    PeriodOverride = null,
                    GroupId = null,
                    Revision = document.Revision + 1,
                    ValidatedAtUtc = clock.UtcNow,
                };
                audits.Add(Audit(
                    context,
                    "document.period_restored",
                    document.Id,
                    null,
                    previousOverride.DisplayLabel,
                    extractedPeriod.DisplayLabel,
                    reason.Trim()));
                var updated = workspace with
                {
                    Documents = documents,
                    Groups = groups,
                    AuditEvents = audits,
                };
                updated = await RevalidateWorkspaceAsync(updated, context, true, ct);
                return (updated, true);
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> RemoveDocumentAsync(
        Guid documentId,
        string reason,
        CancellationToken cancellationToken)
    {
        ValidateReason(reason, "document.removal_reason_required");
        return ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var documents = workspace.Documents.ToList();
                var groups = workspace.Groups.ToList();
                var audits = workspace.AuditEvents.ToList();
                var index = documents.FindIndex(document => document.Id == documentId);
                if (index < 0)
                {
                    throw new DocumentReviewException(
                        "document.not_found",
                        "O documento selecionado não existe neste espaço de revisão.");
                }

                var document = documents[index];
                if (document.GroupId.HasValue)
                {
                    DetachDocumentFromGroup(
                        documents,
                        groups,
                        audits,
                        document.Id,
                        document.GroupId.Value,
                        context,
                        "Documento retirado da revisão ativa.");
                }

                documents.RemoveAt(index);
                audits.Add(Audit(
                    context,
                    "document.removed_from_review",
                    document.Id,
                    document.GroupId,
                    $"client:{document.ClientId?.ToString("N") ?? "-"};" +
                    $"client-name:{SanitizeAuditMetadataValue(document.ClientDisplayName)};" +
                    $"type:{document.DocumentType};period:{document.Period.CanonicalKey};sha256:{document.Sha256}",
                    null,
                    reason.Trim()));
                var updated = workspace with
                {
                    Documents = documents,
                    Groups = groups,
                    AuditEvents = audits,
                };
                updated = PruneEmptyGroups(
                    updated,
                    context,
                    "Grupo removido da revisão ativa porque ficou sem documentos.");
                updated = await RevalidateWorkspaceAsync(updated, context, true, ct);
                return (updated, true);
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> OverrideClientAsync(
        Guid documentId,
        ClientResolutionCandidate candidate,
        string reason,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        ValidateReason(reason, "client.override_reason_required");
        return ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var documents = workspace.Documents.ToList();
                var groups = workspace.Groups.ToList();
                var audits = workspace.AuditEvents.ToList();
                var index = documents.FindIndex(document => document.Id == documentId);
                if (index < 0)
                {
                    throw new DocumentReviewException(
                        "document.not_found",
                        "O documento selecionado não existe neste espaço de revisão.");
                }

                var document = documents[index];
                var trustedCandidate = document.ClientAlternatives.FirstOrDefault(item =>
                    item.ClientId == candidate.ClientId &&
                    item.EstablishmentId == candidate.EstablishmentId);
                if (trustedCandidate is null)
                {
                    throw new DocumentReviewException(
                        "client.override_candidate_not_trusted",
                        "O cliente informado não pertence às alternativas autenticadas deste documento.");
                }

                if (document.GroupId.HasValue)
                {
                    DetachDocumentFromGroup(
                        documents,
                        groups,
                        audits,
                        document.Id,
                        document.GroupId.Value,
                        context,
                        "Cliente alterado manualmente.");
                    document = documents[index];
                }

                var previous = $"{document.ClientDisplayName ?? "Não resolvido"} ({document.ClientTaxIdMasked ?? "***"})";
                var current = $"{trustedCandidate.DisplayName} ({trustedCandidate.TaxIdMasked})";
                documents[index] = document with
                {
                    ClientId = trustedCandidate.ClientId,
                    EstablishmentId = trustedCandidate.EstablishmentId,
                    ClientDisplayName = trustedCandidate.DisplayName,
                    ClientTaxIdMasked = trustedCandidate.TaxIdMasked,
                    ResolutionMethod = ClientResolutionMethod.ManualOverride,
                    ResolutionConfidence = trustedCandidate.Confidence,
                    ResolutionBlockers = [],
                    GroupId = null,
                    State = ReviewDocumentState.Blocked,
                    Revision = document.Revision + 1,
                };
                audits.Add(Audit(
                    context,
                    "document.client_overridden",
                    document.Id,
                    null,
                    previous,
                    current,
                    reason.Trim()));
                var updated = workspace with
                {
                    Documents = documents,
                    Groups = groups,
                    AuditEvents = audits,
                };
                updated = await RevalidateWorkspaceAsync(updated, context, true, ct);
                return (updated, true);
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> SplitGroupAsync(
        Guid groupId,
        IReadOnlyCollection<Guid> documentIds,
        string reason,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(documentIds);
        ValidateReason(reason, "group.change_reason_required");
        return ExecuteAsync(
            (workspace, context, ct) =>
            {
                ct.ThrowIfCancellationRequested();
                var groups = workspace.Groups.ToList();
                var documents = workspace.Documents.ToList();
                var audits = workspace.AuditEvents.ToList();
                var index = groups.FindIndex(group => group.Id == groupId);
                if (index < 0)
                {
                    throw new DocumentReviewException("group.not_found", "O grupo selecionado não existe.");
                }

                var group = groups[index];
                var selected = documentIds.Distinct().ToArray();
                if (selected.Length == 0 ||
                    selected.Length >= group.DocumentIds.Count ||
                    selected.Any(documentId => !group.DocumentIds.Contains(documentId)))
                {
                    throw new DocumentReviewException(
                        "group.split_invalid_selection",
                        "Selecione uma parte não vazia, mas não todos os documentos do grupo.");
                }

                InvalidateApproval(group, groups, index, audits, context, "Grupo separado manualmente.");
                group = groups[index];
                var now = clock.UtcNow;
                var remaining = group.DocumentIds.Except(selected).ToArray();
                groups[index] = group with
                {
                    DocumentIds = remaining,
                    Revision = group.Revision + 1,
                    State = ReviewGroupState.ReadyForReview,
                    UpdatedAtUtc = now,
                };
                var splitId = Guid.NewGuid();
                var splitGroup = group with
                {
                    Id = splitId,
                    GroupingKey = $"{group.GroupingKey}|manual:{splitId:N}",
                    State = ReviewGroupState.ReadyForReview,
                    Revision = 1,
                    DocumentIds = selected,
                    ApprovalSnapshot = null,
                    CreatedAtUtc = now,
                    UpdatedAtUtc = now,
                };
                groups.Add(splitGroup);
                for (var documentIndex = 0; documentIndex < documents.Count; documentIndex++)
                {
                    if (selected.Contains(documents[documentIndex].Id))
                    {
                        documents[documentIndex] = documents[documentIndex] with
                        {
                            GroupId = splitId,
                            State = ReviewDocumentState.Grouped,
                        };
                    }
                }

                audits.Add(Audit(
                    context,
                    "group.split",
                    null,
                    group.Id,
                    $"documents:{group.DocumentIds.Count}",
                    $"remaining:{remaining.Length};new:{selected.Length}",
                    reason.Trim()));
                var result = RecalculateGroups(workspace with
                {
                    Documents = documents,
                    Groups = groups,
                    AuditEvents = audits,
                });
                return Task.FromResult((result, true));
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> MergeGroupsAsync(
        Guid targetGroupId,
        Guid sourceGroupId,
        string reason,
        CancellationToken cancellationToken)
    {
        ValidateReason(reason, "group.change_reason_required");
        if (targetGroupId == sourceGroupId)
        {
            throw new DocumentReviewException("group.merge_same_group", "Escolha dois grupos diferentes.");
        }

        return ExecuteAsync(
            (workspace, context, ct) =>
            {
                ct.ThrowIfCancellationRequested();
                var groups = workspace.Groups.ToList();
                var documents = workspace.Documents.ToList();
                var audits = workspace.AuditEvents.ToList();
                var targetIndex = groups.FindIndex(group => group.Id == targetGroupId);
                var sourceIndex = groups.FindIndex(group => group.Id == sourceGroupId);
                if (targetIndex < 0 || sourceIndex < 0)
                {
                    throw new DocumentReviewException("group.not_found", "Um dos grupos selecionados não existe.");
                }

                var target = groups[targetIndex];
                var source = groups[sourceIndex];
                if (target.ClientId != source.ClientId)
                {
                    throw new DocumentReviewException(
                        "group.client_mismatch",
                        "Grupos de clientes diferentes nunca podem ser unidos.");
                }

                if (!string.Equals(target.PeriodKey, source.PeriodKey, StringComparison.Ordinal))
                {
                    throw new DocumentReviewException(
                        "group.period_mismatch",
                        "Grupos de períodos contábeis diferentes não podem ser unidos.");
                }

                if (target.EstablishmentId != source.EstablishmentId)
                {
                    throw new DocumentReviewException(
                        "group.establishment_mismatch",
                        "Conjuntos de estabelecimentos diferentes não podem ser unidos.");
                }

                if (!string.Equals(
                        target.GroupingPolicyCode,
                        source.GroupingPolicyCode,
                        StringComparison.Ordinal) ||
                    !string.Equals(
                        target.GroupingPolicyVersion,
                        source.GroupingPolicyVersion,
                        StringComparison.Ordinal))
                {
                    throw new DocumentReviewException(
                        "group.policy_mismatch",
                        "Conjuntos formados por políticas ou versões diferentes não podem ser unidos.");
                }

                InvalidateApproval(target, groups, targetIndex, audits, context, "Grupo unido manualmente.");
                sourceIndex = groups.FindIndex(group => group.Id == sourceGroupId);
                source = groups[sourceIndex];
                InvalidateApproval(source, groups, sourceIndex, audits, context, "Grupo unido manualmente.");
                targetIndex = groups.FindIndex(group => group.Id == targetGroupId);
                target = groups[targetIndex];
                sourceIndex = groups.FindIndex(group => group.Id == sourceGroupId);
                source = groups[sourceIndex];
                var mergedIds = target.DocumentIds.Concat(source.DocumentIds).Distinct().ToArray();
                groups[targetIndex] = target with
                {
                    GroupingKey = $"manual:{target.Id:N}:{target.PeriodKey}",
                    DocumentIds = mergedIds,
                    Revision = target.Revision + 1,
                    State = ReviewGroupState.ReadyForReview,
                    UpdatedAtUtc = clock.UtcNow,
                };
                groups.RemoveAt(sourceIndex);
                for (var documentIndex = 0; documentIndex < documents.Count; documentIndex++)
                {
                    if (source.DocumentIds.Contains(documents[documentIndex].Id))
                    {
                        documents[documentIndex] = documents[documentIndex] with
                        {
                            GroupId = target.Id,
                            State = ReviewDocumentState.Grouped,
                        };
                    }
                }

                audits.Add(Audit(
                    context,
                    "group.merged",
                    null,
                    target.Id,
                    $"groups:{target.Id:N},{source.Id:N}",
                    $"documents:{mergedIds.Length}",
                    reason.Trim()));
                var result = RecalculateGroups(workspace with
                {
                    Documents = documents,
                    Groups = groups,
                    AuditEvents = audits,
                });
                return Task.FromResult((result, true));
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> ApproveGroupAsync(
        Guid groupId,
        CancellationToken cancellationToken) =>
        ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var refreshed = await RevalidateWorkspaceAsync(workspace, context, true, ct);
                var approved = ApproveGroup(refreshed, groupId, context);
                return (approved, true);
            },
            cancellationToken);

    public Task<DocumentReviewWorkspace> ApproveGroupsAsync(
        IReadOnlyCollection<Guid> groupIds,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(groupIds);
        var requestedIds = groupIds.Distinct().ToArray();
        if (requestedIds.Length == 0)
        {
            throw new DocumentReviewException(
                "groups.selection_required",
                "Selecione ao menos um grupo pronto para aprovação.");
        }

        return ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var updated = await RevalidateWorkspaceAsync(workspace, context, true, ct);
                var requestedGroups = requestedIds.Select(groupId =>
                    GetApprovableGroup(updated, groupId)).ToArray();
                if (requestedGroups
                    .Select(group => ApprovalCompetenceKey(group, updated))
                    .Distinct(StringComparer.Ordinal)
                    .Count() > 1)
                {
                    throw new DocumentReviewException(
                        "groups.period_mismatch",
                        "A aprovação em lote deve conter conjuntos de uma única competência mensal.");
                }

                foreach (var groupId in requestedIds)
                {
                    updated = ApproveGroup(updated, groupId, context);
                }

                updated = updated with
                {
                    AuditEvents = updated.AuditEvents.Append(Audit(
                        context,
                        "groups.selection_approved",
                        null,
                        null,
                        null,
                        $"approved:{requestedIds.Length}",
                        "Aprovação atômica restrita aos grupos explicitamente selecionados."))
                        .ToArray(),
                };
                return (updated, true);
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> ApproveClientGroupsAsync(
        Guid clientId,
        IReadOnlyCollection<Guid> groupIds,
        CancellationToken cancellationToken)
    {
        if (clientId == Guid.Empty)
        {
            throw new DocumentReviewException(
                "groups.client_required",
                "Selecione um cliente válido para liberar seus conjuntos prontos.");
        }

        ArgumentNullException.ThrowIfNull(groupIds);
        var requestedIds = groupIds.Distinct().ToArray();
        if (requestedIds.Length == 0)
        {
            throw new DocumentReviewException(
                "groups.selection_required",
                "Selecione ao menos um conjunto pronto deste cliente.");
        }

        return ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var updated = await RevalidateWorkspaceAsync(workspace, context, true, ct);
                var requestedGroups = requestedIds.Select(groupId =>
                    updated.Groups.FirstOrDefault(group => group.Id == groupId) ??
                    throw new DocumentReviewException(
                        "group.not_found",
                        "Um dos conjuntos selecionados não existe mais neste espaço de revisão."))
                    .ToArray();
                if (requestedGroups.Any(group => group.ClientId != clientId))
                {
                    throw new DocumentReviewException(
                        "group.client_mismatch",
                        "Todos os conjuntos selecionados devem pertencer ao mesmo cliente informado.");
                }

                foreach (var group in requestedGroups)
                {
                    GetApprovableGroup(updated, group.Id);
                }

                var allApprovableClientIds = updated.Groups
                    .Where(group => group.ClientId == clientId && IsGroupApprovable(updated, group))
                    .Select(group => group.Id)
                    .ToHashSet();
                if (!allApprovableClientIds.SetEquals(requestedIds))
                {
                    throw new DocumentReviewException(
                        "groups.client_selection_incomplete",
                        "A liberação do cliente deve incluir todos os seus conjuntos prontos.");
                }

                var documentCount = requestedGroups
                    .SelectMany(group => group.DocumentIds)
                    .Distinct()
                    .Count();
                var competenceCount = requestedGroups
                    .Select(group => ApprovalCompetenceKey(group, updated))
                    .Distinct(StringComparer.Ordinal)
                    .Count();
                var competenceLabels = requestedGroups
                    .Select(group => group.PeriodLabel)
                    .Distinct(StringComparer.CurrentCultureIgnoreCase)
                    .OrderBy(label => label, StringComparer.CurrentCultureIgnoreCase)
                    .ToArray();
                foreach (var groupId in requestedIds)
                {
                    updated = ApproveGroup(updated, groupId, context);
                }

                updated = updated with
                {
                    AuditEvents = updated.AuditEvents.Append(Audit(
                        context,
                        "groups.client_approved",
                        null,
                        null,
                        null,
                        $"client:{clientId:N};groups:{requestedIds.Length};documents:{documentCount};" +
                        $"competences:{competenceCount};periods:{string.Join('|', competenceLabels)}",
                        "Aprovação humana do cliente; os conjuntos e as competências permaneceram separados."))
                        .ToArray(),
                };
                return (updated, true);
            },
            cancellationToken);
    }

    public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(
        CancellationToken cancellationToken) =>
        ExecuteAsync(
            async (workspace, context, ct) =>
            {
                var updated = await RevalidateWorkspaceAsync(workspace, context, true, ct);
                var eligible = updated.Groups
                    .Where(group => group.State == ReviewGroupState.ReadyForReview && !group.PreventsApproval)
                    .ToArray();
                if (eligible
                    .Select(group => ApprovalCompetenceKey(group, updated))
                    .Distinct(StringComparer.Ordinal)
                    .Count() > 1)
                {
                    throw new DocumentReviewException(
                        "groups.period_mismatch",
                        "Selecione uma única competência mensal antes da aprovação em lote.");
                }

                foreach (var group in eligible)
                {
                    updated = ApproveGroup(updated, group.Id, context);
                }

                updated = updated with
                {
                    AuditEvents = updated.AuditEvents.Append(Audit(
                        context,
                        "groups.bulk_approved",
                        null,
                        null,
                        null,
                        $"approved:{eligible.Length}",
                        "Somente grupos elegíveis, sem erro ou bloqueio."))
                        .ToArray(),
                };
                return (updated, true);
            },
            cancellationToken);

    public void Dispose()
    {
        gate.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task<DocumentReviewWorkspace> RevalidateWorkspaceAsync(
        DocumentReviewWorkspace workspace,
        DocumentReviewContext context,
        bool recordAudit,
        CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var accountingDate = DateOnly.FromDateTime(now.UtcDateTime);
        var evaluated = new List<ReviewDocument>(workspace.Documents.Count);
        foreach (var document in workspace.Documents)
        {
            var period = document.PeriodOverride ?? periodParser.Parse(document.Fields);
            var profile = options.Profiles.GetValueOrDefault(document.DocumentType);
            var candidate = document with { Period = period };
            if (clientResolver is not null)
            {
                var resolution = await clientResolver.ResolveAsync(
                    new ClientResolutionRequest(candidate.Fields
                        .Where(field => field.Role is
                            SemanticFieldRole.EmployerTaxId or
                            SemanticFieldRole.ClientTaxId or
                            SemanticFieldRole.EstablishmentTaxId or
                            SemanticFieldRole.EmployerName or
                            SemanticFieldRole.ClientName or
                            SemanticFieldRole.InternalCode)
                        .ToArray()),
                    cancellationToken);
                candidate = ApplyRefreshedResolution(candidate, resolution);
            }
            var validationContext = new DocumentValidationContext(
                candidate,
                profile,
                accountingDate,
                now);
            var findings = new List<ValidationFinding>();
            foreach (var rule in rules)
            {
                findings.AddRange(await rule.EvaluateAsync(validationContext, cancellationToken));
            }

            var normalized = PreserveFindingHistory(document.Findings, findings);
            var semanticKey = BuildSemanticDuplicateKey(candidate, profile);
            evaluated.Add(candidate with
            {
                SemanticDuplicateKey = semanticKey,
                Findings = normalized,
                State = normalized.Any(finding => finding.PreventsApproval)
                    ? ReviewDocumentState.Blocked
                    : ReviewDocumentState.Ready,
                ValidatedAtUtc = now,
            });
        }

        ApplyDuplicates(evaluated, now);
        var audits = workspace.AuditEvents.ToList();
        for (var index = 0; index < evaluated.Count; index++)
        {
            var previous = workspace.Documents[index];
            var current = evaluated[index];
            if (ReviewChanged(previous, current))
            {
                evaluated[index] = current with { Revision = previous.Revision + 1 };
                if (recordAudit)
                {
                    audits.Add(Audit(
                        context,
                        "document.validated",
                        current.Id,
                        current.GroupId,
                        previous.State.ToString(),
                        current.State.ToString(),
                        null));
                }
            }
            else
            {
                evaluated[index] = current with
                {
                    Revision = previous.Revision,
                    ValidatedAtUtc = previous.ValidatedAtUtc,
                };
            }
        }

        var groups = workspace.Groups.ToList();
        DetachDocumentsWithChangedGroupingIdentity(
            workspace.Documents,
            evaluated,
            groups,
            audits,
            context);
        DetachIneligibleDocuments(evaluated, groups, audits, context);
        var updated = workspace with
        {
            Documents = evaluated,
            Groups = groups,
            AuditEvents = audits,
        };
        updated = AttachEligibleDocuments(updated, context);
        updated = PruneEmptyGroups(
            updated,
            context,
            "Grupo removido automaticamente porque ficou sem documentos ativos.");
        updated = InvalidateChangedApprovalSnapshots(updated, context);
        return RecalculateGroups(updated);
    }

    private static ReviewDocument ApplyRefreshedResolution(
        ReviewDocument document,
        ClientResolutionResult resolution)
    {
        var resolvedCandidate = resolution.ClientId is { } resolvedClientId
            ? new ClientResolutionCandidate(
                resolvedClientId,
                resolution.EstablishmentId,
                resolution.ClientDisplayName ?? "Cliente resolvido",
                resolution.ClientTaxIdMasked ?? "***",
                resolution.Method,
                resolution.Confidence)
            : null;
        if (document.ClientId is { } previousClientId &&
            resolvedCandidate is not null &&
            (resolvedCandidate.ClientId != previousClientId ||
                resolvedCandidate.EstablishmentId != document.EstablishmentId))
        {
            return document with
            {
                ClientId = null,
                EstablishmentId = null,
                ClientDisplayName = null,
                ClientTaxIdMasked = null,
                ResolutionMethod = ClientResolutionMethod.None,
                ResolutionConfidence = 0m,
                ClientAlternatives = [resolvedCandidate, .. resolution.Alternatives],
                ResolutionBlockers = [.. resolution.Blockers, "client.assignment_changed"],
            };
        }

        if (document.ResolutionMethod == ClientResolutionMethod.ManualOverride &&
            document.ClientId is { } manuallySelectedClientId)
        {
            var verified = resolvedCandidate is not null &&
                resolvedCandidate.ClientId == manuallySelectedClientId &&
                resolvedCandidate.EstablishmentId == document.EstablishmentId
                    ? resolvedCandidate
                    : resolution.Alternatives.FirstOrDefault(item =>
                        item.ClientId == manuallySelectedClientId &&
                        item.EstablishmentId == document.EstablishmentId);
            if (verified is not null)
            {
                var hardBlockers = resolution.Blockers
                    .Where(code => !IsManualOverrideResolutionBlocker(code))
                    .Distinct(StringComparer.Ordinal)
                    .ToArray();
                return document with
                {
                    ClientDisplayName = verified.DisplayName,
                    ClientTaxIdMasked = verified.TaxIdMasked,
                    ResolutionMethod = ClientResolutionMethod.ManualOverride,
                    ResolutionConfidence = verified.Confidence,
                    ClientAlternatives = resolution.Alternatives,
                    ResolutionBlockers = hardBlockers,
                };
            }
        }

        return document with
        {
            ClientId = resolution.ClientId,
            EstablishmentId = resolution.EstablishmentId,
            ClientDisplayName = resolution.ClientDisplayName,
            ClientTaxIdMasked = resolution.ClientTaxIdMasked,
            ResolutionMethod = resolution.Method,
            ResolutionConfidence = resolution.Confidence,
            ClientAlternatives = resolution.Alternatives,
            ResolutionBlockers = resolution.Blockers,
        };
    }

    private static bool IsManualOverrideResolutionBlocker(string code) =>
        code is "client.not_resolved" or "client.cnpj_root_ambiguous";

    private DocumentReviewWorkspace AttachEligibleDocuments(
        DocumentReviewWorkspace workspace,
        DocumentReviewContext context)
    {
        var documents = workspace.Documents.ToList();
        var groups = workspace.Groups.ToList();
        var audits = workspace.AuditEvents.ToList();
        for (var documentIndex = 0; documentIndex < documents.Count; documentIndex++)
        {
            var document = documents[documentIndex];
            if (document.State != ReviewDocumentState.Ready ||
                document.GroupId.HasValue ||
                !document.ClientId.HasValue ||
                document.Period.Kind == DocumentPeriodKind.Unknown ||
                !options.Profiles.TryGetValue(document.DocumentType, out var profile))
            {
                continue;
            }

            var baseKey = BuildGroupingKey(document, profile);
            var key = profile.GroupIndividually ? $"{baseKey}|document:{document.Id:N}" : baseKey;
            var groupIndex = groups.FindIndex(group =>
                string.Equals(group.GroupingKey, key, StringComparison.Ordinal));
            Guid groupId;
            if (groupIndex < 0)
            {
                groupId = Guid.NewGuid();
                groups.Add(new DocumentDispatchGroup(
                    groupId,
                    key,
                    profile.GroupingPolicyCode,
                    profile.Version,
                    document.ClientId.Value,
                    profile.IncludeEstablishmentInGrouping ? document.EstablishmentId : null,
                    document.ClientDisplayName ?? "Cliente resolvido",
                    document.Period.GroupingPeriodKey,
                    document.Period.DisplayLabel,
                    ReviewGroupState.ReadyForReview,
                    1,
                    [document.Id],
                    [],
                    null,
                    clock.UtcNow,
                    clock.UtcNow));
            }
            else
            {
                var group = groups[groupIndex];
                groupId = group.Id;
                InvalidateApproval(group, groups, groupIndex, audits, context, "Novo documento adicionado ao grupo.");
                group = groups[groupIndex];
                groups[groupIndex] = group with
                {
                    ClientId = document.ClientId.Value,
                    EstablishmentId = profile.IncludeEstablishmentInGrouping
                        ? document.EstablishmentId
                        : null,
                    ClientDisplayName = document.ClientDisplayName ?? "Cliente resolvido",
                    PeriodKey = document.Period.GroupingPeriodKey,
                    PeriodLabel = document.Period.DisplayLabel,
                    DocumentIds = group.DocumentIds.Append(document.Id).Distinct().ToArray(),
                    Revision = group.Revision + 1,
                    UpdatedAtUtc = clock.UtcNow,
                };
            }

            documents[documentIndex] = document with
            {
                GroupId = groupId,
                State = ReviewDocumentState.Grouped,
            };
            audits.Add(Audit(
                context,
                "document.grouped",
                document.Id,
                groupId,
                null,
                profile.GroupingPolicyCode,
                null));
        }

        return workspace with
        {
            Documents = documents,
            Groups = groups,
            AuditEvents = audits,
        };
    }

    private DocumentReviewWorkspace InvalidateChangedApprovalSnapshots(
        DocumentReviewWorkspace workspace,
        DocumentReviewContext context)
    {
        var groups = workspace.Groups.ToList();
        var audits = workspace.AuditEvents.ToList();
        for (var index = 0; index < groups.Count; index++)
        {
            var group = groups[index];
            if (group.ApprovalSnapshot is null)
            {
                continue;
            }

            var currentHash = BuildApprovalContentHash(group, workspace.Documents);
            if (!string.Equals(
                    currentHash,
                    group.ApprovalSnapshot.ContentHash,
                    StringComparison.Ordinal) ||
                group.ApprovalSnapshot.GroupRevision != group.Revision)
            {
                InvalidateApproval(
                    group,
                    groups,
                    index,
                    audits,
                    context,
                    "Conteúdo, validação ou composição mudou após a aprovação.");
            }
        }

        return workspace with { Groups = groups, AuditEvents = audits };
    }

    private DocumentReviewWorkspace PruneEmptyGroups(
        DocumentReviewWorkspace workspace,
        DocumentReviewContext context,
        string reason)
    {
        var emptyGroupIds = workspace.Groups
            .Where(group => !workspace.Documents.Any(document =>
                document.GroupId == group.Id || group.DocumentIds.Contains(document.Id)))
            .Select(group => group.Id)
            .ToArray();
        if (emptyGroupIds.Length == 0)
        {
            return workspace;
        }

        var groups = workspace.Groups.ToList();
        var audits = workspace.AuditEvents.ToList();
        foreach (var groupId in emptyGroupIds)
        {
            var index = groups.FindIndex(group => group.Id == groupId);
            if (index < 0)
            {
                continue;
            }

            var group = groups[index];
            InvalidateApproval(group, groups, index, audits, context, reason);
            group = groups[index];
            audits.Add(Audit(
                context,
                "group.empty_removed",
                null,
                group.Id,
                $"state:{group.State};revision:{group.Revision};documents:{group.DocumentIds.Count}",
                null,
                reason));
            groups.RemoveAt(index);
        }

        return workspace with { Groups = groups, AuditEvents = audits };
    }

    private static DocumentReviewWorkspace RecalculateGroups(DocumentReviewWorkspace workspace)
    {
        var documents = workspace.Documents.ToList();
        var groups = workspace.Groups.Select(group =>
        {
            var members = documents.Where(document => group.DocumentIds.Contains(document.Id)).ToArray();
            var findings = BuildGroupFindings(group, members);
            var state = group.ApprovalSnapshot is not null && findings.All(finding => !finding.PreventsApproval)
                ? ReviewGroupState.Approved
                : members.Length == 0
                    ? ReviewGroupState.Building
                    : findings.Any(finding => finding.PreventsApproval) ||
                        members.Any(document => document.PreventsApproval)
                        ? ReviewGroupState.Blocked
                        : ReviewGroupState.ReadyForReview;
            return group with { Findings = findings, State = state };
        }).ToArray();
        var approvedIds = groups.Where(group => group.IsApproved).SelectMany(group => group.DocumentIds).ToHashSet();
        for (var index = 0; index < documents.Count; index++)
        {
            var document = documents[index];
            if (document.State is ReviewDocumentState.Blocked or ReviewDocumentState.Duplicate)
            {
                continue;
            }

            documents[index] = document with
            {
                State = approvedIds.Contains(document.Id)
                    ? ReviewDocumentState.Approved
                    : document.GroupId.HasValue
                        ? ReviewDocumentState.Grouped
                        : ReviewDocumentState.Ready,
            };
        }

        return workspace with { Documents = documents, Groups = groups };
    }

    private DocumentReviewWorkspace ApproveGroup(
        DocumentReviewWorkspace workspace,
        Guid groupId,
        DocumentReviewContext context)
    {
        var group = GetApprovableGroup(workspace, groupId);
        var groups = workspace.Groups.ToList();
        var groupIndex = groups.FindIndex(item => item.Id == groupId);
        var members = workspace.Documents.Where(document => group.DocumentIds.Contains(document.Id)).ToArray();
        var snapshotDocuments = members.OrderBy(document => document.Id).Select(document =>
            new ApprovalDocumentSnapshot(
                document.Id,
                document.Sha256,
                document.Revision,
                document.ClientId!.Value,
                document.EstablishmentId,
                document.Period.CanonicalKey,
                document.SemanticDuplicateKey)).ToArray();
        var snapshot = new GroupApprovalSnapshot(
            Guid.NewGuid(),
            group.Id,
            group.Revision,
            BuildApprovalContentHash(group, workspace.Documents),
            context.ActorId,
            clock.UtcNow,
            snapshotDocuments);
        groups[groupIndex] = group with
        {
            State = ReviewGroupState.Approved,
            ApprovalSnapshot = snapshot,
            UpdatedAtUtc = clock.UtcNow,
        };
        var audits = workspace.AuditEvents.Append(Audit(
            context,
            "group.approved",
            null,
            group.Id,
            null,
            snapshot.ContentHash,
            "Conteúdo e agrupamento registrados; esta aprovação não envia e-mail."))
            .ToArray();
        return RecalculateGroups(workspace with { Groups = groups, AuditEvents = audits });
    }

    private static DocumentDispatchGroup GetApprovableGroup(
        DocumentReviewWorkspace workspace,
        Guid groupId)
    {
        var group = workspace.Groups.FirstOrDefault(item => item.Id == groupId) ??
            throw new DocumentReviewException("group.not_found", "O grupo selecionado não existe.");
        if (!IsGroupApprovable(workspace, group))
        {
            throw new DocumentReviewException(
                "group.approval_blocked",
                "O grupo contém erro, bloqueio ou duplicidade e não pode ser aprovado.");
        }

        return group;
    }

    private static bool IsGroupApprovable(
        DocumentReviewWorkspace workspace,
        DocumentDispatchGroup group)
    {
        var members = workspace.Documents
            .Where(document => group.DocumentIds.Contains(document.Id))
            .ToArray();
        return group.State == ReviewGroupState.ReadyForReview &&
            !group.PreventsApproval &&
            members.Length > 0 &&
            members.All(document =>
                !document.PreventsApproval &&
                document.State != ReviewDocumentState.Duplicate);
    }

    private static string ApprovalCompetenceKey(
        DocumentDispatchGroup group,
        DocumentReviewWorkspace workspace)
    {
        var keys = workspace.Documents
            .Where(document => group.DocumentIds.Contains(document.Id))
            .Select(document =>
            {
                var year = document.Period.Year ?? document.Period.StartDate?.Year;
                var month = document.Period.Month ?? document.Period.StartDate?.Month;
                return year.HasValue && month.HasValue
                    ? $"month:{year.Value:0000}-{month.Value:00}"
                    : document.Period.CanonicalKey;
            })
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        return keys.Length == 1 ? keys[0] : group.PeriodKey;
    }

    private static List<ValidationFinding> BuildGroupFindings(
        DocumentDispatchGroup group,
        IReadOnlyList<ReviewDocument> members)
    {
        var now = group.UpdatedAtUtc;
        var findings = new List<ValidationFinding>();
        if (members.Select(document => document.ClientId).Distinct().Count() > 1 ||
            members.Any(document => document.ClientId != group.ClientId))
        {
            findings.Add(new ValidationFinding(
                Guid.NewGuid(),
                "group.client_mismatch",
                ValidationSeverity.Blocker,
                "O grupo contém documentos de clientes diferentes.",
                "ClientId",
                false,
                FindingResolutionType.None,
                null,
                null,
                now,
                null));
        }

        if (members.Select(document => document.Period.GroupingPeriodKey)
            .Distinct(StringComparer.Ordinal).Count() > 1)
        {
            findings.Add(new ValidationFinding(
                Guid.NewGuid(),
                "group.period_mismatch",
                ValidationSeverity.Blocker,
                "O grupo contém períodos contábeis incompatíveis.",
                "Period",
                false,
                FindingResolutionType.None,
                null,
                null,
                now,
                null));
        }

        return findings;
    }

    private static void ApplyDuplicates(List<ReviewDocument> documents, DateTimeOffset now)
    {
        var exact = new Dictionary<string, Guid>(StringComparer.OrdinalIgnoreCase);
        var semantic = new Dictionary<string, Guid>(StringComparer.Ordinal);
        foreach (var document in documents.OrderBy(item => item.ImportedAtUtc).ThenBy(item => item.Id).ToArray())
        {
            var index = documents.FindIndex(item => item.Id == document.Id);
            var findings = documents[index].Findings.ToList();
            if (exact.TryGetValue(document.Sha256, out var originalId))
            {
                findings.Add(DuplicateFinding(
                    "duplicate.exact_sha256",
                    "O mesmo conteúdo já foi importado neste espaço; nenhum novo grupo será criado.",
                    originalId,
                    now));
                documents[index] = documents[index] with
                {
                    Findings = PreserveFindingHistory(document.Findings, findings),
                    State = ReviewDocumentState.Duplicate,
                    GroupId = null,
                };
                continue;
            }

            exact.Add(document.Sha256, document.Id);
            if (document.SemanticDuplicateKey.Length > 0 &&
                semantic.TryGetValue(document.SemanticDuplicateKey, out originalId))
            {
                findings.Add(DuplicateFinding(
                    "duplicate.semantic_key",
                    "Outro PDF representa a mesma obrigação contábil configurada; revise o original.",
                    originalId,
                    now));
                documents[index] = documents[index] with
                {
                    Findings = PreserveFindingHistory(document.Findings, findings),
                    State = ReviewDocumentState.Duplicate,
                    GroupId = null,
                };
                continue;
            }

            if (document.SemanticDuplicateKey.Length > 0)
            {
                semantic.Add(document.SemanticDuplicateKey, document.Id);
            }
        }
    }

    private static ValidationFinding DuplicateFinding(
        string code,
        string message,
        Guid originalId,
        DateTimeOffset now) => new(
            Guid.NewGuid(),
            code,
            ValidationSeverity.Blocker,
            $"{message} Referência local: {originalId:N}.",
            "SHA256",
            false,
            FindingResolutionType.None,
            null,
            null,
            now,
            null);

    private static string BuildSemanticDuplicateKey(
        ReviewDocument document,
        DocumentReviewProfile? profile)
    {
        if (!document.ClientId.HasValue ||
            document.Period.Kind == DocumentPeriodKind.Unknown ||
            profile is null)
        {
            return string.Empty;
        }

        var identity = profile.SemanticIdentityRoles.Select(role =>
            document.Fields.FirstOrDefault(field => field.Role == role)?.Value.Trim().ToUpperInvariant() ?? "-");
        return string.Join('|',
            new[]
            {
                document.ClientId.Value.ToString("N"),
                document.DocumentType.ToString(),
                document.Period.CanonicalKey,
            }.Concat(identity));
    }

    private static string BuildGroupingKey(ReviewDocument document, DocumentReviewProfile profile)
    {
        var establishment = profile.IncludeEstablishmentInGrouping
            ? document.EstablishmentId?.ToString("N") ?? "client-root"
            : "client-root";
        return string.Join('|',
            document.ClientId!.Value.ToString("N"),
            establishment,
            document.Period.GroupingPeriodKey,
            profile.GroupingPolicyCode,
            profile.Version);
    }

    private static string BuildApprovalContentHash(
        DocumentDispatchGroup group,
        IReadOnlyList<ReviewDocument> documents)
    {
        var builder = new StringBuilder()
            .Append(group.Id.ToString("N")).Append('|')
            .Append(group.Revision).Append('|')
            .Append(group.ClientId.ToString("N")).Append('|')
            .Append(group.EstablishmentId?.ToString("N") ?? "-").Append('|')
            .Append(group.PeriodKey).Append('|')
            .Append(group.GroupingPolicyCode).Append('|')
            .Append(group.GroupingPolicyVersion);
        foreach (var document in documents.Where(document => group.DocumentIds.Contains(document.Id))
            .OrderBy(document => document.Id))
        {
            builder.Append('\n')
                .Append(document.Id.ToString("N")).Append('|')
                .Append(document.Sha256).Append('|')
                .Append(document.Revision).Append('|')
                .Append(document.ClientId?.ToString("N") ?? "-").Append('|')
                .Append(document.EstablishmentId?.ToString("N") ?? "-").Append('|')
                .Append(document.Period.CanonicalKey).Append('|')
                .Append(document.SemanticDuplicateKey);
        }

        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(builder.ToString())));
    }

    private static ValidationFinding[] PreserveFindingHistory(
        IReadOnlyList<ValidationFinding> previous,
        IReadOnlyList<ValidationFinding> current) => current.Select(finding =>
    {
        var existing = previous.FirstOrDefault(item =>
            string.Equals(item.RuleCode, finding.RuleCode, StringComparison.Ordinal) &&
            item.Severity == finding.Severity &&
            string.Equals(item.Message, finding.Message, StringComparison.Ordinal) &&
            string.Equals(item.FieldKey, finding.FieldKey, StringComparison.Ordinal));
        return existing is null
            ? finding
            : finding with
            {
                Id = existing.Id,
                IsResolved = existing.IsResolved,
                ResolutionType = existing.ResolutionType,
                ResolvedBy = existing.ResolvedBy,
                ResolutionNote = existing.ResolutionNote,
                CreatedAtUtc = existing.CreatedAtUtc,
                ResolvedAtUtc = existing.ResolvedAtUtc,
            };
    }).OrderByDescending(finding => finding.Severity).ThenBy(finding => finding.RuleCode).ToArray();

    private static bool ReviewChanged(ReviewDocument previous, ReviewDocument current) =>
        IsIneligible(previous.State) != IsIneligible(current.State) ||
        previous.ClientId != current.ClientId ||
        previous.EstablishmentId != current.EstablishmentId ||
        !string.Equals(previous.ClientDisplayName, current.ClientDisplayName, StringComparison.Ordinal) ||
        !string.Equals(previous.ClientTaxIdMasked, current.ClientTaxIdMasked, StringComparison.Ordinal) ||
        previous.ResolutionMethod != current.ResolutionMethod ||
        previous.ResolutionConfidence != current.ResolutionConfidence ||
        !previous.ResolutionBlockers.SequenceEqual(current.ResolutionBlockers, StringComparer.Ordinal) ||
        previous.Period != current.Period ||
        !string.Equals(previous.SemanticDuplicateKey, current.SemanticDuplicateKey, StringComparison.Ordinal) ||
        !FindingSetsEqual(previous.Findings, current.Findings);

    private static bool IsIneligible(ReviewDocumentState state) =>
        state is ReviewDocumentState.Blocked or ReviewDocumentState.Duplicate;

    private static bool FindingSetsEqual(
        IReadOnlyList<ValidationFinding> left,
        IReadOnlyList<ValidationFinding> right) => left.Count == right.Count &&
        left.Zip(right).All(pair =>
            pair.First.RuleCode == pair.Second.RuleCode &&
            pair.First.Severity == pair.Second.Severity &&
            pair.First.Message == pair.Second.Message &&
            pair.First.FieldKey == pair.Second.FieldKey &&
            pair.First.IsResolved == pair.Second.IsResolved);

    private void DetachDocumentsWithChangedGroupingIdentity(
        IReadOnlyList<ReviewDocument> previousDocuments,
        List<ReviewDocument> currentDocuments,
        List<DocumentDispatchGroup> groups,
        List<ReviewAuditEvent> audits,
        DocumentReviewContext context)
    {
        foreach (var previous in previousDocuments.Where(document => document.GroupId.HasValue))
        {
            var current = currentDocuments.Single(document => document.Id == previous.Id);
            if (previous.ClientId == current.ClientId &&
                previous.EstablishmentId == current.EstablishmentId &&
                string.Equals(previous.ClientDisplayName, current.ClientDisplayName, StringComparison.Ordinal) &&
                string.Equals(previous.Period.GroupingPeriodKey, current.Period.GroupingPeriodKey, StringComparison.Ordinal) &&
                string.Equals(previous.SemanticDuplicateKey, current.SemanticDuplicateKey, StringComparison.Ordinal))
            {
                continue;
            }

            DetachDocumentFromGroup(
                currentDocuments,
                groups,
                audits,
                previous.Id,
                previous.GroupId!.Value,
                context,
                "Cliente, competência ou identidade contábil alterada após revalidação.");
        }
    }

    private void DetachIneligibleDocuments(
        List<ReviewDocument> documents,
        List<DocumentDispatchGroup> groups,
        List<ReviewAuditEvent> audits,
        DocumentReviewContext context)
    {
        foreach (var document in documents.Where(item =>
            item.GroupId.HasValue &&
            item.State is ReviewDocumentState.Blocked or ReviewDocumentState.Duplicate).ToArray())
        {
            DetachDocumentFromGroup(
                documents,
                groups,
                audits,
                document.Id,
                document.GroupId!.Value,
                context,
                "Documento deixou de ser elegível após revalidação.");
        }
    }

    private void DetachDocumentFromGroup(
        List<ReviewDocument> documents,
        List<DocumentDispatchGroup> groups,
        List<ReviewAuditEvent> audits,
        Guid documentId,
        Guid groupId,
        DocumentReviewContext context,
        string reason)
    {
        var groupIndex = groups.FindIndex(group => group.Id == groupId);
        if (groupIndex >= 0)
        {
            var group = groups[groupIndex];
            InvalidateApproval(group, groups, groupIndex, audits, context, reason);
            group = groups[groupIndex];
            groups[groupIndex] = group with
            {
                DocumentIds = group.DocumentIds.Where(id => id != documentId).ToArray(),
                Revision = group.Revision + 1,
                UpdatedAtUtc = clock.UtcNow,
            };
        }

        var documentIndex = documents.FindIndex(document => document.Id == documentId);
        if (documentIndex >= 0)
        {
            documents[documentIndex] = documents[documentIndex] with { GroupId = null };
        }
    }

    private void InvalidateApproval(
        DocumentDispatchGroup group,
        List<DocumentDispatchGroup> groups,
        int groupIndex,
        List<ReviewAuditEvent> audits,
        DocumentReviewContext context,
        string reason)
    {
        if (group.ApprovalSnapshot is null)
        {
            return;
        }

        groups[groupIndex] = group with
        {
            ApprovalSnapshot = null,
            State = ReviewGroupState.ReadyForReview,
        };
        audits.Add(Audit(
            context,
            "group.approval_invalidated",
            null,
            group.Id,
            group.ApprovalSnapshot.ContentHash,
            null,
            reason));
    }

    private ReviewAuditEvent Audit(
        DocumentReviewContext context,
        string action,
        Guid? documentId,
        Guid? groupId,
        string? previous,
        string? current,
        string? reason) => new(
            Guid.NewGuid(),
            context.ScopeKey,
            context.ActorId,
            clock.UtcNow,
            action,
            documentId,
            groupId,
            previous,
            current,
            reason,
            Guid.NewGuid().ToString("N"));

    private static string SanitizeAuditMetadataValue(string? value) =>
        string.IsNullOrWhiteSpace(value)
            ? "Cliente não identificado"
            : value.Trim()
                .Replace(';', ',')
                .Replace('\r', ' ')
                .Replace('\n', ' ');

    private void ValidateReason(string reason, string code)
    {
        if (string.IsNullOrWhiteSpace(reason) || reason.Trim().Length < options.MinimumOverrideReasonLength)
        {
            throw new DocumentReviewException(
                code,
                $"Informe uma justificativa com ao menos {options.MinimumOverrideReasonLength} caracteres.");
        }
    }

    private static void ValidateCorrectedPeriod(DocumentPeriod period)
    {
        var isValid = period.Kind switch
        {
            DocumentPeriodKind.Monthly =>
                period.Month is >= 1 and <= 12 && period.Year is >= 1900 and <= 9999,
            DocumentPeriodKind.Annual =>
                period.Month is null && period.Year is >= 1900 and <= 9999,
            DocumentPeriodKind.DateRange =>
                period.StartDate.HasValue &&
                period.EndDate.HasValue &&
                period.EndDate.Value >= period.StartDate.Value,
            DocumentPeriodKind.EventDate => period.StartDate.HasValue,
            DocumentPeriodKind.AssessmentPeriod =>
                period.StartDate.HasValue &&
                (!period.EndDate.HasValue || period.EndDate.Value >= period.StartDate.Value),
            _ => false,
        };
        if (!isValid)
        {
            throw new DocumentReviewException(
                "period.correction_invalid",
                "Informe uma competência ou período contábil válido para corrigir o documento.");
        }
    }

    private static bool PeriodsEquivalent(DocumentPeriod left, DocumentPeriod right) =>
        left.Kind == right.Kind &&
        left.Month == right.Month &&
        left.Year == right.Year &&
        left.StartDate == right.StartDate &&
        left.EndDate == right.EndDate &&
        left.DueDate == right.DueDate;

    private async Task<DocumentReviewWorkspace> ExecuteAsync(
        Func<DocumentReviewWorkspace, DocumentReviewContext, CancellationToken,
            Task<(DocumentReviewWorkspace Workspace, bool Save)>> operation,
        CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            var context = await contextAccessor.GetCurrentAsync(cancellationToken);
            if (string.IsNullOrWhiteSpace(context.ScopeKey) || string.IsNullOrWhiteSpace(context.ActorId))
            {
                throw new DocumentReviewException(
                    "review.context_invalid",
                    "O contexto autenticado de revisão não está disponível.");
            }

            var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
            var (updated, save) = await operation(workspace, context, cancellationToken);
            if (!string.Equals(updated.ScopeKey, context.ScopeKey, StringComparison.Ordinal))
            {
                throw new DocumentReviewException(
                    "review.scope_mismatch",
                    "O espaço de revisão não pertence ao contexto autenticado atual.");
            }

            if (save)
            {
                await store.SaveAsync(updated, cancellationToken);
            }

            return updated;
        }
        finally
        {
            gate.Release();
        }
    }
}
