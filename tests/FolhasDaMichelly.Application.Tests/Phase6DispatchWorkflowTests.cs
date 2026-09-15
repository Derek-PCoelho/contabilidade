using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Application.Tests;

public sealed class Phase6DispatchWorkflowTests : IDisposable
{
    private readonly string attachmentPath = Path.Combine(
        Path.GetTempPath(),
        $"folhas-phase6-{Guid.NewGuid():N}.pdf");
    private readonly FixedClock clock = new();

    public Phase6DispatchWorkflowTests()
    {
        File.WriteAllText(attachmentPath, "PDF sintético da fase seis.");
    }

    [Theory]
    [InlineData(DispatchOperationMode.Test, DispatchItemState.AcceptedByProvider)]
    [InlineData(DispatchOperationMode.Draft, DispatchItemState.DraftCreated)]
    [InlineData(DispatchOperationMode.Send, DispatchItemState.AcceptedByProvider)]
    public async Task TestDraftAndSendCompleteOnlyThroughFakeProvider(
        DispatchOperationMode mode,
        DispatchItemState expectedState)
    {
        var fixture = CreateFixture();
        var item = await PrepareAndApproveAsync(fixture, mode);

        var workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(
                item.Id,
                mode == DispatchOperationMode.Send ? "CONFIRMAR 1" : null),
            CancellationToken.None);

        var executed = workspace.Items.Single(current => current.Id == item.Id);
        Assert.Equal(expectedState, executed.State);
        Assert.Single(workspace.Attempts);
        Assert.Equal(DispatchWorkflowOptions.FakeProviderKey, workspace.Attempts[0].ProviderKey);
        Assert.Equal(mode == DispatchOperationMode.Draft ? 0 : 1, fixture.Provider.SendCallCount);
        Assert.Equal(mode == DispatchOperationMode.Draft ? 1 : 0, fixture.Provider.DraftCallCount);
        if (mode == DispatchOperationMode.Test)
        {
            Assert.Equal(["auditoria@example.invalid"], executed.Message!.EffectiveTo);
            Assert.Equal("cliente@example.invalid", Assert.Single(executed.Message.OriginalTo).Email);
            Assert.StartsWith("[TESTE — NÃO ENVIAR AO CLIENTE]", executed.Message.Subject, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task UnsafeTestDestinationIsBlockedBeforeApproval()
    {
        var fixture = CreateFixture();
        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "pessoa@dominio-real.test",
                FakeDeliveryScenario.Success),
            CancellationToken.None);

        var item = Assert.Single(workspace.Items);
        Assert.Equal(DispatchItemState.Blocked, item.State);
        Assert.Contains(item.Blocks, block => block.Code == "TEST_DESTINATION_INVALID");
        Assert.Empty(workspace.Attempts);
    }

    [Fact]
    public async Task AmbiguousResultNeverRetriesBlindlyAndUsesReconciliation()
    {
        var fixture = CreateFixture();
        fixture.Provider.NextSendResult = new EmailProviderResult(
            DeliveryAttemptState.Ambiguous,
            null,
            null,
            "FAKE_AMBIGUOUS_RESULT",
            "Resultado sintético ambíguo.");
        var item = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Test);
        var workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(item.Id, null),
            CancellationToken.None);
        Assert.Equal(DispatchItemState.Ambiguous, workspace.Items.Single(current => current.Id == item.Id).State);

        var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExecuteAsync(new ExecuteDispatchRequest(item.Id, null), CancellationToken.None));
        Assert.Equal("DISPATCH_RECONCILIATION_REQUIRED", exception.Code);
        Assert.Equal(1, fixture.Provider.SendCallCount);

        fixture.Options.ProviderKey = DispatchWorkflowOptions.MicrosoftGraphProviderKey;
        var providerMismatch = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ReconcileAsync(item.Id, CancellationToken.None));
        Assert.Equal("EMAIL_PROVIDER_RECOVERY_MISMATCH", providerMismatch.Code);
        fixture.Options.ProviderKey = DispatchWorkflowOptions.FakeProviderKey;

        fixture.Provider.NextReconciliationResult = new EmailProviderResult(
            DeliveryAttemptState.AcceptedByProvider,
            "fake-message-reconciled",
            null,
            null,
            null);
        workspace = await fixture.Service.ReconcileAsync(item.Id, CancellationToken.None);

        Assert.Equal(DispatchItemState.Reconciled, workspace.Items.Single(current => current.Id == item.Id).State);
        Assert.Equal(1, fixture.Provider.SendCallCount);
        Assert.Equal(1, fixture.Provider.ReconcileCallCount);
    }

    [Fact]
    public async Task ReconciledUnsentDraftRequiresASecondExplicitSendConfirmation()
    {
        var fixture = CreateFixture();
        fixture.Provider.NextSendResult = new EmailProviderResult(
            DeliveryAttemptState.Ambiguous,
            null,
            "fake-draft-staged",
            "SEND_AMBIGUOUS",
            "Resultado sintético ambíguo.");
        var item = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Send);
        var workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(item.Id, "CONFIRMAR 1"),
            CancellationToken.None);
        Assert.Equal(DispatchItemState.Ambiguous, workspace.Items.Single(current => current.Id == item.Id).State);

        fixture.Provider.NextReconciliationResult = new EmailProviderResult(
            DeliveryAttemptState.DraftCreated,
            null,
            "fake-draft-staged",
            "GRAPH_DRAFT_CONFIRMED_NOT_SENT",
            "Rascunho confirmado.");
        workspace = await fixture.Service.ReconcileAsync(item.Id, CancellationToken.None);
        Assert.Equal(DispatchItemState.DraftCreated, workspace.Items.Single(current => current.Id == item.Id).State);

        fixture.Provider.NextSendResult = new EmailProviderResult(
            DeliveryAttemptState.AcceptedByProvider,
            "fake-message-resumed",
            "fake-draft-staged",
            null,
            null);
        workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(item.Id, "CONFIRMAR 1"),
            CancellationToken.None);

        Assert.Equal(DispatchItemState.AcceptedByProvider, workspace.Items.Single(current => current.Id == item.Id).State);
        Assert.Equal(2, fixture.Provider.SendCallCount);
        Assert.Equal(2, workspace.Attempts.Count);
        Assert.Equal(2, workspace.Attempts.Select(attempt => attempt.IdempotencyKey).Distinct(StringComparer.Ordinal).Count());
    }

    [Fact]
    public async Task KillSwitchAndMinimumVersionBlockSendButNotTest()
    {
        var fixture = CreateFixture(options =>
        {
            options.EmailSendEnabled = false;
            options.MinimumSendVersion = "99.0.0";
        });
        var send = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Send);
        var killSwitch = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExecuteAsync(
                new ExecuteDispatchRequest(send.Id, "CONFIRMAR 1"),
                CancellationToken.None));
        Assert.Equal("EMAIL_SEND_DISABLED", killSwitch.Code);
        fixture.Options.EmailSendEnabled = true;
        var minimumVersion = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExecuteAsync(
                new ExecuteDispatchRequest(send.Id, "CONFIRMAR 1"),
                CancellationToken.None));
        Assert.Equal("APP_VERSION_BELOW_MINIMUM", minimumVersion.Code);

        var test = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Test);
        var completed = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(test.Id, null),
            CancellationToken.None);

        Assert.Equal(DispatchItemState.AcceptedByProvider, completed.Items.Single(item => item.Id == test.Id).State);
    }

    [Fact]
    public async Task Phase11PilotBlocksSendBeforeCompositionButKeepsTestAndDraftAvailable()
    {
        var fixture = CreateFixture(options =>
        {
            options.PilotModeEnabled = true;
            options.PilotAllowTest = true;
            options.PilotAllowDraft = true;
            options.PilotAllowSend = false;
        });

        var blocked = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.PrepareAsync(
                new PrepareDispatchRequest(
                    [fixture.GroupId],
                    ProcessingSelectionMode.Individual,
                    DispatchOperationMode.Send,
                    null,
                    FakeDeliveryScenario.Success),
                CancellationToken.None));

        Assert.Equal("PILOT_SEND_DISABLED", blocked.Code);
        Assert.Empty(fixture.Store.Workspace.Items);
        var test = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        Assert.Single(test.Items);

        var draft = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Draft,
                null,
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        Assert.Contains(draft.Items, item => item.Mode == DispatchOperationMode.Draft);
    }

    [Fact]
    public async Task Phase12BlocksExternalSendBeforeCompositionWhenProductionIsNotReady()
    {
        var fixture = CreateFixture(options =>
        {
            options.ProviderKey = DispatchWorkflowOptions.MicrosoftGraphProviderKey;
            options.MicrosoftGraphEnabled = true;
            options.MicrosoftGraphSendEnabled = true;
            options.MicrosoftGraphControlledRecipient = "controlado@example.invalid";
            options.ProductionRolloutReady = false;
        });

        var blocked = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.PrepareAsync(
                new PrepareDispatchRequest(
                    [fixture.GroupId],
                    ProcessingSelectionMode.Individual,
                    DispatchOperationMode.Send,
                    null,
                    FakeDeliveryScenario.Success),
                CancellationToken.None));

        Assert.Equal("PRODUCTION_ROLLOUT_CLOSED", blocked.Code);
        Assert.Empty(fixture.Store.Workspace.Items);
        Assert.Equal(0, fixture.RemoteGuard.CallCount);
        Assert.Equal(0, fixture.Provider.SendCallCount);
    }

    [Fact]
    public async Task TemplateChangeInvalidatesApprovalOnReloadBeforeProviderCall()
    {
        var fixture = CreateFixture();
        var item = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Test);
        fixture.Catalog.Template = fixture.Catalog.Template with
        {
            Version = fixture.Catalog.Template.Version + 1,
            BodyTemplate = "Conteúdo atualizado: {{documentos.lista}}",
        };

        var reloaded = await fixture.Service.LoadAsync(CancellationToken.None);

        var stored = Assert.Single(reloaded.Items, current => current.Id == item.Id);
        Assert.Null(stored.Approval);
        Assert.Equal(DispatchItemState.ReadyForApproval, stored.State);
        Assert.Equal(0, fixture.Provider.SendCallCount);
    }

    [Fact]
    public async Task UnknownTemplatePlaceholderIsAnApprovalBlocker()
    {
        var fixture = CreateFixture();
        fixture.Catalog.Template = fixture.Catalog.Template with
        {
            BodyTemplate = "Olá {{cliente.nome_inexistente}}",
        };

        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);

        var item = Assert.Single(workspace.Items);
        Assert.Equal(DispatchItemState.Blocked, item.State);
        Assert.Contains(item.Blocks, block => block.Code == "TEMPLATE_PLACEHOLDER_UNRESOLVED");
    }

    [Fact]
    public async Task EveryPublishedPlaceholderIsRenderedByTheComposer()
    {
        var fixture = CreateFixture();
        fixture.Catalog.Template = fixture.Catalog.Template with
        {
            BodyTemplate = string.Join(
                Environment.NewLine,
                MessageTemplatePlaceholderCatalog.Definitions.Select(definition => definition.Token)),
        };

        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);

        var item = Assert.Single(workspace.Items);
        Assert.Equal(DispatchItemState.ReadyForApproval, item.State);
        Assert.DoesNotContain(item.Blocks, block => block.Code == "TEMPLATE_PLACEHOLDER_UNRESOLVED");
        Assert.DoesNotContain("{{", item.Message!.TextBody, StringComparison.Ordinal);
        Assert.Contains("AL Contadores Associados", item.Message.TextBody, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ComposerUsesPortugueseDocumentNamesAndNeverLeaksTheTechnicalActorIntoTheMessage()
    {
        var fixture = CreateFixture(documentType: RecognizedDocumentType.Vacation);
        fixture.Catalog.Template = fixture.Catalog.Template with
        {
            BodyTemplate = "Documentos:\n{{documentos.lista}}\nResponsável: {{operador.nome}}",
        };

        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);

        var message = Assert.Single(workspace.Items).Message;
        Assert.NotNull(message);
        Assert.Contains("- Férias:", message.TextBody, StringComparison.Ordinal);
        Assert.Contains("Responsável: Equipe responsável", message.TextBody, StringComparison.Ordinal);
        Assert.DoesNotContain("Vacation", message.TextBody, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("synthetic-operator", message.TextBody, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("synthetic-operator", message.HtmlBody, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task FakeProviderAcceptanceIsPresentedOnlyAsALocalSimulationWithoutDeliveryClaim()
    {
        var fixture = CreateFixture();
        var item = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Test);
        var workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(item.Id, null),
            CancellationToken.None);
        var executed = Assert.Single(workspace.Items, current => current.Id == item.Id);
        var attempt = Assert.Single(workspace.Attempts, current => current.DispatchItemId == item.Id);

        var presentation = DispatchOutcomePresenter.Present(executed, attempt);

        Assert.True(presentation.IsSimulation);
        Assert.True(presentation.IsTechnicalSuccess);
        Assert.False(presentation.NeedsAttention);
        Assert.Equal("Simulação local concluída", presentation.OperationResult);
        Assert.Equal("Nenhum e-mail real foi enviado", presentation.DeliveryStatus);
        Assert.DoesNotContain("entregue", presentation.DeliveryStatus, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("aceito pelo serviço", presentation.OperationResult, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task FakeProviderDraftIsPresentedAsACompletedLocalSimulation()
    {
        var fixture = CreateFixture();
        var item = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Draft);
        var workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(item.Id, null),
            CancellationToken.None);
        var executed = Assert.Single(workspace.Items, current => current.Id == item.Id);
        var attempt = Assert.Single(workspace.Attempts, current => current.DispatchItemId == item.Id);

        var presentation = DispatchOutcomePresenter.Present(executed, attempt);

        Assert.Equal(DispatchItemState.DraftCreated, executed.State);
        Assert.Equal(DeliveryAttemptState.DraftCreated, attempt.State);
        Assert.True(presentation.IsSimulation);
        Assert.True(presentation.IsTechnicalSuccess);
        Assert.False(presentation.NeedsAttention);
        Assert.Equal("Simulação de rascunho concluída", presentation.OperationResult);
        Assert.Equal("Nenhum e-mail real foi enviado", presentation.DeliveryStatus);
        Assert.Equal("Rascunho registrado somente neste aplicativo", presentation.Evidence);
        Assert.DoesNotContain("conta conectada", presentation.OperationResult, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task BatchApprovalExcludesBlockedItemsAndExecutionIsSequential()
    {
        var fixture = CreateFixture();
        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Batch,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var batch = Assert.Single(workspace.Batches);
        var eligible = Assert.Single(workspace.Items);
        var blocked = eligible with
        {
            Id = Guid.NewGuid(),
            State = DispatchItemState.Blocked,
            Blocks = [new DispatchBlock("SYNTHETIC_BLOCK", ValidationSeverity.Blocker, "Bloqueio sintético.")],
        };
        workspace = workspace with
        {
            Items = [eligible, blocked],
            Batches = [batch with { DispatchItemIds = [eligible.Id, blocked.Id] }],
        };
        await fixture.Store.SaveAsync(workspace, CancellationToken.None);

        workspace = await fixture.Service.ApproveBatchAsync(batch.Id, CancellationToken.None);
        Assert.Equal(DispatchItemState.Approved, workspace.Items.Single(item => item.Id == eligible.Id).State);
        Assert.Equal(DispatchItemState.Blocked, workspace.Items.Single(item => item.Id == blocked.Id).State);
        workspace = await fixture.Service.ExecuteBatchAsync(batch.Id, null, CancellationToken.None);

        Assert.Equal(
            DispatchItemState.AcceptedByProvider,
            workspace.Items.Single(item => item.Id == eligible.Id).State);
        Assert.Equal(DispatchItemState.Blocked, workspace.Items.Single(item => item.Id == blocked.Id).State);
        Assert.Equal(1, fixture.Provider.SendCallCount);
    }

    [Fact]
    public async Task Phase7GraphCompositionForcesControlledRecipientAndRequiresExplicitGraphConfirmation()
    {
        var fixture = CreateFixture(options =>
        {
            options.ProviderKey = DispatchWorkflowOptions.MicrosoftGraphProviderKey;
            options.MicrosoftGraphEnabled = true;
            options.MicrosoftGraphSendEnabled = true;
            options.MicrosoftGraphControlledRecipient = "controlado@example.invalid";
        });
        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Send,
                "ignorado@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var item = Assert.Single(workspace.Items);
        Assert.Equal(["controlado@example.invalid"], item.Message!.EffectiveTo);
        Assert.Empty(item.Message.EffectiveCc);
        Assert.Equal("cliente@example.invalid", Assert.Single(item.Message.OriginalTo).Email);
        Assert.StartsWith("[DESTINO CONTROLADO]", item.Message.Subject, StringComparison.Ordinal);
        Assert.Equal("microsoft-graph://me", item.Message.SenderAccountId);
        workspace = await fixture.Service.ApproveAsync(item.Id, CancellationToken.None);

        var wrongConfirmation = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExecuteAsync(
                new ExecuteDispatchRequest(item.Id, "CONFIRMAR 1"),
                CancellationToken.None));
        Assert.Equal("SEND_CONFIRMATION_REQUIRED", wrongConfirmation.Code);

        workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(item.Id, "CONFIRMAR GRAPH 1"),
            CancellationToken.None);
        Assert.Equal(DispatchItemState.AcceptedByProvider, workspace.Items.Single(current => current.Id == item.Id).State);
        Assert.Equal(DispatchWorkflowOptions.MicrosoftGraphProviderKey, Assert.Single(workspace.Attempts).ProviderKey);
        Assert.Equal(1, fixture.RemoteGuard.CallCount);
    }

    [Fact]
    public async Task Phase8GmailCompositionForcesControlledRecipientAndRequiresExplicitGmailConfirmation()
    {
        var fixture = CreateFixture(options =>
        {
            options.ProviderKey = DispatchWorkflowOptions.GmailProviderKey;
            options.GmailEnabled = true;
            options.GmailSendEnabled = true;
            options.GmailControlledRecipient = "google-controlado@example.invalid";
        });
        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Send,
                "ignorado@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var item = Assert.Single(workspace.Items);
        Assert.Equal(["google-controlado@example.invalid"], item.Message!.EffectiveTo);
        Assert.Empty(item.Message.EffectiveCc);
        Assert.Equal("cliente@example.invalid", Assert.Single(item.Message.OriginalTo).Email);
        Assert.StartsWith("[DESTINO CONTROLADO]", item.Message.Subject, StringComparison.Ordinal);
        Assert.Equal("google-gmail://me", item.Message.SenderAccountId);
        workspace = await fixture.Service.ApproveAsync(item.Id, CancellationToken.None);

        var wrongConfirmation = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExecuteAsync(
                new ExecuteDispatchRequest(item.Id, "CONFIRMAR GRAPH 1"),
                CancellationToken.None));
        Assert.Equal("SEND_CONFIRMATION_REQUIRED", wrongConfirmation.Code);

        workspace = await fixture.Service.ExecuteAsync(
            new ExecuteDispatchRequest(item.Id, "CONFIRMAR GMAIL 1"),
            CancellationToken.None);
        Assert.Equal(DispatchItemState.AcceptedByProvider, workspace.Items.Single(current => current.Id == item.Id).State);
        Assert.Equal(DispatchWorkflowOptions.GmailProviderKey, Assert.Single(workspace.Attempts).ProviderKey);
        Assert.Equal(1, fixture.RemoteGuard.CallCount);
    }

    [Fact]
    public async Task BatchPreparationRejectsGroupsFromDifferentCompetencesBeforeComposing()
    {
        var fixture = CreateFixture();
        var septemberGroupId = AddApprovedMonthlyGroup(fixture, 9);

        var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.PrepareAsync(
                new PrepareDispatchRequest(
                    [fixture.GroupId, septemberGroupId],
                    ProcessingSelectionMode.Batch,
                    DispatchOperationMode.Test,
                    "auditoria@example.invalid",
                    FakeDeliveryScenario.Success),
                CancellationToken.None));

        Assert.Equal("DISPATCH_PERIOD_MISMATCH", exception.Code);
        Assert.Empty(fixture.Store.Workspace.Items);
        Assert.Empty(fixture.Store.Workspace.Batches);
    }

    [Fact]
    public async Task RecompositionIncrementsRevisionAndReportsOnlyTheCurrentMessage()
    {
        var fixture = CreateFixture();
        var firstWorkspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var first = Assert.Single(firstWorkspace.Items);
        Assert.Equal(1, first.Revision);

        var recomposed = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var current = recomposed.Items.Single(item => item.State != DispatchItemState.Cancelled);
        Assert.Equal(2, current.Revision);
        Assert.Equal(DispatchItemState.Cancelled, recomposed.Items.Single(item => item.Id == first.Id).State);

        var oldAttempt = new DeliveryAttempt(
            Guid.NewGuid(),
            first.BatchId,
            first.Id,
            first.GroupId,
            1,
            first.Mode,
            DeliveryAttemptState.AcceptedByProvider,
            fixture.Options.ProviderKey,
            "old-attempt-key",
            Assert.IsType<RenderedMessageSnapshot>(first.Message).DispatchFingerprint,
            "old-provider-message",
            null,
            null,
            null,
            clock.UtcNow,
            clock.UtcNow);
        var currentAttempt = oldAttempt with
        {
            Id = Guid.NewGuid(),
            BatchId = current.BatchId,
            DispatchItemId = current.Id,
            AttemptNumber = 2,
            IdempotencyKey = "current-attempt-key",
            DispatchFingerprint = Assert.IsType<RenderedMessageSnapshot>(current.Message).DispatchFingerprint,
            ProviderMessageId = "current-provider-message",
        };
        await fixture.Store.SaveAsync(
            recomposed with { Attempts = [oldAttempt, currentAttempt] },
            CancellationToken.None);

        var result = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.AllPeriods,
            CancellationToken.None);

        Assert.Equal(1, result.ItemCount);
        var export = Assert.Single(fixture.ReportExporter.Calls);
        Assert.Equal(current.Id, Assert.Single(export.Dispatch.Items).Id);
        var batch = Assert.Single(export.Dispatch.Batches);
        Assert.Equal([current.Id], batch.DispatchItemIds);
        Assert.Equal([fixture.GroupId], batch.GroupIds);
        Assert.Equal(currentAttempt.Id, Assert.Single(export.Dispatch.Attempts).Id);
        Assert.Equal(
            2,
            export.Dispatch.AuditEvents.Count(audit => audit.Action == "dispatch_composed"));
    }

    [Fact]
    public async Task ClientReportUsesSpecificAuditIdentityAndIncludesAggregateClientApproval()
    {
        var fixture = CreateFixture();
        var prepared = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var selectedItem = Assert.Single(prepared.Items);
        var sharedBatch = Assert.Single(prepared.Batches);
        var selectedGroup = Assert.Single(fixture.ReviewService.Workspace.Groups);
        var selectedDocument = Assert.Single(fixture.ReviewService.Workspace.Documents);

        var otherClientId = Guid.NewGuid();
        var otherGroupId = Guid.NewGuid();
        var otherDocumentId = Guid.NewGuid();
        var otherItemId = Guid.NewGuid();
        var otherDocument = selectedDocument with
        {
            Id = otherDocumentId,
            ClientId = otherClientId,
            ClientDisplayName = "Outro cliente sintético",
            GroupId = otherGroupId,
            Sha256 = "other-client-sha",
            SemanticDuplicateKey = "other-client-semantic-key",
        };
        var approval = Assert.IsType<GroupApprovalSnapshot>(selectedGroup.ApprovalSnapshot);
        var otherGroup = selectedGroup with
        {
            Id = otherGroupId,
            GroupingKey = $"{otherClientId:N}|2026-08",
            ClientId = otherClientId,
            ClientDisplayName = "Outro cliente sintético",
            DocumentIds = [otherDocumentId],
            ApprovalSnapshot = approval with
            {
                Id = Guid.NewGuid(),
                GroupId = otherGroupId,
                ContentHash = "other-client-content-hash",
                Documents =
                [
                    Assert.Single(approval.Documents) with
                    {
                        DocumentId = otherDocumentId,
                        ClientId = otherClientId,
                        Sha256 = otherDocument.Sha256,
                        SemanticDuplicateKey = otherDocument.SemanticDuplicateKey,
                    },
                ],
            },
        };
        var otherItem = selectedItem with
        {
            Id = otherItemId,
            GroupId = otherGroupId,
            ClientId = otherClientId,
            ClientDisplayName = "Outro cliente sintético",
        };
        var selectedAggregateAudit = new ReviewAuditEvent(
            Guid.NewGuid(),
            "synthetic-scope",
            "synthetic-operator",
            clock.UtcNow,
            "groups.client_approved",
            null,
            null,
            null,
            $"client:{fixture.ClientId:N};groups:1;documents:1;competences:1;periods:08/2026",
            "Aprovação sintética do cliente selecionado.",
            "selected-aggregate");
        var otherAggregateAudit = selectedAggregateAudit with
        {
            Id = Guid.NewGuid(),
            NewValue = $"client:{otherClientId:N};groups:1;documents:1;competences:1;periods:08/2026",
            CorrelationId = "other-aggregate",
        };
        fixture.ReviewService.Workspace = fixture.ReviewService.Workspace with
        {
            Documents = [selectedDocument, otherDocument],
            Groups = [selectedGroup, otherGroup],
            AuditEvents = [selectedAggregateAudit, otherAggregateAudit],
        };

        var otherDispatchAudit = new DispatchAuditEvent(
            Guid.NewGuid(),
            "synthetic-scope",
            "synthetic-operator",
            clock.UtcNow,
            "dispatch_composed",
            sharedBatch.Id,
            otherItemId,
            otherGroupId,
            "ReadyForApproval",
            null,
            "other-dispatch");
        var batchOnlyAudit = otherDispatchAudit with
        {
            Id = Guid.NewGuid(),
            Action = "dispatch_batch_checked",
            DispatchItemId = null,
            GroupId = null,
            CorrelationId = "shared-batch",
        };
        await fixture.Store.SaveAsync(
            prepared with
            {
                Batches =
                [
                    sharedBatch with
                    {
                        GroupIds = [fixture.GroupId, otherGroupId],
                        DispatchItemIds = [selectedItem.Id, otherItemId],
                    },
                ],
                Items = [selectedItem, otherItem],
                AuditEvents = [.. prepared.AuditEvents, otherDispatchAudit, batchOnlyAudit],
            },
            CancellationToken.None);

        await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.ForClientInMonth(fixture.ClientId, 2026, 8, "Cliente sintético"),
            CancellationToken.None);

        var export = Assert.Single(fixture.ReportExporter.Calls);
        Assert.Equal(selectedItem.Id, Assert.Single(export.Dispatch.Items).Id);
        Assert.DoesNotContain(export.Dispatch.AuditEvents, audit => audit.Id == otherDispatchAudit.Id);
        Assert.Contains(export.Dispatch.AuditEvents, audit => audit.DispatchItemId == selectedItem.Id);
        Assert.Contains(export.Dispatch.AuditEvents, audit => audit.Id == batchOnlyAudit.Id);
        var filteredBatch = Assert.Single(export.Dispatch.Batches);
        Assert.Equal([selectedItem.Id], filteredBatch.DispatchItemIds);
        Assert.Equal([fixture.GroupId], filteredBatch.GroupIds);
        Assert.Contains(export.Review.AuditEvents, audit => audit.Id == selectedAggregateAudit.Id);
        Assert.DoesNotContain(export.Review.AuditEvents, audit => audit.Id == otherAggregateAudit.Id);
    }

    [Fact]
    public async Task ReportExportFiltersAllPeriodsMonthAndClientWithoutCrossClientLeakage()
    {
        var fixture = CreateFixture();
        var prepared = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                DispatchOperationMode.Test,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var augustItem = Assert.Single(prepared.Items);
        var augustBatch = Assert.Single(prepared.Batches);
        var augustDocument = Assert.Single(fixture.ReviewService.Workspace.Documents);
        var augustGroup = Assert.Single(fixture.ReviewService.Workspace.Groups);

        var septemberClientId = Guid.NewGuid();
        var septemberDocumentId = Guid.NewGuid();
        var septemberGroupId = Guid.NewGuid();
        var septemberItemId = Guid.NewGuid();
        var septemberBatchId = Guid.NewGuid();
        var septemberDocument = augustDocument with
        {
            Id = septemberDocumentId,
            Sha256 = "def456",
            ClientId = septemberClientId,
            ClientDisplayName = "Outro cliente sintético",
            Period = new DocumentPeriod(
                DocumentPeriodKind.Monthly,
                9,
                2026,
                null,
                null,
                new DateOnly(2026, 10, 7),
                "09/2026"),
            SemanticDuplicateKey = "synthetic-semantic-key-september",
            GroupId = septemberGroupId,
        };
        var augustApproval = Assert.IsType<GroupApprovalSnapshot>(augustGroup.ApprovalSnapshot);
        var septemberApprovalDocument = Assert.Single(augustApproval.Documents) with
        {
            DocumentId = septemberDocumentId,
            Sha256 = septemberDocument.Sha256,
            ClientId = septemberClientId,
            PeriodKey = "month:2026-09",
            SemanticDuplicateKey = septemberDocument.SemanticDuplicateKey,
        };
        var septemberGroup = augustGroup with
        {
            Id = septemberGroupId,
            GroupingKey = $"{septemberClientId:N}|2026-09",
            ClientId = septemberClientId,
            ClientDisplayName = "Outro cliente sintético",
            PeriodKey = "2026-09",
            PeriodLabel = "09/2026",
            DocumentIds = [septemberDocumentId],
            ApprovalSnapshot = augustApproval with
            {
                Id = Guid.NewGuid(),
                GroupId = septemberGroupId,
                ContentHash = "review-content-hash-september",
                Documents = [septemberApprovalDocument],
            },
        };
        fixture.ReviewService.Workspace = fixture.ReviewService.Workspace with
        {
            Documents = [augustDocument, septemberDocument],
            Groups = [augustGroup, septemberGroup],
        };

        var septemberMessage = augustItem.Message! with
        {
            Attachments =
            [
                Assert.Single(augustItem.Message.Attachments) with
                {
                    DocumentId = septemberDocumentId,
                    Sha256 = septemberDocument.Sha256,
                },
            ],
            DispatchFingerprint = new string('b', 64),
        };
        var septemberItem = augustItem with
        {
            Id = septemberItemId,
            BatchId = septemberBatchId,
            GroupId = septemberGroupId,
            ClientId = septemberClientId,
            ClientDisplayName = "Outro cliente sintético",
            PeriodLabel = "09/2026",
            Message = septemberMessage,
        };
        var septemberBatch = augustBatch with
        {
            Id = septemberBatchId,
            GroupIds = [septemberGroupId],
            DispatchItemIds = [septemberItemId],
        };
        await fixture.Store.SaveAsync(
            prepared with
            {
                Batches = [augustBatch, septemberBatch],
                Items = [augustItem, septemberItem],
            },
            CancellationToken.None);

        var allPeriods = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            CancellationToken.None);
        var august = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.ForMonth(2026, 8),
            CancellationToken.None);
        var selectedClient = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.ForClient(septemberClientId, "Outro cliente sintético"),
            CancellationToken.None);
        var year = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.ForYear(2026),
            CancellationToken.None);
        var inclusiveRange = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.ForRange(2026, 8, 2026, 9),
            CancellationToken.None);
        var selectedClientInSeptember = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.ForClientInMonth(
                septemberClientId,
                2026,
                9,
                "Outro cliente sintético"),
            CancellationToken.None);
        var selectedClientOutsidePeriod = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            DispatchReportFilter.ForClientInRange(
                septemberClientId,
                2026,
                8,
                2026,
                8,
                "Outro cliente sintético"),
            CancellationToken.None);
        var legacyYear = await fixture.Service.ExportReportsAsync(
            Path.GetTempPath(),
            2026,
            null,
            CancellationToken.None);

        Assert.Equal(2, allPeriods.ItemCount);
        Assert.Equal(1, august.ItemCount);
        Assert.Equal(1, selectedClient.ItemCount);
        Assert.Equal(2, year.ItemCount);
        Assert.Equal(2, inclusiveRange.ItemCount);
        Assert.Equal(1, selectedClientInSeptember.ItemCount);
        Assert.Equal(0, selectedClientOutsidePeriod.ItemCount);
        Assert.Equal(2, legacyYear.ItemCount);
        Assert.Equal(8, fixture.ReportExporter.Calls.Count);

        var allCall = fixture.ReportExporter.Calls[0];
        Assert.Equal(DispatchReportScope.AllPeriods, allCall.Filter.Scope);
        Assert.Equal(2, allCall.Dispatch.Items.Count);
        Assert.Equal(2, allCall.Review.Documents.Count);

        var monthCall = fixture.ReportExporter.Calls[1];
        Assert.Equal(DispatchReportScope.Month, monthCall.Filter.Scope);
        Assert.All(monthCall.Dispatch.Items, item => Assert.Equal("08/2026", item.PeriodLabel));
        Assert.All(monthCall.Review.Documents, document => Assert.Equal(8, document.Period.Month));

        var clientCall = fixture.ReportExporter.Calls[2];
        Assert.Equal(DispatchReportScope.Client, clientCall.Filter.Scope);
        Assert.All(clientCall.Dispatch.Items, item => Assert.Equal(septemberClientId, item.ClientId));
        Assert.All(clientCall.Review.Documents, document => Assert.Equal(septemberClientId, document.ClientId));
        Assert.DoesNotContain(clientCall.Dispatch.Items, item => item.ClientId == fixture.ClientId);
        Assert.DoesNotContain(clientCall.Review.Documents, document => document.ClientId == fixture.ClientId);

        var yearCall = fixture.ReportExporter.Calls[3];
        Assert.Equal(DispatchReportScope.Year, yearCall.Filter.Scope);
        Assert.All(yearCall.Dispatch.Items, item => Assert.EndsWith("/2026", item.PeriodLabel, StringComparison.Ordinal));

        var rangeCall = fixture.ReportExporter.Calls[4];
        Assert.Equal(DispatchReportScope.Range, rangeCall.Filter.Scope);
        Assert.Equal(["08/2026", "09/2026"], rangeCall.Dispatch.Items.Select(item => item.PeriodLabel).ToArray());

        var composedCall = fixture.ReportExporter.Calls[5];
        Assert.Equal(DispatchReportScope.Month, composedCall.Filter.Scope);
        Assert.Equal(septemberClientId, composedCall.Filter.ClientId);
        Assert.All(composedCall.Dispatch.Items, item =>
        {
            Assert.Equal(septemberClientId, item.ClientId);
            Assert.Equal("09/2026", item.PeriodLabel);
        });
        Assert.All(composedCall.Review.Documents, document =>
        {
            Assert.Equal(septemberClientId, document.ClientId);
            Assert.Equal(9, document.Period.Month);
        });

        var emptyIntersectionCall = fixture.ReportExporter.Calls[6];
        Assert.Empty(emptyIntersectionCall.Dispatch.Items);
        Assert.Empty(emptyIntersectionCall.Review.Documents);
        Assert.Empty(emptyIntersectionCall.Review.Groups);

        var legacyYearCall = fixture.ReportExporter.Calls[7];
        Assert.Equal(DispatchReportScope.Year, legacyYearCall.Filter.Scope);
    }

    [Theory]
    [InlineData(0, 2026)]
    [InlineData(13, 2026)]
    [InlineData(8, 1899)]
    public async Task ReportExportRejectsInvalidMonthBeforeCallingTheExporter(int month, int year)
    {
        var fixture = CreateFixture();

        var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExportReportsAsync(
                Path.GetTempPath(),
                DispatchReportFilter.ForMonth(year, month),
                CancellationToken.None));

        Assert.Equal("REPORT_MONTH_REQUIRED", exception.Code);
        Assert.Empty(fixture.ReportExporter.Calls);
    }

    [Fact]
    public async Task ReportExportRejectsClientScopeWithoutAClientBeforeCallingTheExporter()
    {
        var fixture = CreateFixture();

        var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExportReportsAsync(
                Path.GetTempPath(),
                new DispatchReportFilter(DispatchReportScope.Client),
                CancellationToken.None));

        Assert.Equal("REPORT_CLIENT_REQUIRED", exception.Code);
        Assert.Empty(fixture.ReportExporter.Calls);
    }

    [Fact]
    public async Task ReportExportRejectsAnInvertedCompetenceRangeBeforeCallingTheExporter()
    {
        var fixture = CreateFixture();

        var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExportReportsAsync(
                Path.GetTempPath(),
                DispatchReportFilter.ForRange(2027, 1, 2026, 12),
                CancellationToken.None));

        Assert.Equal("REPORT_RANGE_INVERTED", exception.Code);
        Assert.Empty(fixture.ReportExporter.Calls);
    }

    [Theory]
    [InlineData(1899, 8, 2026, 9)]
    [InlineData(2026, 0, 2026, 9)]
    [InlineData(2026, 8, 10000, 9)]
    [InlineData(2026, 8, 2026, 13)]
    public async Task ReportExportRejectsInvalidCompetenceRangeBeforeCallingTheExporter(
        int startYear,
        int startMonth,
        int endYear,
        int endMonth)
    {
        var fixture = CreateFixture();

        var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExportReportsAsync(
                Path.GetTempPath(),
                DispatchReportFilter.ForRange(startYear, startMonth, endYear, endMonth),
                CancellationToken.None));

        Assert.Equal("REPORT_RANGE_REQUIRED", exception.Code);
        Assert.Empty(fixture.ReportExporter.Calls);
    }

    [Fact]
    public async Task Phase7GraphOutboundFailsClosedWhenCentralAuditGuardDenies()
    {
        foreach (var mode in new[] { DispatchOperationMode.Test, DispatchOperationMode.Send })
        {
            var fixture = CreateFixture(options =>
            {
                options.ProviderKey = DispatchWorkflowOptions.MicrosoftGraphProviderKey;
                options.MicrosoftGraphEnabled = true;
                options.MicrosoftGraphSendEnabled = true;
                options.MicrosoftGraphControlledRecipient = "controlado@example.invalid";
            });
            var item = await PrepareAndApproveAsync(fixture, mode);
            fixture.RemoteGuard.Response = fixture.RemoteGuard.Response with
            {
                Authorized = mode == DispatchOperationMode.Test,
                EmailSendEnabled = false,
                ErrorCode = "SEND_DISABLED_REMOTELY",
            };

            var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
                fixture.Service.ExecuteAsync(
                    new ExecuteDispatchRequest(
                        item.Id,
                        mode == DispatchOperationMode.Send ? "CONFIRMAR GRAPH 1" : null),
                    CancellationToken.None));

            Assert.Equal("SEND_DISABLED_REMOTELY", exception.Code);
            Assert.Equal(1, fixture.RemoteGuard.CallCount);
            Assert.Equal(0, fixture.Provider.SendCallCount);
            Assert.Empty(fixture.Store.Workspace.Attempts);
        }
    }

    [Fact]
    public async Task Phase7GraphSendFailsClosedWhenCentralAuditIsUnavailable()
    {
        var fixture = CreateFixture(options =>
        {
            options.ProviderKey = DispatchWorkflowOptions.MicrosoftGraphProviderKey;
            options.MicrosoftGraphEnabled = true;
            options.MicrosoftGraphSendEnabled = true;
            options.MicrosoftGraphControlledRecipient = "controlado@example.invalid";
        });
        var item = await PrepareAndApproveAsync(fixture, DispatchOperationMode.Send);
        fixture.RemoteGuard.Failure = new HttpRequestException("Synthetic central outage.");

        var exception = await Assert.ThrowsAsync<DispatchWorkflowException>(() =>
            fixture.Service.ExecuteAsync(
                new ExecuteDispatchRequest(item.Id, "CONFIRMAR GRAPH 1"),
                CancellationToken.None));

        Assert.Equal("REMOTE_SEND_GUARD_UNAVAILABLE", exception.Code);
        Assert.Equal(0, fixture.Provider.SendCallCount);
        Assert.Empty(fixture.Store.Workspace.Attempts);
    }

    public void Dispose()
    {
        File.Delete(attachmentPath);
        GC.SuppressFinalize(this);
    }

    private Fixture CreateFixture(
        Action<DispatchWorkflowOptions>? configure = null,
        RecognizedDocumentType documentType = RecognizedDocumentType.Payroll)
    {
        var clientId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var documentId = Guid.NewGuid();
        var reviewApprovalId = Guid.NewGuid();
        var review = new DocumentReviewWorkspace(
            "synthetic-scope",
            [CreateDocument(documentId, groupId, clientId, documentType)],
            [new DocumentDispatchGroup(
                groupId,
                $"{clientId:N}|2026-08",
                "monthly-accounting",
                "phase5-review-v1",
                clientId,
                null,
                "Cliente sintético",
                "2026-08",
                "08/2026",
                ReviewGroupState.Approved,
                1,
                [documentId],
                [],
                new GroupApprovalSnapshot(
                    reviewApprovalId,
                    groupId,
                    1,
                    "review-content-hash",
                    "synthetic-operator",
                    clock.UtcNow,
                    [new ApprovalDocumentSnapshot(
                        documentId,
                        "abc123",
                        1,
                        clientId,
                        null,
                        "month:2026-08",
                        "synthetic-semantic-key")]),
                clock.UtcNow,
                clock.UtcNow)],
            []);
        var options = new DispatchWorkflowOptions
        {
            ProductionRolloutReady = true,
            ProductionMaximumBatchSize = 5,
        };
        configure?.Invoke(options);
        var store = new InMemoryDispatchStore();
        var catalog = new FakeCatalog(clientId, clock.UtcNow);
        var context = new FixedDispatchContext();
        var provider = new RecordingFakeProvider(options.ProviderKey);
        var reviewService = new FixedReviewService(review);
        var remoteGuard = new FixedRemoteSendGuard();
        var reportExporter = new RecordingReportExporter(clock);
        var composer = new DeterministicDispatchMessageComposer(catalog, context, clock, options);
        var service = new DispatchWorkflowService(
            store,
            reviewService,
            composer,
            provider,
            context,
            remoteGuard,
            reportExporter,
            clock,
            options);
        return new Fixture(
            service,
            store,
            reviewService,
            catalog,
            provider,
            remoteGuard,
            reportExporter,
            options,
            clientId,
            groupId);
    }

    private ReviewDocument CreateDocument(
        Guid id,
        Guid groupId,
        Guid clientId,
        RecognizedDocumentType documentType) => new(
        id,
        attachmentPath,
        Path.GetFileName(attachmentPath),
        "abc123",
        new FileInfo(attachmentPath).Length,
        1,
        documentType,
        "synthetic-v1",
        clientId,
        null,
        "Cliente sintético",
        "11.***.***/****-81",
        ClientResolutionMethod.ExactClientTaxId,
        .99m,
        [],
        [],
        [],
        [],
        new DocumentPeriod(DocumentPeriodKind.Monthly, 8, 2026, null, null, new DateOnly(2026, 9, 7), "08/2026"),
        "synthetic-semantic-key",
        ReviewDocumentState.Approved,
        1,
        groupId,
        [],
        clock.UtcNow,
        clock.UtcNow);

    private static Guid AddApprovedMonthlyGroup(Fixture fixture, int month)
    {
        var originalDocument = Assert.Single(fixture.ReviewService.Workspace.Documents);
        var originalGroup = Assert.Single(fixture.ReviewService.Workspace.Groups);
        var originalApproval = Assert.IsType<GroupApprovalSnapshot>(originalGroup.ApprovalSnapshot);
        var originalApprovalDocument = Assert.Single(originalApproval.Documents);
        var groupId = Guid.NewGuid();
        var documentId = Guid.NewGuid();
        var document = originalDocument with
        {
            Id = documentId,
            GroupId = groupId,
            Period = new DocumentPeriod(
                DocumentPeriodKind.Monthly,
                month,
                2026,
                null,
                null,
                new DateOnly(2026, Math.Min(month + 1, 12), 7),
                $"{month:00}/2026"),
            Sha256 = $"synthetic-sha-{month:00}",
            SemanticDuplicateKey = $"synthetic-semantic-{month:00}",
        };
        var group = originalGroup with
        {
            Id = groupId,
            GroupingKey = $"{fixture.ClientId:N}|2026-{month:00}",
            PeriodKey = $"2026-{month:00}",
            PeriodLabel = $"{month:00}/2026",
            DocumentIds = [documentId],
            ApprovalSnapshot = originalApproval with
            {
                Id = Guid.NewGuid(),
                GroupId = groupId,
                ContentHash = $"review-content-hash-{month:00}",
                Documents =
                [
                    originalApprovalDocument with
                    {
                        DocumentId = documentId,
                        Sha256 = document.Sha256,
                        PeriodKey = $"month:2026-{month:00}",
                        SemanticDuplicateKey = document.SemanticDuplicateKey,
                    },
                ],
            },
        };
        fixture.ReviewService.Workspace = fixture.ReviewService.Workspace with
        {
            Documents = [.. fixture.ReviewService.Workspace.Documents, document],
            Groups = [.. fixture.ReviewService.Workspace.Groups, group],
        };
        return groupId;
    }

    private static async Task<DispatchItem> PrepareAndApproveAsync(
        Fixture fixture,
        DispatchOperationMode mode)
    {
        var workspace = await fixture.Service.PrepareAsync(
            new PrepareDispatchRequest(
                [fixture.GroupId],
                ProcessingSelectionMode.Individual,
                mode,
                "auditoria@example.invalid",
                FakeDeliveryScenario.Success),
            CancellationToken.None);
        var item = workspace.Items.Single(current => current.State == DispatchItemState.ReadyForApproval);
        workspace = await fixture.Service.ApproveAsync(item.Id, CancellationToken.None);
        return workspace.Items.Single(current => current.Id == item.Id);
    }

    private sealed record Fixture(
        DispatchWorkflowService Service,
        InMemoryDispatchStore Store,
        FixedReviewService ReviewService,
        FakeCatalog Catalog,
        RecordingFakeProvider Provider,
        FixedRemoteSendGuard RemoteGuard,
        RecordingReportExporter ReportExporter,
        DispatchWorkflowOptions Options,
        Guid ClientId,
        Guid GroupId);

    private sealed class FixedClock : IClock
    {
        public DateTimeOffset UtcNow { get; } = new(2026, 8, 21, 12, 0, 0, TimeSpan.Zero);
    }

    private sealed class FixedDispatchContext : IDispatchExecutionContextAccessor
    {
        private static readonly IReadOnlySet<string> Permissions = new HashSet<string>(
            AppPermissions.All,
            StringComparer.Ordinal);

        public Task<DispatchExecutionContext> GetCurrentAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new DispatchExecutionContext(
                "synthetic-scope",
                "synthetic-operator",
                Permissions));
    }

    private sealed class InMemoryDispatchStore : IDispatchWorkflowStore
    {
        public DispatchWorkspace Workspace { get; private set; } = DispatchWorkspace.Empty("synthetic-scope");

        public Task<DispatchWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken) =>
            Task.FromResult(Workspace);

        public Task SaveAsync(DispatchWorkspace workspace, CancellationToken cancellationToken)
        {
            Workspace = workspace;
            return Task.CompletedTask;
        }
    }

    private sealed class FixedReviewService(DocumentReviewWorkspace workspace) : IDocumentReviewService
    {
        public DocumentReviewWorkspace Workspace { get; set; } = workspace;

        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) => Task.FromResult(Workspace);

        public Task<DocumentReviewWorkspace> ImportAsync(string localPath, DocumentRecognitionResult recognition, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(Guid documentId, DocumentPeriod period, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(Guid documentId, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(Guid documentId, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> OverrideClientAsync(Guid documentId, ClientResolutionCandidate candidate, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> SplitGroupAsync(Guid groupId, IReadOnlyCollection<Guid> documentIds, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> MergeGroupsAsync(Guid targetGroupId, Guid sourceGroupId, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> ApproveGroupAsync(Guid groupId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(IReadOnlyCollection<Guid> groupIds, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class FakeCatalog(Guid clientId, DateTimeOffset timestamp) : IClientCatalogService
    {
        public MessageTemplateModel Template { get; set; } = new(
            Guid.NewGuid(),
            clientId,
            null,
            "Template sintético",
            "Documentos de {{periodo.rotulo}} — {{cliente.nome_preferencia_ou_razao_social}}",
            "Olá {{contato.nome}},\n\n{{documentos.lista}}\nVencimentos: {{vencimentos.lista}}\n{{escritorio.nome}}",
            SignatureModeModel.Organization,
            true,
            true,
            1,
            timestamp);

        public Task<ClientDetails?> GetAsync(Guid requestedClientId, CancellationToken cancellationToken) =>
            Task.FromResult<ClientDetails?>(new ClientDetails(
                clientId,
                PersonTypeModel.LegalEntity,
                "Cliente sintético Ltda.",
                "Cliente sintético",
                "SYN-001",
                "11222333000181",
                true,
                Template.Id,
                Template.Id,
                null,
                1,
                timestamp,
                timestamp,
                [],
                [],
                [new RecipientModel(
                    Guid.NewGuid(),
                    null,
                    "Contato sintético",
                    "cliente@example.invalid",
                    DeliveryRoleModel.To,
                    null,
                    true,
                    true,
                    null,
                    null)]));

        public Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(Guid? requestedClientId, bool includeInactive, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<MessageTemplateModel>>([Template]);

        public Task<ClientSearchResponse> SearchAsync(string? search, bool? isActive, PersonTypeModel? personType, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<ClientDetails> SaveAsync(Guid? requestedClientId, ClientMutationRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<ClientReadinessResponse?> GetReadinessAsync(Guid requestedClientId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<MessageTemplateModel> SaveTemplateAsync(Guid? templateId, MessageTemplateMutationRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<ClientCatalogTransferDocument> ExportAsync(CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<ClientCatalogImportResult> ImportAsync(ClientCatalogImportRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<IReadOnlyList<AuditEventModel>> GetAuditAsync(Guid requestedClientId, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class RecordingFakeProvider(string providerKey) : IEmailProvider
    {
        public string ProviderKey => providerKey;
        public int SendCallCount { get; private set; }
        public int DraftCallCount { get; private set; }
        public int ReconcileCallCount { get; private set; }
        public EmailProviderResult NextSendResult { get; set; } = new(DeliveryAttemptState.AcceptedByProvider, "fake-message", null, null, null);
        public EmailProviderResult NextReconciliationResult { get; set; } = new(DeliveryAttemptState.Ambiguous, null, null, "STILL_AMBIGUOUS", "Ainda ambíguo.");

        public Task<EmailProviderAccount> GetAccountAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new EmailProviderAccount(ProviderKey, "synthetic://local", "Synthetic", true));

        public Task<EmailProviderCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new EmailProviderCapabilities(true, true, true, 25L * 1024 * 1024));

        public Task<EmailProviderResult> CreateDraftAsync(EmailEnvelope envelope, CancellationToken cancellationToken)
        {
            DraftCallCount++;
            return Task.FromResult(new EmailProviderResult(DeliveryAttemptState.DraftCreated, null, "fake-draft", null, null));
        }

        public Task<EmailProviderResult> SendAsync(EmailEnvelope envelope, CancellationToken cancellationToken)
        {
            SendCallCount++;
            return Task.FromResult(NextSendResult);
        }

        public Task<EmailProviderResult> ReconcileAsync(DeliveryAttempt attempt, CancellationToken cancellationToken)
        {
            ReconcileCallCount++;
            return Task.FromResult(NextReconciliationResult);
        }
    }

    private sealed class FixedRemoteSendGuard : IRemoteEmailSendGuard
    {
        public int CallCount { get; private set; }

        public Exception? Failure { get; set; }

        public EmailSendPreflightResponse Response { get; set; } = new(
            true,
            true,
            DispatchWorkflowOptions.CurrentApplicationVersion,
            Guid.NewGuid(),
            null);

        public Task<EmailSendPreflightResponse> AuthorizeAsync(
            EmailSendPreflightRequest request,
            CancellationToken cancellationToken)
        {
            CallCount++;
            return Failure is null
                ? Task.FromResult(Response)
                : Task.FromException<EmailSendPreflightResponse>(Failure);
        }
    }

    private sealed class RecordingReportExporter(IClock clock) : IDispatchReportExporter
    {
        public List<ReportExportCall> Calls { get; } = [];

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
            Calls.Add(new ReportExportCall(dispatchWorkspace, reviewWorkspace, filter));
            return Task.FromResult(new DispatchReportResult(
                "report.xlsx",
                [],
                dispatchWorkspace.Items.Count,
                clock.UtcNow,
                "report.pdf"));
        }
    }

    private sealed record ReportExportCall(
        DispatchWorkspace Dispatch,
        DocumentReviewWorkspace Review,
        DispatchReportFilter Filter);
}
