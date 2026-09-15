using System.Security.Cryptography;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.Pilot;
using FolhasDaMichelly.Application.Preferences;
using FolhasDaMichelly.Application.Production;
using FolhasDaMichelly.Application.Updates;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Desktop;
using FolhasDaMichelly.Desktop.Models;
using FolhasDaMichelly.Desktop.ViewModels;
using FolhasDaMichelly.Domain.Clients;

namespace FolhasDaMichelly.Ui.Tests;

public sealed class MainViewModelTests
{
    [Fact]
    public void DesktopAllowsOnlyOneLocalInstancePerLockFile()
    {
        var directory = Path.Combine(Path.GetTempPath(), "folhas-single-instance-tests", Guid.NewGuid().ToString("N"));
        var lockPath = Path.Combine(directory, "desktop.lock");
        try
        {
            using var first = DesktopSingleInstanceGuard.TryAcquire(lockPath);
            Assert.NotNull(first);
            using var second = DesktopSingleInstanceGuard.TryAcquire(lockPath);
            Assert.Null(second);

            first.Dispose();
            using var afterClose = DesktopSingleInstanceGuard.TryAcquire(lockPath);
            Assert.NotNull(afterClose);
            afterClose.Dispose();
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
    }

    [Theory]
    [InlineData("logo1.png", "8e4f70f3295f7d572eacd89147b57a93c564f39d385effcbe683ba34e37ac919")]
    [InlineData("aaa1.png", "d521cfc5512c2caebb1b6b93183109de6334426798b580bd0de422f048a60d42")]
    [InlineData("aaa2.png", "ed1bf0373ed2538f18ce0e9318c82f632d10297e7e2f0b657c03790f1cebf6e0")]
    public void OfficialBrandingSourceMatchesAuthorizedContent(string fileName, string expectedSha256)
    {
        var repositoryRoot = FindRepositoryRoot();
        var assetPath = Path.Combine(
            repositoryRoot,
            "src",
            "FolhasDaMichelly.Desktop",
            "Assets",
            "Branding",
            fileName);

        using var stream = File.OpenRead(assetPath);
        var actualSha256 = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();

        Assert.Equal(expectedSha256, actualSha256);
    }

    private static string FindRepositoryRoot()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory);
             directory is not null;
             directory = directory.Parent)
        {
            if (File.Exists(Path.Combine(directory.FullName, "FolhasDaMichelly.slnx")))
            {
                return directory.FullName;
            }
        }

        throw new DirectoryNotFoundException("A raiz do repositório não foi encontrada.");
    }

    [Fact]
    public void InitialStateUsesCustomerLanguageAndStartsAtHome()
    {
        var viewModel = new MainViewModel();

        Assert.Equal("Folhas da Michelly", viewModel.ProductName);
        Assert.Contains("Fase 12", viewModel.Phase, StringComparison.Ordinal);
        Assert.Equal("0.12.7", viewModel.CurrentApplicationVersion);
        Assert.Contains("Tudo pronto", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("25 MB", viewModel.DocumentImportSummary, StringComparison.Ordinal);
        Assert.Contains("fluxo simples", viewModel.Subtitle, StringComparison.OrdinalIgnoreCase);
        Assert.Equal("auditoria@example.invalid", viewModel.TestDestination);
        Assert.Equal(AppSection.Home, viewModel.CurrentSection);
        Assert.True(viewModel.IsHomeSection);
    }

    [Fact]
    public void Phase11PilotRemovesSendFromTheCustomerJourney()
    {
        var viewModel = new MainViewModel(new PilotModeOptions
        {
            Enabled = true,
            EnvironmentName = "staging",
            AllowTest = true,
            AllowDraft = true,
            AllowSend = false,
            RequireNonProductionData = true,
            MaximumClients = 5,
        });

        Assert.True(viewModel.IsPilotMode);
        Assert.Equal(
            [DispatchOperationMode.Test, DispatchOperationMode.Draft],
            viewModel.DispatchOperationModes);
        Assert.DoesNotContain(DispatchOperationMode.Send, viewModel.DispatchOperationModes);
        Assert.Contains("bloqueado", viewModel.PilotSafeguardSummary, StringComparison.OrdinalIgnoreCase);
        Assert.Equal("Ambiente de homologação", viewModel.PilotEnvironmentLabel);
    }

    [Fact]
    public void Phase12ClosedProductionIsReadOnlyAndRemovesSendFromTheJourney()
    {
        var options = new ProductionRolloutOptions();
        var snapshot = ProductionReadinessEvaluator.Evaluate(
            options,
            pilotModeEnabled: false,
            globalSendEnabled: false,
            stableChannelEnabled: false);
        var viewModel = new MainViewModel(options, snapshot);

        Assert.False(viewModel.IsProductionRolloutVisible);
        Assert.False(viewModel.IsProductionReady);
        Assert.Equal("Produção bloqueada", viewModel.ProductionStatusTitle);
        Assert.Equal("Etapa fechada", viewModel.ProductionStageLabel);
        Assert.DoesNotContain(DispatchOperationMode.Send, viewModel.DispatchOperationModes);
        Assert.Contains("sequência", viewModel.ProductionLimitsSummary, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void StatusMessageRaisesPropertyChanged()
    {
        var viewModel = new MainViewModel();
        string? changedProperty = null;
        viewModel.PropertyChanged += (_, args) => changedProperty = args.PropertyName;

        viewModel.StatusMessage = "Validação sintética concluída.";

        Assert.Equal(nameof(MainViewModel.StatusMessage), changedProperty);
    }

    [Fact]
    public void NewClientCannotBeDeactivatedAndRecipientRoleIsExplicit()
    {
        var viewModel = new MainViewModel
        {
            NewRecipientName = "Financeiro sintético",
            NewRecipientEmail = "financeiro@example.invalid",
        };

        viewModel.AddRecipientCommand.Execute(null);
        viewModel.ToggleClientActiveCommand.Execute(null);

        var recipient = Assert.Single(viewModel.Recipients);
        Assert.Equal(FolhasDaMichelly.Contracts.Clients.DeliveryRoleModel.To, recipient.DeliveryRole);
        Assert.True(viewModel.IsClientActive);
        Assert.False(viewModel.HasExistingClient);
        Assert.Contains("salve", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ClientPanelsCanBeRecollectedWithoutLeavingAnEmptyWorkspace()
    {
        var viewModel = new MainViewModel();

        Assert.True(viewModel.IsClientListPanelExpanded);
        Assert.True(viewModel.IsClientEditorPanelExpanded);

        viewModel.ToggleClientListPanelCommand.Execute(null);

        Assert.False(viewModel.IsClientListPanelExpanded);
        Assert.True(viewModel.IsClientEditorPanelExpanded);

        viewModel.ToggleClientEditorPanelCommand.Execute(null);

        Assert.True(viewModel.IsClientListPanelExpanded);
        Assert.False(viewModel.IsClientEditorPanelExpanded);

        viewModel.ToggleClientListPanelCommand.Execute(null);

        Assert.False(viewModel.IsClientListPanelExpanded);
        Assert.True(viewModel.IsClientEditorPanelExpanded);
    }

    [Fact]
    public void NewClientFocusesTheEditorAndReleasesTheListSpace()
    {
        var viewModel = new MainViewModel
        {
            IsClientEditorPanelExpanded = false,
        };

        viewModel.NewClientCommand.Execute(null);

        Assert.True(viewModel.IsClientEditorPanelExpanded);
        Assert.False(viewModel.IsClientListPanelExpanded);
        Assert.Equal(AppSection.Clients, viewModel.CurrentSection);
    }

    [Theory]
    [InlineData("financeiro@dominio")]
    [InlineData("financeiro@-dominio.com")]
    [InlineData("financeiro@dominio.c")]
    public void DeliveryContactRequiresACompleteValidEmailDomain(string invalidEmail)
    {
        var viewModel = new MainViewModel
        {
            NewRecipientName = "Financeiro sintético",
            NewRecipientEmail = invalidEmail,
        };

        viewModel.AddRecipientCommand.Execute(null);

        Assert.Empty(viewModel.Recipients);
        Assert.True(viewModel.ClientFeedbackIsError);
        Assert.Contains("não é válido", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void PersonTypeChangesFieldsAndFormatsBrazilianTaxIds()
    {
        var viewModel = new MainViewModel
        {
            SelectedPersonType = PersonTypeModel.Individual,
            PrimaryTaxId = "52998224725",
        };

        Assert.True(viewModel.IsIndividual);
        Assert.False(viewModel.IsLegalEntity);
        Assert.Equal("CPF", viewModel.PrimaryTaxIdLabel);
        Assert.Equal("Nome completo", viewModel.LegalNameLabel);
        Assert.Equal("529.982.247-25", viewModel.PrimaryTaxId);

        viewModel.SelectedPersonType = PersonTypeModel.LegalEntity;
        viewModel.PrimaryTaxId = "11222333000181";

        Assert.Equal("CNPJ", viewModel.PrimaryTaxIdLabel);
        Assert.Equal("Razão social", viewModel.LegalNameLabel);
        Assert.Equal("11.222.333/0001-81", viewModel.PrimaryTaxId);
    }

    [Fact]
    public void CompanyPartnersAreKeptSeparateFromDocumentIdentifiers()
    {
        var viewModel = new MainViewModel
        {
            NewPartnerName = "Sócia-administradora sintética",
            NewPartnerCpf = "52998224725",
            NewPartnerEmail = "socia@example.invalid",
            UsePartnerAsDeliveryContact = true,
            SelectedPartnerRole = ClientPartnerRoleModel.ManagingPartner,
        };

        viewModel.AddPartnerCommand.Execute(null);

        var partner = Assert.Single(viewModel.Partners);
        Assert.Equal("529.982.247-25", partner.Cpf);
        Assert.Equal("socia@example.invalid", partner.Email);
        Assert.Equal(ClientPartnerRoleModel.ManagingPartner, partner.Role);
        Assert.Empty(viewModel.Identifiers);
        Assert.Equal("socia@example.invalid", Assert.Single(viewModel.Recipients).Email);
    }

    [Fact]
    public void EmailTypedInPartnerCpfFieldStaysVisibleAndExplainsHowToCorrectIt()
    {
        var viewModel = new MainViewModel
        {
            NewPartnerName = "Representante sintética",
            NewPartnerCpf = "representante@example.invalid",
        };

        viewModel.AddPartnerCommand.Execute(null);

        Assert.Empty(viewModel.Partners);
        Assert.Equal("representante@example.invalid", viewModel.NewPartnerCpf);
        Assert.True(viewModel.HasPartnerInputError);
        Assert.Contains("e-mail no campo CPF", viewModel.PartnerInputError, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("E-mail do representante", viewModel.PartnerInputError, StringComparison.Ordinal);
    }

    [Fact]
    public void EditingCpfDoesNotHideAnUnresolvedPartnerEmailError()
    {
        var viewModel = new MainViewModel
        {
            NewPartnerName = "Representante sintética",
            NewPartnerCpf = "52998224725",
            NewPartnerEmail = "endereco-incompleto",
        };

        viewModel.AddPartnerCommand.Execute(null);
        var emailError = viewModel.PartnerInputError;

        viewModel.NewPartnerCpf = "529.982.247-25";

        Assert.Contains("e-mail", emailError, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(emailError, viewModel.PartnerInputError);
    }

    [Fact]
    public async Task OpeningClientClearsAnUncommittedRepresentativeDraft()
    {
        var timestamp = new DateTimeOffset(2026, 8, 25, 12, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Sintética Ltda.",
            "Empresa Sintética",
            null,
            "11222333000181",
            true,
            null,
            null,
            null,
            1,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []));
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService())
        {
            NewPartnerName = "Rascunho do cliente anterior",
            NewPartnerCpf = "representante@example.invalid",
            NewPartnerEmail = "representante@example.invalid",
            UsePartnerAsDeliveryContact = true,
        };
        viewModel.AddPartnerCommand.Execute(null);
        Assert.True(viewModel.HasPartnerInputError);

        await viewModel.ShowClientsCommand.ExecuteAsync(null);
        viewModel.SelectedClient = Assert.Single(viewModel.Clients);
        await viewModel.OpenSelectedClientCommand.ExecuteAsync(null);

        Assert.Equal(string.Empty, viewModel.NewPartnerName);
        Assert.Equal(string.Empty, viewModel.NewPartnerCpf);
        Assert.Equal(string.Empty, viewModel.NewPartnerEmail);
        Assert.False(viewModel.UsePartnerAsDeliveryContact);
        Assert.False(viewModel.HasPartnerInputError);
    }

    [Fact]
    public async Task SavingClientShowsInlineSuccessAndRefreshesTheSearchList()
    {
        var catalog = new InMemoryClientCatalog();
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService())
        {
            LegalName = "Empresa Sintética Ltda.",
            PreferredName = "Empresa Sintética",
            PrimaryTaxId = "11.222.333/0001-81",
        };

        await viewModel.SaveClientCommand.ExecuteAsync(null);

        Assert.True(viewModel.HasExistingClient);
        Assert.Single(viewModel.Clients);
        Assert.Contains("salvo com sucesso", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
        Assert.False(viewModel.ClientFeedbackIsError);
        Assert.Equal(1, catalog.SaveCount);
    }

    [Fact]
    public async Task InactivatingAndReactivatingPersistsImmediatelyWithoutMakingTheClientDisappear()
    {
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Sintética Ltda.",
            "Empresa Sintética",
            null,
            "11222333000181",
            true,
            null,
            null,
            null,
            1,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []));
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService());

        await viewModel.ShowClientsCommand.ExecuteAsync(null);
        viewModel.SelectedClient = Assert.Single(viewModel.Clients);
        await viewModel.OpenSelectedClientCommand.ExecuteAsync(null);
        await viewModel.ToggleClientActiveCommand.ExecuteAsync(null);

        Assert.False(viewModel.IsClientActive);
        Assert.True(viewModel.IncludeInactive);
        Assert.Equal("Inativo", viewModel.ClientStatusLabel);
        Assert.Equal("Reativar agora", viewModel.ClientStatusActionLabel);
        Assert.False((await catalog.GetAsync(clientId, CancellationToken.None))!.IsActive);
        Assert.False(Assert.Single(viewModel.Clients).IsActive);

        await viewModel.ToggleClientActiveCommand.ExecuteAsync(null);

        Assert.True(viewModel.IsClientActive);
        Assert.Equal("Ativo", viewModel.ClientStatusLabel);
        Assert.True((await catalog.GetAsync(clientId, CancellationToken.None))!.IsActive);
        Assert.Equal(2, catalog.SaveCount);
    }

    [Fact]
    public async Task StatusChangeStopsBusyFeedbackWhenEditorRefreshFailsAfterPersistence()
    {
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Sintética Ltda.",
            "Empresa Sintética",
            null,
            "11222333000181",
            true,
            null,
            null,
            null,
            1,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []))
        {
            FailReadinessAfterStatusChange = true,
        };
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService());

        await viewModel.ShowClientsCommand.ExecuteAsync(null);
        viewModel.SelectedClient = Assert.Single(viewModel.Clients);
        await viewModel.OpenSelectedClientCommand.ExecuteAsync(null);
        await viewModel.ToggleClientActiveCommand.ExecuteAsync(null);

        Assert.False(viewModel.IsSavingClient);
        Assert.False(viewModel.IsClientActive);
        Assert.False((await catalog.GetAsync(clientId, CancellationToken.None))!.IsActive);
        Assert.DoesNotContain("Inativando", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("foi inativado", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("não pôde ser atualizada", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task OpeningLegacyClientWithIncompleteEmailExplainsTheRequiredCorrection()
    {
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Legada Sintética Ltda.",
            "Empresa Legada",
            null,
            "11222333000181",
            true,
            null,
            null,
            null,
            1,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []))
        {
            ReadinessBlockCodes =
            [
                ClientBlockCodes.RecipientEmailInvalid,
                ClientBlockCodes.NoActiveToRecipient,
            ],
        };
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService());

        await viewModel.ShowClientsCommand.ExecuteAsync(null);
        viewModel.SelectedClient = Assert.Single(viewModel.Clients);
        await viewModel.OpenSelectedClientCommand.ExecuteAsync(null);

        Assert.True(viewModel.HasExistingClient);
        Assert.Contains("corrija", viewModel.ReadinessMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("e-mail", viewModel.ReadinessMessage, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("RECIPIENT_EMAIL_INVALID", viewModel.TechnicalDetails, StringComparison.Ordinal);
    }

    [Fact]
    public async Task DuplicateTaxIdRecoversTheInactiveRecordInsteadOfPretendingItWasDeleted()
    {
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Sintética Ltda.",
            "Empresa Sintética",
            null,
            "11222333000181",
            false,
            null,
            null,
            null,
            3,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []));
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService())
        {
            LegalName = "Tentativa de recadastro",
            PrimaryTaxId = "11.222.333/0001-81",
        };

        await viewModel.SaveClientCommand.ExecuteAsync(null);

        Assert.Equal(0, catalog.SaveCount);
        Assert.True(viewModel.HasExistingClient);
        Assert.False(viewModel.IsClientActive);
        Assert.True(viewModel.IncludeInactive);
        Assert.Equal(clientId, Assert.Single(viewModel.Clients).Id);
        Assert.Contains("cadastro inativo", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData("search")]
    [InlineData("load")]
    [InlineData("refresh")]
    public async Task DuplicateRecoveryFailureKeepsTheDraftAndShowsFriendlyFeedback(string failureStage)
    {
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            Guid.NewGuid(),
            PersonTypeModel.LegalEntity,
            "Empresa já cadastrada Ltda.",
            "Empresa já cadastrada",
            null,
            "11222333000181",
            false,
            null,
            null,
            null,
            3,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []))
        {
            FailOnSearchCall = failureStage switch
            {
                "search" => 1,
                "refresh" => 2,
                _ => null,
            },
            FailOnGet = failureStage == "load",
        };
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService())
        {
            LegalName = "Rascunho que deve permanecer",
            PrimaryTaxId = "11.222.333/0001-81",
        };

        await viewModel.SaveClientCommand.ExecuteAsync(null);

        Assert.False(viewModel.HasExistingClient);
        Assert.False(viewModel.IncludeInactive);
        Assert.Empty(viewModel.Clients);
        Assert.Equal("Rascunho que deve permanecer", viewModel.LegalName);
        Assert.Equal("11.222.333/0001-81", viewModel.PrimaryTaxId);
        Assert.Equal(0, catalog.SaveCount);
        Assert.True(viewModel.ClientFeedbackIsError);
        Assert.Contains("dados digitados foram mantidos", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task TemplateEditorAcceptsBodyAboveOneHundredSixtyAndBlocksUnknownFields()
    {
        var catalog = new InMemoryClientCatalog();
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService())
        {
            LegalName = "Empresa Sintética Ltda.",
            PrimaryTaxId = "11.222.333/0001-81",
        };
        await viewModel.SaveClientCommand.ExecuteAsync(null);
        viewModel.TemplateName = "Mensagem mensal";
        viewModel.TemplateSubject = "Documentos de {{periodo.rotulo}}";
        viewModel.TemplateBody = new string('x', 161) + " {{documentos.lista}}";

        await viewModel.SaveTemplateCommand.ExecuteAsync(null);

        Assert.Equal(1, catalog.TemplateSaveCount);
        Assert.NotNull(catalog.LastTemplateRequest);
        Assert.True(catalog.LastTemplateRequest.BodyTemplate.Length > 160);
        Assert.Contains("criada", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);

        viewModel.TemplateName = "Mensagem inválida";
        viewModel.TemplateSubject = "Assunto";
        viewModel.TemplateBody = "Olá {{campo.inventado}}";
        await viewModel.SaveTemplateCommand.ExecuteAsync(null);

        Assert.Equal(1, catalog.TemplateSaveCount);
        Assert.Contains("desconhecidas", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task StandardTemplatesFillTheMessageAndVariablesCanTargetTheSubject()
    {
        var catalog = new InMemoryClientCatalog();
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService())
        {
            LegalName = "Empresa Sintética Ltda.",
            PrimaryTaxId = "11.222.333/0001-81",
        };
        await viewModel.SaveClientCommand.ExecuteAsync(null);

        viewModel.ApplyLegalEntityStandardTemplateCommand.Execute(null);

        Assert.Equal("Padrão para empresa", viewModel.TemplateName);
        Assert.Contains("{{cliente.nome_preferencia_ou_razao_social}}", viewModel.TemplateSubject, StringComparison.Ordinal);
        Assert.Contains("{{documentos.lista}}", viewModel.TemplateBody, StringComparison.Ordinal);

        viewModel.SelectedTemplatePlaceholderTarget = viewModel.TemplatePlaceholderTargetOptions.Single(
            option => option.Key == "subject");
        viewModel.InsertDueDatesPlaceholderCommand.Execute(null);

        Assert.Contains("{{vencimentos.lista}}", viewModel.TemplateSubject, StringComparison.Ordinal);
        Assert.Contains("assunto", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task MonthlyReportUsesIndependentSelectorsAndRequiresAValidMonthAndYear()
    {
        var dispatch = new RecordingDispatchService(DispatchWorkspace.Empty("ui-test"));
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            dispatch);
        viewModel.ShowAllPeriodsCommand.Execute(null);
        viewModel.SelectedReportYear = null;

        await viewModel.ExportDispatchReportsCommand.ExecuteAsync(null);

        Assert.False(viewModel.CanExportSelectedPeriodReport);
        Assert.Contains("ano e um mês válidos", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task OpeningReportsDirectlyOffersInactiveClientsWithoutDependingOnTheClientsScreen()
    {
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Histórica Ltda.",
            "Empresa Histórica",
            null,
            "11222333000181",
            false,
            null,
            null,
            null,
            3,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []));
        var viewModel = new MainViewModel(
            catalog,
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            new RecordingDispatchService(DispatchWorkspace.Empty("ui-test")));

        await viewModel.ShowReportsCommand.ExecuteAsync(null);

        Assert.Empty(viewModel.Clients);
        var reportClient = Assert.Single(viewModel.ReportClients);
        Assert.Equal(clientId, reportClient.Id);
        Assert.Equal("Empresa Histórica", reportClient.DisplayName);
        Assert.False(reportClient.IsActive);
    }

    [Fact]
    public void DispatchSimulationStatesClearlyThatNoEmailLeavesTheComputer()
    {
        var viewModel = new MainViewModel();

        Assert.True(viewModel.IsLocalEmailSimulation);
        Assert.Contains("nenhum e-mail", viewModel.DispatchSafetyTitle, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("somente neste aplicativo", viewModel.DispatchSafetyMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData("fake.local", "fake://local", "google.gmail", true)]
    [InlineData("google.gmail", "google-gmail://me", "fake.local", false)]
    public async Task HistoricalDispatchOutcomeUsesItsRecordedProviderInsteadOfTheCurrentConnection(
        string recordedProvider,
        string senderAccountId,
        string currentProvider,
        bool expectedSimulation)
    {
        var service = new RecordingDispatchService(CreateCompletedDispatchWorkspace(recordedProvider, senderAccountId));
        var connection = new StaticEmailAccountConnectionService(new EmailAccountConnectionStatus(
            currentProvider,
            true,
            currentProvider != DispatchWorkflowOptions.FakeProviderKey,
            currentProvider == DispatchWorkflowOptions.FakeProviderKey ? "fake://local" : "connected@example.invalid",
            "Conta sintética",
            null));
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            service,
            connection);

        await viewModel.LoadReviewWorkspaceAsync();

        if (expectedSimulation)
        {
            Assert.Contains("Simulação local concluída", viewModel.DispatchOutcomeTitle, StringComparison.Ordinal);
            Assert.Contains("nenhum e-mail", viewModel.DispatchSafetyTitle, StringComparison.OrdinalIgnoreCase);
        }
        else
        {
            Assert.Contains("Solicitação aceita", viewModel.DispatchOutcomeTitle, StringComparison.Ordinal);
            Assert.DoesNotContain("nenhum e-mail", viewModel.DispatchSafetyTitle, StringComparison.OrdinalIgnoreCase);
            Assert.Contains("não confirmada", viewModel.DispatchOutcomeMessage, StringComparison.OrdinalIgnoreCase);
        }
    }

    [Fact]
    public async Task TogglingTheTemplateBeingEditedDoesNotRevertItsStatusOnSave()
    {
        var catalog = new InMemoryClientCatalog();
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService())
        {
            LegalName = "Empresa Sintética Ltda.",
            PrimaryTaxId = "11.222.333/0001-81",
        };
        await viewModel.SaveClientCommand.ExecuteAsync(null);
        viewModel.TemplateName = "Mensagem mensal";
        viewModel.TemplateSubject = "Documentos de {{periodo.rotulo}}";
        viewModel.TemplateBody = "Olá {{contato.nome}}. {{documentos.lista}}";
        await viewModel.SaveTemplateCommand.ExecuteAsync(null);

        viewModel.EditSelectedTemplateCommand.Execute(null);
        await viewModel.ToggleTemplateActiveCommand.ExecuteAsync(null);
        viewModel.TemplateBody = "Corpo atualizado, ainda inativo. {{documentos.lista}}";
        await viewModel.SaveTemplateCommand.ExecuteAsync(null);

        Assert.Equal(3, catalog.TemplateSaveCount);
        Assert.NotNull(catalog.LastTemplateRequest);
        Assert.False(catalog.LastTemplateRequest.IsActive);
        Assert.False(Assert.Single(viewModel.Templates).IsActive);
    }

    [Fact]
    public async Task OpeningAndSavingClientPreservesDefaultTemplates()
    {
        var subjectTemplateId = Guid.NewGuid();
        var bodyTemplateId = Guid.NewGuid();
        var clientId = Guid.NewGuid();
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var catalog = new InMemoryClientCatalog(new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Sintética Ltda.",
            "Empresa Sintética",
            null,
            "11222333000181",
            true,
            subjectTemplateId,
            bodyTemplateId,
            null,
            4,
            timestamp,
            timestamp,
            [],
            [],
            [],
            []));
        var viewModel = new MainViewModel(
            catalog,
            new UnusedRecognitionService(),
            new EmptyReviewService());

        await viewModel.ShowClientsCommand.ExecuteAsync(null);
        viewModel.SelectedClient = Assert.Single(viewModel.Clients);
        await viewModel.OpenSelectedClientCommand.ExecuteAsync(null);
        viewModel.PreferredName = "Empresa Sintética Atualizada";
        await viewModel.SaveClientCommand.ExecuteAsync(null);

        var saved = await catalog.GetAsync(clientId, CancellationToken.None);
        Assert.NotNull(saved);
        Assert.Equal(subjectTemplateId, saved.DefaultSubjectTemplateId);
        Assert.Equal(bodyTemplateId, saved.DefaultBodyTemplateId);
    }

    [Fact]
    public async Task EmptyClientShowsValidationAndDoesNotCallPersistence()
    {
        var catalog = new InMemoryClientCatalog();
        var viewModel = new MainViewModel(catalog, new UnusedRecognitionService(), new EmptyReviewService());

        await viewModel.SaveClientCommand.ExecuteAsync(null);

        Assert.Equal(0, catalog.SaveCount);
        Assert.True(viewModel.ClientFeedbackIsError);
        Assert.Contains("razão social", viewModel.ClientFeedbackMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void OperationalPeriodStartsSelectedAndHasAnUnassignedMonthOption()
    {
        var viewModel = new MainViewModel();

        Assert.NotNull(viewModel.SelectedOperationalYear?.Year);
        Assert.InRange(viewModel.SelectedOperationalMonth?.Month ?? 0, 1, 12);
        Assert.Contains(viewModel.OperationalMonths, item => item.Month == 0);
        Assert.DoesNotContain("Todos os períodos", viewModel.WorkPeriodLabel, StringComparison.Ordinal);
    }

    [Fact]
    public void SelectedDocumentExplainsTheResolvedClientAndAutomaticOrganization()
    {
        var viewModel = new MainViewModel();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);
        var now = new DateTimeOffset(2026, 8, 23, 12, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var documentId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var period = new DocumentPeriod(DocumentPeriodKind.Monthly, 8, 2026, null, null, null, "08/2026");
        var document = new ReviewDocument(
            documentId,
            "/synthetic/folha.pdf",
            "folha-sintetica.pdf",
            new string('A', 64),
            1_024,
            1,
            RecognizedDocumentType.Payroll,
            "ui-test",
            clientId,
            null,
            "Empresa Sintética",
            "**.***.***/****-81",
            ClientResolutionMethod.ExactClientTaxId,
            .99m,
            [],
            [],
            [],
            [],
            period,
            "synthetic-key",
            ReviewDocumentState.Grouped,
            1,
            groupId,
            [],
            now,
            now);
        var group = new DocumentDispatchGroup(
            groupId,
            "synthetic-group",
            "monthly",
            "1",
            clientId,
            null,
            "Empresa Sintética",
            period.GroupingPeriodKey,
            period.DisplayLabel,
            ReviewGroupState.ReadyForReview,
            1,
            [documentId],
            [],
            null,
            now,
            now);
        viewModel.ReviewGroups.Add(group);
        viewModel.ReviewDocuments.Add(document);

        viewModel.SelectedReviewDocument = document;

        Assert.Contains("Cliente identificado", viewModel.SelectedDocumentClientSummary, StringComparison.Ordinal);
        Assert.Contains("Empresa Sintética", viewModel.SelectedDocumentClientSummary, StringComparison.Ordinal);
        Assert.Contains("****-81", viewModel.SelectedDocumentClientSummary, StringComparison.Ordinal);
        Assert.Contains("Documento principal do cliente", viewModel.SelectedDocumentRecognitionMethodSummary, StringComparison.Ordinal);
        Assert.Contains("organizado automaticamente", viewModel.SelectedDocumentGroupingSummary, StringComparison.OrdinalIgnoreCase);
        Assert.Equal("Pronto para revisão e aprovação", viewModel.SelectedReviewGroupStatusSummary);
        Assert.DoesNotContain("GroupingKey", viewModel.SelectedDocumentGroupingSummary, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void UnresolvedDocumentNeverClaimsThatAClientWasIdentified()
    {
        var viewModel = new MainViewModel();
        var now = new DateTimeOffset(2026, 8, 23, 12, 0, 0, TimeSpan.Zero);
        viewModel.SelectedReviewDocument = new ReviewDocument(
            Guid.NewGuid(),
            "/synthetic/unresolved.pdf",
            "unresolved.pdf",
            new string('B', 64),
            1_024,
            1,
            RecognizedDocumentType.Payroll,
            "ui-test",
            null,
            null,
            null,
            null,
            ClientResolutionMethod.None,
            0m,
            [],
            ["client.not_resolved"],
            [],
            [],
            DocumentPeriod.Unknown(),
            string.Empty,
            ReviewDocumentState.Blocked,
            1,
            null,
            [],
            now,
            now);

        Assert.Contains("não identificado", viewModel.SelectedDocumentClientSummary, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Cliente identificado:", viewModel.SelectedDocumentClientSummary, StringComparison.Ordinal);
        Assert.Contains("Nenhuma associação", viewModel.SelectedDocumentRecognitionMethodSummary, StringComparison.Ordinal);
        Assert.Contains("Aguardando", viewModel.SelectedReviewGroupStatusSummary, StringComparison.Ordinal);
        Assert.False(viewModel.IsDocumentCorrectionPanelExpanded);
        Assert.False(viewModel.IsDocumentOrganizationPanelExpanded);
        Assert.False(viewModel.IsDocumentRecognitionPanelExpanded);
    }

    [Fact]
    public async Task OperationalPeriodRemainsGlobalAndPersistsAcrossRestart()
    {
        var selectedYear = DateTimeOffset.Now.Year - 1;
        var preferencesStore = new InMemoryWorkspacePreferencesStore();
        var viewModel = CreateViewModel(preferencesStore);
        await viewModel.LoadReviewWorkspaceAsync();

        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == selectedYear);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 3);

        viewModel.ShowDocumentsCommand.Execute(null);
        viewModel.ShowDispatchCommand.Execute(null);
        await viewModel.ShowReportsCommand.ExecuteAsync(null);
        viewModel.ShowHistoryCommand.Execute(null);
        viewModel.ShowSettingsCommand.Execute(null);
        viewModel.ShowHomeCommand.Execute(null);

        Assert.Equal(selectedYear, viewModel.SelectedOperationalYear?.Year);
        Assert.Equal(3, viewModel.SelectedOperationalMonth?.Month);
        await viewModel.SetInputFolderAsync("pasta-sintetica", CancellationToken.None);

        var restarted = CreateViewModel(preferencesStore);
        await restarted.LoadReviewWorkspaceAsync();

        Assert.Equal(selectedYear, restarted.SelectedOperationalYear?.Year);
        Assert.Equal(3, restarted.SelectedOperationalMonth?.Month);
        Assert.Contains(selectedYear.ToString(System.Globalization.CultureInfo.InvariantCulture), restarted.WorkPeriodLabel, StringComparison.Ordinal);

        restarted.ShowAllPeriodsCommand.Execute(null);
        await restarted.SetInputFolderAsync("pasta-sintetica", CancellationToken.None);

        var restartedWithAllPeriods = CreateViewModel(preferencesStore);
        await restartedWithAllPeriods.LoadReviewWorkspaceAsync();
        var allYears = Assert.Single(restartedWithAllPeriods.OperationalYears, option => option.Year is null);

        Assert.Same(allYears, restartedWithAllPeriods.SelectedOperationalYear);
        Assert.Equal("Todos os anos", allYears.ToString());
        Assert.Equal("Todos os períodos", restartedWithAllPeriods.WorkPeriodLabel);
    }

    [Fact]
    public async Task RepeatedWorkspaceRefreshRepairsDuplicatedYearsAndKeepsAllSelected()
    {
        var viewModel = CreateViewModel();
        var duplicatedYear = DateTimeOffset.Now.Year;
        viewModel.OperationalYears.Add(new OperationalYearOption(duplicatedYear, duplicatedYear.ToString(
            System.Globalization.CultureInfo.InvariantCulture)));
        viewModel.ShowAllPeriodsCommand.Execute(null);

        for (var refresh = 0; refresh < 5; refresh++)
        {
            await viewModel.LoadReviewWorkspaceAsync();
        }

        Assert.Equal(
            viewModel.OperationalYears.Count,
            viewModel.OperationalYears.Select(option => option.Year).Distinct().Count());
        Assert.Single(viewModel.OperationalYears, option => option.Year == duplicatedYear);
        var allYears = Assert.Single(viewModel.OperationalYears, option => option.Year is null);
        Assert.Same(allYears, viewModel.SelectedOperationalYear);
        Assert.Equal("Todos os anos", allYears.Label);
    }

    [Fact]
    public void AllYearsWithOneMonthHasAnAccurateFriendlyLabel()
    {
        var viewModel = new MainViewModel
        {
            SelectedOperationalYear = OperationalYearOption.All,
        };
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);

        Assert.Contains("AGOSTO", viewModel.WorkPeriodLabel, StringComparison.Ordinal);
        Assert.Contains("todos os anos", viewModel.WorkPeriodLabel, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task AllYearsWithOneMonthFiltersHistoryByThatMonth()
    {
        var review = new PeriodBatchReviewService();
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            review);
        await viewModel.LoadReviewWorkspaceAsync();

        viewModel.SelectedOperationalYear = OperationalYearOption.All;
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);

        var visible = Assert.Single(viewModel.VisibleReviewAuditEvents);
        Assert.Equal(review.AugustAuditId, visible.Id);
        Assert.DoesNotContain(viewModel.VisibleReviewAuditEvents, item => item.Id == review.SeptemberAuditId);
    }

    [Fact]
    public async Task HistoryCleanupHidesOnlyTheViewPersistsAndCanBeRestored()
    {
        var review = new PeriodBatchReviewService();
        var preferencesStore = new InMemoryWorkspacePreferencesStore();
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            review,
            null,
            null,
            preferencesStore);
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(item => item.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(item => item.Month == 8);
        viewModel.SelectedHistoryCleanupScope = viewModel.HistoryCleanupScopes.Single(item =>
            item.Value == HistoryVisibilityRuleKind.SelectedOperationalPeriod);

        await viewModel.ArchiveHistoryViewCommand.ExecuteAsync(null);

        Assert.Equal(2, viewModel.ReviewAuditEvents.Count);
        Assert.Empty(viewModel.VisibleReviewAuditEvents);
        Assert.Equal(1, viewModel.HiddenHistoryEventCount);
        var savedRule = Assert.Single(preferencesStore.SavedPreferences?.HistoryVisibility?.Rules ?? []);
        Assert.Equal(HistoryVisibilityRuleKind.SelectedOperationalPeriod, savedRule.Kind);
        Assert.Equal([review.AugustAuditId], savedRule.EventIds);
        Assert.Contains("preservados", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);

        viewModel.ShowHiddenHistory = true;
        Assert.Equal(review.AugustAuditId, Assert.Single(viewModel.VisibleReviewAuditEvents).Id);

        var restarted = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            review,
            null,
            null,
            preferencesStore);
        await restarted.LoadReviewWorkspaceAsync();

        Assert.Equal(2, restarted.ReviewAuditEvents.Count);
        Assert.Empty(restarted.VisibleReviewAuditEvents);
        Assert.Equal(1, restarted.HiddenHistoryEventCount);

        await restarted.RestoreHistoryViewCommand.ExecuteAsync(null);

        Assert.Equal(review.AugustAuditId, Assert.Single(restarted.VisibleReviewAuditEvents).Id);
        Assert.Empty(preferencesStore.SavedPreferences?.HistoryVisibility?.Rules ?? []);
        Assert.Contains("voltou", restarted.StatusMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task MultiPeriodImportUsesRecognizedFoldersWithoutChangingTheGlobalSelection()
    {
        var temporaryDirectory = Path.Combine(Path.GetTempPath(), "folhas-ui-import", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temporaryDirectory);
        try
        {
            var august = Path.Combine(temporaryDirectory, "agosto.pdf");
            var september = Path.Combine(temporaryDirectory, "setembro.pdf");
            await File.WriteAllTextAsync(august, "%PDF- documento sintetico agosto");
            await File.WriteAllTextAsync(september, "%PDF- documento sintetico setembro");
            var review = new ImportingReviewService();
            var viewModel = new MainViewModel(
                new InMemoryClientCatalog(),
                new PeriodRecognitionService(),
                review)
            {
                DocumentArchiveDirectory = Path.Combine(temporaryDirectory, "acervo"),
            };
            viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(item => item.Year == 2026);
            viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(item => item.Month == 8);

            await viewModel.ImportDocumentsAsync([august, september]);

            Assert.Equal(2026, viewModel.SelectedOperationalYear?.Year);
            Assert.Equal(8, viewModel.SelectedOperationalMonth?.Month);
            Assert.Single(viewModel.VisibleReviewDocuments);
            Assert.Equal(1, viewModel.HiddenReviewDocumentCount);
            Assert.Contains("2 competências", viewModel.DocumentImportSummary, StringComparison.Ordinal);
            Assert.Contains("foi mantida", viewModel.DocumentImportSummary, StringComparison.OrdinalIgnoreCase);
            Assert.Contains(review.Workspace.Documents, document =>
                document.LocalPath.Contains(Path.Combine("2026", "08"), StringComparison.Ordinal));
            Assert.Contains(review.Workspace.Documents, document =>
                document.LocalPath.Contains(Path.Combine("2026", "09"), StringComparison.Ordinal));

            viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(item => item.Year == 2026);
            viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(item => item.Month == 10);
            viewModel.ShowHomeCommand.Execute(null);
            viewModel.ShowDocumentsCommand.Execute(null);

            Assert.Equal(2026, viewModel.SelectedOperationalYear?.Year);
            Assert.Equal(10, viewModel.SelectedOperationalMonth?.Month);
            Assert.Empty(viewModel.VisibleReviewDocuments);
            Assert.Equal(2, viewModel.HiddenReviewDocumentCount);
            Assert.Contains("Mostrar todos", viewModel.StatusMessage, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(temporaryDirectory, true);
        }
    }

    [Fact]
    public async Task MixedInputFolderImportsPdfAndReportsUnsupportedFilesPreservedInPlace()
    {
        var temporaryDirectory = Path.Combine(Path.GetTempPath(), "folhas-ui-mixed-folder", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temporaryDirectory);
        try
        {
            var pdf = Path.Combine(temporaryDirectory, "agosto.pdf");
            var docx = Path.Combine(temporaryDirectory, "folha.docx");
            var xlsx = Path.Combine(temporaryDirectory, "resumo.xlsx");
            var unsupported = Path.Combine(temporaryDirectory, "leia-me.txt");
            await File.WriteAllTextAsync(pdf, "%PDF- documento sintetico agosto");
            await File.WriteAllTextAsync(docx, "documento Word sintético");
            await File.WriteAllTextAsync(xlsx, "planilha sintética");
            await File.WriteAllTextAsync(unsupported, "formato não compatível sintético");
            var viewModel = new MainViewModel(
                new InMemoryClientCatalog(),
                new PeriodRecognitionService(),
                new ImportingReviewService())
            {
                InputFolderPath = temporaryDirectory,
                DocumentArchiveDirectory = Path.Combine(temporaryDirectory, "acervo"),
            };

            await viewModel.ImportInputFolderAsync();

            Assert.Single(viewModel.RecognizedDocuments);
            Assert.Contains("3 arquivos não compatíveis", viewModel.DocumentImportSummary, StringComparison.Ordinal);
            Assert.Contains("DOCX: 1", viewModel.DocumentImportSummary, StringComparison.Ordinal);
            Assert.Contains("XLSX: 1", viewModel.DocumentImportSummary, StringComparison.Ordinal);
            Assert.Contains("TXT: 1", viewModel.DocumentImportSummary, StringComparison.Ordinal);
            Assert.Contains("recusado", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
            Assert.True(File.Exists(docx));
            Assert.True(File.Exists(xlsx));
            Assert.True(File.Exists(unsupported));
        }
        finally
        {
            Directory.Delete(temporaryDirectory, true);
        }
    }

    [Fact]
    public async Task FailedReviewPersistenceRemovesTheStagedPdfAndKeepsTheUiConsistent()
    {
        var temporaryDirectory = Path.Combine(Path.GetTempPath(), "folhas-ui-import-failure", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temporaryDirectory);
        try
        {
            var source = Path.Combine(temporaryDirectory, "agosto-falha.pdf");
            await File.WriteAllTextAsync(source, "%PDF- documento sintetico com falha de persistencia");
            var archive = Path.Combine(temporaryDirectory, "acervo");
            var viewModel = new MainViewModel(
                new InMemoryClientCatalog(),
                new PeriodRecognitionService(),
                new FailingImportReviewService())
            {
                DocumentArchiveDirectory = archive,
            };

            await viewModel.ImportDocumentsAsync([source]);

            Assert.Empty(viewModel.RecognizedDocuments);
            Assert.Empty(viewModel.ReviewDocuments);
            Assert.False(Directory.Exists(archive) && Directory.EnumerateFiles(
                archive,
                "*.pdf",
                SearchOption.AllDirectories).Any());
            Assert.Contains("1 rejeitado", viewModel.DocumentImportSummary, StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            Directory.Delete(temporaryDirectory, true);
        }
    }

    [Fact]
    public void ApplicationIconAndReleaseMetadataArePreparedForWindowsAndMacOs()
    {
        var root = FindRepositoryRoot();
        var branding = Path.Combine(root, "src", "FolhasDaMichelly.Desktop", "Assets", "Branding");
        var plist = File.ReadAllText(Path.Combine(root, "src", "FolhasDaMichelly.Desktop", "Info.plist"));
        var releaseWorkflow = File.ReadAllText(Path.Combine(root, ".github", "workflows", "release.yml"));

        Assert.True(File.Exists(Path.Combine(branding, "app-icon.ico")));
        Assert.True(File.Exists(Path.Combine(branding, "app-icon.icns")));
        Assert.Contains("CFBundleIconFile", plist, StringComparison.Ordinal);
        Assert.Contains($"<string>{AppVersionInfo.Baseline}</string>", plist, StringComparison.Ordinal);
        Assert.Contains($"default: {AppVersionInfo.Baseline}", releaseWorkflow, StringComparison.Ordinal);
    }

    [Fact]
    public void LateralNavigationChangesTheVisibleJourney()
    {
        var viewModel = new MainViewModel();

        viewModel.ShowDocumentsCommand.Execute(null);

        Assert.Equal(AppSection.Documents, viewModel.CurrentSection);
        Assert.True(viewModel.IsDocumentsSection);
        Assert.False(viewModel.IsHomeSection);
        Assert.Equal("Documentos", viewModel.SectionTitle);
    }

    [Fact]
    public async Task FailedDispatchCanRetryOnlyWhenTheLatestAttemptWasTransient()
    {
        var transientService = new RecordingDispatchService(CreateFailedDispatchWorkspace(
            DeliveryAttemptState.FailedTransient));
        var transientViewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            transientService);
        await transientViewModel.LoadReviewWorkspaceAsync();

        Assert.True(transientViewModel.CanExecuteSelectedDispatch);
        await transientViewModel.ExecuteSelectedDispatchCommand.ExecuteAsync(null);
        Assert.Equal(1, transientService.ExecuteCount);

        var permanentService = new RecordingDispatchService(CreateFailedDispatchWorkspace(
            DeliveryAttemptState.FailedPermanent));
        var permanentViewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            permanentService);
        await permanentViewModel.LoadReviewWorkspaceAsync();

        Assert.False(permanentViewModel.CanExecuteSelectedDispatch);
        await permanentViewModel.ExecuteSelectedDispatchCommand.ExecuteAsync(null);
        Assert.Equal(0, permanentService.ExecuteCount);
        Assert.Contains("falha temporária", permanentViewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task BatchActionsUseEligibleItemsFromTheWholeSelectedPeriodBatch()
    {
        var service = new RecordingBatchDispatchService(CreateMixedDispatchBatchWorkspace());
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            service);
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(item => item.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(item => item.Month == 8);
        viewModel.SelectedDispatchItem = viewModel.DispatchItems.Single(item => item.State == DispatchItemState.Blocked);

        Assert.False(viewModel.CanApproveSelectedDispatch);
        Assert.True(viewModel.CanApproveSelectedDispatchBatch);
        Assert.False(viewModel.CanExecuteSelectedDispatch);
        Assert.True(viewModel.CanExecuteSelectedDispatchBatch);

        await viewModel.ApproveSelectedDispatchBatchCommand.ExecuteAsync(null);

        Assert.Equal(1, service.ApproveBatchCount);
        Assert.False(viewModel.CanApproveSelectedDispatchBatch);
        Assert.True(viewModel.CanExecuteSelectedDispatchBatch);

        await viewModel.ExecuteSelectedDispatchBatchCommand.ExecuteAsync(null);

        Assert.Equal(1, service.ExecuteBatchCount);
        Assert.False(viewModel.CanExecuteSelectedDispatchBatch);
        Assert.Equal(
            DispatchItemState.Blocked,
            viewModel.DispatchItems.Single(item => item.ClientDisplayName == "Cliente com pendência").State);
        Assert.All(
            viewModel.DispatchItems.Where(item => item.ClientDisplayName != "Cliente com pendência"),
            item => Assert.Equal(DispatchItemState.AcceptedByProvider, item.State));
    }

    [Fact]
    public async Task BatchSendShowsAndUsesItsOwnAggregatedConfirmationPhrase()
    {
        var service = new RecordingBatchDispatchService(CreateSendDispatchBatchWorkspace());
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            service);
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(item => item.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(item => item.Month == 8);
        viewModel.SelectedDispatchItem = viewModel.DispatchItems.First();

        Assert.True(viewModel.ShowDispatchBatchConfirmation);
        Assert.Contains("CONFIRMAR LOTE 2 3", viewModel.DispatchBatchConfirmationGuidance, StringComparison.Ordinal);
        viewModel.SendConfirmationPhrase = "CONFIRMAÇÃO INDIVIDUAL";
        viewModel.BatchSendConfirmationPhrase = "CONFIRMAR LOTE 2 3";

        await viewModel.ExecuteSelectedDispatchBatchCommand.ExecuteAsync(null);

        Assert.Equal("CONFIRMAR LOTE 2 3", service.LastConfirmationPhrase);
        Assert.Equal(1, service.ExecuteBatchCount);
    }

    [Fact]
    public async Task BulkApprovalRequiresOneMonthAndNeverApprovesHiddenCompetencies()
    {
        var review = new PeriodBatchReviewService();
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            review);
        await viewModel.LoadReviewWorkspaceAsync();

        viewModel.ShowAllPeriodsCommand.Execute(null);
        await viewModel.ApproveAllEligibleCommand.ExecuteAsync(null);
        Assert.Empty(review.ApprovedGroupIds);
        Assert.Contains("único mês", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);

        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(item => item.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(item => item.Month == 8);
        await viewModel.ApproveAllEligibleCommand.ExecuteAsync(null);

        Assert.Equal([review.AugustGroupId], review.ApprovedGroupIds);
        Assert.Equal(1, review.ApproveGroupsCallCount);
        Assert.DoesNotContain(review.SeptemberGroupId, review.ApprovedGroupIds);
        Assert.Contains("AGOSTO 2026", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task ApplicationUpdateRemainsManualFromCheckThroughRestart()
    {
        var updateService = new FakeAppUpdateService();
        var viewModel = new MainViewModel(updateService);

        await viewModel.CheckForApplicationUpdatesCommand.ExecuteAsync(null);

        Assert.Equal(AppUpdateChannel.Stable, updateService.CheckedChannel);
        Assert.True(viewModel.CanDownloadApplicationUpdate);
        Assert.False(viewModel.CanApplyApplicationUpdate);
        Assert.Equal(0, updateService.ApplyCount);

        await viewModel.DownloadApplicationUpdateCommand.ExecuteAsync(null);

        Assert.False(viewModel.CanDownloadApplicationUpdate);
        Assert.True(viewModel.CanApplyApplicationUpdate);
        Assert.Equal(0, updateService.ApplyCount);

        viewModel.ApplyApplicationUpdateCommand.Execute(null);

        Assert.Equal(1, updateService.ApplyCount);
    }

    [Fact]
    public async Task ChangingReleaseChannelInvalidatesPreviouslyAvailableUpdate()
    {
        var updateService = new FakeAppUpdateService();
        var viewModel = new MainViewModel(updateService);
        await viewModel.CheckForApplicationUpdatesCommand.ExecuteAsync(null);
        Assert.True(viewModel.CanDownloadApplicationUpdate);

        viewModel.SelectedReleaseChannel = Assert.Single(
            viewModel.ReleaseChannels,
            item => item.Value == AppUpdateChannel.Beta);

        Assert.False(viewModel.CanDownloadApplicationUpdate);
        Assert.False(viewModel.CanApplyApplicationUpdate);
        Assert.Contains("Beta", viewModel.AppUpdateSummary, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(DispatchOperationMode.Test, "Teste seguro")]
    [InlineData(DispatchOperationMode.Draft, "Salvar como rascunho")]
    [InlineData(DispatchOperationMode.Send, "Enviar aos destinatários")]
    [InlineData(ReviewDocumentState.Duplicate, "Documento repetido")]
    [InlineData(ValidationSeverity.Blocker, "Ação obrigatória")]
    public void TechnicalEnumsHaveFriendlyLabels(object value, string expected)
    {
        Assert.Equal(expected, FriendlyTextConverter.ToFriendlyText(value));
    }

    [Fact]
    public void ApprovedDocumentStateUsesCustomerLanguage()
    {
        var workspace = new PeriodBatchReviewService();
        Assert.DoesNotContain("snapshot", workspace.ApprovedDocumentStateSummary, StringComparison.OrdinalIgnoreCase);
        Assert.Equal("Aprovado para preparar a mensagem", workspace.ApprovedDocumentStateSummary);
    }

    [Theory]
    [InlineData("document.period_corrected", "Competência corrigida")]
    [InlineData("document.removed_from_review", "Documento retirado da revisão")]
    [InlineData("document.grouped", "Documento incluído em um conjunto")]
    [InlineData("group.approved", "Conjunto liberado para mensagem")]
    [InlineData("group.approval_invalidated", "Aprovação revogada após alteração")]
    [InlineData("dispatch_bulk_approved", "Mensagens prontas do mês aprovadas")]
    [InlineData("Snapshot de conteúdo e agrupamento; não autoriza envio de e-mail.", "Conteúdo e organização registrados; isto ainda não envia e-mail.")]
    [InlineData("unauthenticated-operator", "Operador local")]
    public void TechnicalAuditActionsHaveCustomerFriendlyLabels(string value, string expected)
    {
        Assert.Equal(expected, FriendlyTextConverter.ToFriendlyText(value));
    }

    [Fact]
    public void ApprovedGroupSummaryDoesNotExposeTechnicalActorIdentifier()
    {
        var timestamp = new DateTimeOffset(2026, 8, 23, 12, 0, 0, TimeSpan.Zero);
        var group = new DocumentDispatchGroup(
            Guid.NewGuid(),
            "synthetic-key",
            "synthetic-policy",
            "1",
            Guid.NewGuid(),
            null,
            "Empresa sintética",
            "2026-08",
            "Agosto de 2026",
            ReviewGroupState.Approved,
            1,
            [],
            [],
            null,
            timestamp,
            timestamp);

        Assert.Equal("Aprovado no aplicativo", group.ApprovalSummary);
        Assert.DoesNotContain("operator", group.ApprovalSummary, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void SelectedGroupViewerShowsEveryDocumentWithoutLosingIndividualSelection()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 9, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var documents = new[]
        {
            CreateReviewDocument(clientId, groupId, "folha-agosto.pdf", RecognizedDocumentType.Payroll, 2026, 8, timestamp),
            CreateReviewDocument(clientId, groupId, "fgts-agosto.pdf", RecognizedDocumentType.FgtsDigital, 2026, 8, timestamp),
            CreateReviewDocument(clientId, groupId, "pro-labore-agosto.pdf", RecognizedDocumentType.ProLabore, 2026, 8, timestamp),
        };
        var group = CreateReviewGroup(
            groupId,
            clientId,
            "Empresa Sintética Viewer",
            "2026-08",
            "08/2026",
            documents.Select(document => document.Id).ToArray(),
            timestamp);
        var viewModel = new MainViewModel();
        foreach (var document in documents)
        {
            viewModel.ReviewDocuments.Add(document);
        }
        viewModel.ReviewGroups.Add(group);

        viewModel.SelectedReviewDocument = documents[1];

        Assert.Same(documents[1], viewModel.SelectedReviewDocument);
        Assert.Equal(groupId, viewModel.SelectedReviewGroup?.Id);
        Assert.Equal(
            ["fgts-agosto.pdf", "folha-agosto.pdf", "pro-labore-agosto.pdf"],
            viewModel.SelectedReviewGroupDocuments.Select(document => document.FileName));
        Assert.True(viewModel.HasSelectedReviewGroupDocuments);
        Assert.Contains("3 documento(s)", viewModel.SelectedGroupViewerHeader, StringComparison.Ordinal);
    }

    [Fact]
    public void ReturningToDocumentsRealignsTheGroupWithTheSelectedDocument()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 9, 0, 0, TimeSpan.Zero);
        var firstClientId = Guid.NewGuid();
        var secondClientId = Guid.NewGuid();
        var firstGroupId = Guid.NewGuid();
        var secondGroupId = Guid.NewGuid();
        var firstDocument = CreateReviewDocument(
            firstClientId,
            firstGroupId,
            "folha-primeiro-cliente.pdf",
            RecognizedDocumentType.Payroll,
            2026,
            8,
            timestamp,
            "Primeiro Cliente");
        var secondDocument = CreateReviewDocument(
            secondClientId,
            secondGroupId,
            "folha-segundo-cliente.pdf",
            RecognizedDocumentType.Payroll,
            2026,
            8,
            timestamp,
            "Segundo Cliente");
        var firstGroup = CreateReviewGroup(
            firstGroupId,
            firstClientId,
            "Primeiro Cliente",
            "2026-08",
            "08/2026",
            [firstDocument.Id],
            timestamp);
        var secondGroup = CreateReviewGroup(
            secondGroupId,
            secondClientId,
            "Segundo Cliente",
            "2026-08",
            "08/2026",
            [secondDocument.Id],
            timestamp);
        var viewModel = new MainViewModel();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);
        viewModel.ReviewDocuments.Add(firstDocument);
        viewModel.ReviewDocuments.Add(secondDocument);
        viewModel.ReviewGroups.Add(firstGroup);
        viewModel.ReviewGroups.Add(secondGroup);
        viewModel.SelectedReviewDocument = firstDocument;
        viewModel.SelectedReviewGroup = secondGroup;

        viewModel.ShowDocumentsCommand.Execute(null);

        Assert.Same(firstDocument, viewModel.SelectedReviewDocument);
        Assert.Equal(firstGroupId, viewModel.SelectedReviewGroup?.Id);
        Assert.All(viewModel.SelectedReviewGroupDocuments, document =>
            Assert.Equal(firstGroupId, document.GroupId));
    }

    [Fact]
    public async Task ClientReleaseApprovesThreeSeparatePeriodsInOneAtomicUiAction()
    {
        var service = new RecordingClientApprovalReviewService();
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            service);
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);
        viewModel.SelectedReviewDocument = viewModel.ReviewDocuments.First(document =>
            document.Period.Year == 2026 && document.Period.Month == 8);

        Assert.Equal(3, viewModel.SelectedClientReadyGroupCount);
        Assert.Equal(5, viewModel.SelectedClientReadyDocumentCount);
        Assert.Equal(3, viewModel.SelectedClientReadyPeriodCount);
        Assert.Contains("3 conjuntos / 5 documentos / 3 competências", viewModel.SelectedClientApprovalLabel, StringComparison.Ordinal);

        await viewModel.ApproveSelectedClientGroupsCommand.ExecuteAsync(null);

        Assert.Equal(1, service.ApproveClientGroupsCallCount);
        Assert.Equal(0, service.ApproveGroupsCallCount);
        Assert.Equal(service.ClientId, service.ApprovedClientId);
        Assert.Equal(service.ExpectedGroupIds.Order(), service.ApprovedGroupIds.Order());
        Assert.Equal(3, viewModel.ReviewGroups.Count);
        Assert.Equal(3, viewModel.ReviewGroups.Select(group => group.PeriodKey).Distinct().Count());
        Assert.Equal([1, 1, 3], viewModel.ReviewGroups.Select(group => group.DocumentIds.Count).Order());
        Assert.All(viewModel.ReviewGroups, group => Assert.True(group.IsApproved));
        Assert.Contains("continua separado", viewModel.StatusMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ManualOrganizationOffersOnlyCompatibleDistinctGroupsAndRequiresReasons()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 10, 0, 0, TimeSpan.Zero);
        var selectedClientId = Guid.NewGuid();
        var otherClientId = Guid.NewGuid();
        var selectedGroupId = Guid.NewGuid();
        var firstCompatibleId = Guid.NewGuid();
        var secondCompatibleId = Guid.NewGuid();
        var otherPeriodId = Guid.NewGuid();
        var otherClientGroupId = Guid.NewGuid();
        var otherEstablishmentId = Guid.NewGuid();
        var otherPolicyId = Guid.NewGuid();
        var selectedDocuments = new[]
        {
            CreateReviewDocument(selectedClientId, selectedGroupId, "folha-a.pdf", RecognizedDocumentType.Payroll, 2026, 8, timestamp),
            CreateReviewDocument(selectedClientId, selectedGroupId, "fgts-a.pdf", RecognizedDocumentType.FgtsDigital, 2026, 8, timestamp),
        };
        var firstCompatibleDocument = CreateReviewDocument(
            selectedClientId,
            firstCompatibleId,
            "pro-labore-b.pdf",
            RecognizedDocumentType.ProLabore,
            2026,
            8,
            timestamp);
        var secondCompatibleDocuments = new[]
        {
            CreateReviewDocument(selectedClientId, secondCompatibleId, "guia-c.pdf", RecognizedDocumentType.FederalRevenueCollection, 2026, 8, timestamp),
            CreateReviewDocument(selectedClientId, secondCompatibleId, "folha-c.pdf", RecognizedDocumentType.Payroll, 2026, 8, timestamp),
        };
        var excludedOtherPeriodDocument = CreateReviewDocument(
            selectedClientId,
            otherPeriodId,
            "folha-setembro.pdf",
            RecognizedDocumentType.Payroll,
            2026,
            9,
            timestamp);
        var excludedOtherClientDocument = CreateReviewDocument(
            otherClientId,
            otherClientGroupId,
            "folha-outra-empresa.pdf",
            RecognizedDocumentType.Payroll,
            2026,
            8,
            timestamp);
        var excludedOtherEstablishmentDocument = CreateReviewDocument(
            selectedClientId,
            otherEstablishmentId,
            "folha-outra-unidade.pdf",
            RecognizedDocumentType.Payroll,
            2026,
            8,
            timestamp);
        var excludedOtherPolicyDocument = CreateReviewDocument(
            selectedClientId,
            otherPolicyId,
            "folha-outra-regra.pdf",
            RecognizedDocumentType.Payroll,
            2026,
            8,
            timestamp);
        var viewModel = new MainViewModel();
        foreach (var document in selectedDocuments
                     .Append(firstCompatibleDocument)
                     .Concat(secondCompatibleDocuments)
                     .Append(excludedOtherPeriodDocument)
                     .Append(excludedOtherClientDocument)
                     .Append(excludedOtherEstablishmentDocument)
                     .Append(excludedOtherPolicyDocument))
        {
            viewModel.ReviewDocuments.Add(document);
        }
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            selectedGroupId,
            selectedClientId,
            "Empresa Sintética",
            "2026-08",
            "08/2026",
            selectedDocuments.Select(document => document.Id).ToArray(),
            timestamp));
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            firstCompatibleId,
            selectedClientId,
            "Empresa Sintética",
            "2026-08",
            "08/2026",
            [firstCompatibleDocument.Id],
            timestamp.AddMinutes(1)));
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            secondCompatibleId,
            selectedClientId,
            "Empresa Sintética",
            "2026-08",
            "08/2026",
            secondCompatibleDocuments.Select(document => document.Id).ToArray(),
            timestamp.AddMinutes(2)));
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            otherPeriodId,
            selectedClientId,
            "Empresa Sintética",
            "2026-09",
            "09/2026",
            [excludedOtherPeriodDocument.Id],
            timestamp.AddMinutes(3)));
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            otherClientGroupId,
            otherClientId,
            "Outra Empresa",
            "2026-08",
            "08/2026",
            [excludedOtherClientDocument.Id],
            timestamp.AddMinutes(4)));
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            otherEstablishmentId,
            selectedClientId,
            "Empresa Sintética",
            "2026-08",
            "08/2026",
            [excludedOtherEstablishmentDocument.Id],
            timestamp.AddMinutes(5)) with
        {
            EstablishmentId = Guid.NewGuid(),
        });
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            otherPolicyId,
            selectedClientId,
            "Empresa Sintética",
            "2026-08",
            "08/2026",
            [excludedOtherPolicyDocument.Id],
            timestamp.AddMinutes(6)) with
        {
            GroupingPolicyVersion = "outra-versao",
        });

        viewModel.SelectedReviewDocument = selectedDocuments[0];

        var compatible = viewModel.CompatibleMergeGroupOptions.ToArray();
        Assert.Equal([firstCompatibleId, secondCompatibleId], compatible.Select(option => option.GroupId));
        Assert.Equal(2, compatible.Select(option => option.Label).Distinct().Count());
        Assert.Equal(2, compatible.Select(option => option.Detail).Distinct().Count());
        Assert.Contains(compatible, option => option.Detail.Contains("Pró-labore", StringComparison.Ordinal));
        Assert.Contains(compatible, option => option.Detail.Contains("2 documento(s)", StringComparison.Ordinal));
        Assert.DoesNotContain(compatible, option => option.GroupId == selectedGroupId);
        Assert.DoesNotContain(compatible, option => option.GroupId == otherPeriodId);
        Assert.DoesNotContain(compatible, option => option.GroupId == otherClientGroupId);
        Assert.DoesNotContain(compatible, option => option.GroupId == otherEstablishmentId);
        Assert.DoesNotContain(compatible, option => option.GroupId == otherPolicyId);

        viewModel.SplitGroupReason = "curto";
        viewModel.MergeGroupReason = "curto";

        Assert.False(viewModel.CanSplitSelectedDocument);
        Assert.False(viewModel.CanMergeSelectedGroups);

        viewModel.SplitGroupReason = "Separação sintética justificada";
        viewModel.MergeGroupReason = "União sintética justificada";

        Assert.True(viewModel.CanSplitSelectedDocument);
        Assert.True(viewModel.CanMergeSelectedGroups);
    }

    [Fact]
    public async Task DispatchQueueKeepsOnlyTheCurrentVersionPerGroupAndSupportsSearchAndStatusFilters()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 11, 0, 0, TimeSpan.Zero);
        var sharedGroupId = Guid.NewGuid();
        var sharedClientId = Guid.NewGuid();
        var oldVersion = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Alfa",
            DispatchItemState.ReadyForApproval,
            timestamp.AddMinutes(10),
            [],
            attachmentCount: 1) with
        {
            GroupId = sharedGroupId,
            ClientId = sharedClientId,
            Revision = 1,
            Message = CreateDispatchMessage(
                "folha-antiga.pdf",
                RecognizedDocumentType.Payroll,
                timestamp.AddMinutes(10)),
        };
        var currentVersion = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Alfa",
            DispatchItemState.ReadyForApproval,
            timestamp.AddMinutes(2),
            [],
            attachmentCount: 1) with
        {
            GroupId = sharedGroupId,
            ClientId = sharedClientId,
            Revision = 2,
            Message = CreateDispatchMessage(
                "folha-atual-agosto.pdf",
                RecognizedDocumentType.Payroll,
                timestamp.AddMinutes(2)),
        };
        var cancelledReplacement = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Alfa",
            DispatchItemState.Cancelled,
            timestamp.AddMinutes(3),
            [],
            attachmentCount: 1) with
        {
            GroupId = sharedGroupId,
            ClientId = sharedClientId,
            Revision = 3,
        };
        var attentionItem = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Beta",
            DispatchItemState.Blocked,
            timestamp.AddMinutes(4),
            [new DispatchBlock("SYNTHETIC", ValidationSeverity.Blocker, "Pendência sintética.")]);
        var completedItem = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Gama",
            DispatchItemState.AcceptedByProvider,
            timestamp.AddMinutes(5),
            [],
            attachmentCount: 1);
        var workspace = new DispatchWorkspace(
            "ui-test",
            [],
            [oldVersion, currentVersion, cancelledReplacement, attentionItem, completedItem],
            [],
            []);
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            new RecordingDispatchService(workspace));
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);

        Assert.Equal(
            new[] { currentVersion.Id, attentionItem.Id }.Order(),
            viewModel.VisibleDispatchItems.Select(item => item.Id).Order());
        Assert.DoesNotContain(viewModel.VisibleDispatchItems, item => item.Id == oldVersion.Id);
        Assert.DoesNotContain(viewModel.VisibleDispatchItems, item => item.Id == cancelledReplacement.Id);

        viewModel.SelectedDispatchQueueFilter = viewModel.DispatchQueueFilters.Single(option =>
            option.Value == DispatchQueueFilterKind.All);
        Assert.Equal(3, viewModel.VisibleDispatchItems.Count());

        viewModel.DispatchQueueSearchText = "folha-atual-agosto";
        var searched = Assert.Single(viewModel.VisibleDispatchItems);
        Assert.Equal(currentVersion.Id, searched.Id);

        viewModel.DispatchQueueSearchText = $"#{currentVersion.Id.ToString("N")[..6]}";
        searched = Assert.Single(viewModel.VisibleDispatchItems);
        Assert.Equal(currentVersion.Id, searched.Id);

        viewModel.DispatchQueueSearchText = "referência que não existe";
        Assert.Empty(viewModel.VisibleDispatchItems);
        Assert.True(viewModel.ShowDispatchQueueEmptyState);
        Assert.Contains("Nenhuma mensagem corresponde", viewModel.DispatchQueueEmptyMessage, StringComparison.Ordinal);

        viewModel.DispatchQueueSearchText = string.Empty;
        viewModel.SelectedDispatchQueueFilter = viewModel.DispatchQueueFilters.Single(option =>
            option.Value == DispatchQueueFilterKind.Completed);
        Assert.Equal(completedItem.Id, Assert.Single(viewModel.VisibleDispatchItems).Id);

        viewModel.SelectedDispatchQueueFilter = viewModel.DispatchQueueFilters.Single(option =>
            option.Value == DispatchQueueFilterKind.All);
        viewModel.SelectedDispatchItem = currentVersion;
        Assert.Equal($"Mensagem #{currentVersion.Id.ToString("N")[..6].ToUpperInvariant()}", viewModel.SelectedDispatchReference);
        Assert.Contains("Empresa Alfa", viewModel.SelectedDispatchContextSummary, StringComparison.Ordinal);
        Assert.Contains("08/2026", viewModel.SelectedDispatchContextSummary, StringComparison.Ordinal);
        Assert.Contains("1 anexo(s)", viewModel.SelectedDispatchContextSummary, StringComparison.Ordinal);
        Assert.Contains("2 a fazer", viewModel.DispatchQueueSummary, StringComparison.Ordinal);
        Assert.Contains("1 concluída", viewModel.DispatchQueueSummary, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ReportsCombineClientWithMonthYearAndRangeAndExplainGroupsAndFiles()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 12, 0, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var otherClientId = Guid.NewGuid();
        var august = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Relatório",
            DispatchItemState.AcceptedByProvider,
            timestamp,
            [],
            attachmentCount: 1) with
        {
            ClientId = clientId,
            PeriodLabel = "08/2026",
            Message = CreateDispatchMessage("folha-agosto.pdf", RecognizedDocumentType.Payroll, timestamp),
        };
        var september = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Relatório",
            DispatchItemState.DraftCreated,
            timestamp.AddMonths(1),
            [],
            attachmentCount: 1) with
        {
            ClientId = clientId,
            PeriodLabel = "09/2026",
            Message = CreateDispatchMessage("fgts-setembro.pdf", RecognizedDocumentType.FgtsDigital, timestamp.AddMonths(1)),
        };
        var december = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Relatório",
            DispatchItemState.Completed,
            timestamp.AddMonths(4),
            [],
            attachmentCount: 1) with
        {
            ClientId = clientId,
            PeriodLabel = "12/2026",
            Message = CreateDispatchMessage("pro-labore-dezembro.pdf", RecognizedDocumentType.ProLabore, timestamp.AddMonths(4)),
        };
        var otherClient = CreateDispatchItem(
            Guid.NewGuid(),
            "Outra Empresa",
            DispatchItemState.AcceptedByProvider,
            timestamp.AddMinutes(1),
            [],
            attachmentCount: 1) with
        {
            ClientId = otherClientId,
            PeriodLabel = "08/2026",
            Message = CreateDispatchMessage("outra-empresa.pdf", RecognizedDocumentType.Payroll, timestamp.AddMinutes(1)),
        };
        var service = new CapturingReportDispatchService(new DispatchWorkspace(
            "ui-test",
            [],
            [august, september, december, otherClient],
            [],
            []));
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            service)
        {
            ReportOutputDirectory = Path.Combine(Path.GetTempPath(), "folhas-ui-reports", Guid.NewGuid().ToString("N")),
        };
        await viewModel.LoadReviewWorkspaceAsync();
        var reportClient = CreateClientRow(clientId, "Empresa Relatório");
        viewModel.ReportClients.Add(reportClient);
        viewModel.SelectedReportClientFilter = viewModel.ReportClientFilters.Single(option =>
            option.Value == ReportClientFilterKind.SelectedClient);
        viewModel.SelectedReportClient = reportClient;
        viewModel.SelectedReportScope = viewModel.ReportScopes.Single(option => option.Value == DispatchReportScope.Month);
        viewModel.SelectedReportYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedReportMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);

        var monthRow = Assert.Single(viewModel.ReportCommunicationRows);
        Assert.Equal(august.Id, monthRow.ItemId);
        Assert.Equal($"Mensagem #{august.Id.ToString("N")[..6].ToUpperInvariant()}", monthRow.MessageReference);
        Assert.Contains(august.GroupId.ToString("N")[..6].ToUpperInvariant(), monthRow.GroupSummary, StringComparison.Ordinal);
        Assert.Contains("Folha de pagamento", monthRow.DocumentSummary, StringComparison.Ordinal);
        Assert.Contains("folha-agosto.pdf", monthRow.DocumentSummary, StringComparison.Ordinal);

        await viewModel.ExportDispatchReportsCommand.ExecuteAsync(null);
        AssertReportFilter(service.Filters[^1], DispatchReportScope.Month, clientId, year: 2026, month: 8);

        viewModel.SelectedReportScope = viewModel.ReportScopes.Single(option => option.Value == DispatchReportScope.Year);
        Assert.Equal(3, viewModel.ReportCommunicationRows.Count());
        await viewModel.ExportDispatchReportsCommand.ExecuteAsync(null);
        AssertReportFilter(service.Filters[^1], DispatchReportScope.Year, clientId, year: 2026);

        viewModel.SelectedReportScope = viewModel.ReportScopes.Single(option => option.Value == DispatchReportScope.Range);
        viewModel.SelectedReportStartYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedReportStartMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);
        viewModel.SelectedReportEndYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedReportEndMonth = viewModel.OperationalMonths.Single(option => option.Month == 9);
        Assert.Equal(
            new[] { august.Id, september.Id }.Order(),
            viewModel.ReportCommunicationRows.Select(row => row.ItemId).Order());
        await viewModel.ExportDispatchReportsCommand.ExecuteAsync(null);
        var rangeFilter = service.Filters[^1];
        AssertReportFilter(rangeFilter, DispatchReportScope.Range, clientId);
        Assert.Equal((2026, 8, 2026, 9), (rangeFilter.StartYear, rangeFilter.StartMonth, rangeFilter.EndYear, rangeFilter.EndMonth));
        Assert.Contains("Empresa Relatório", viewModel.ReportScopeSummary, StringComparison.Ordinal);
        Assert.Contains("AGOSTO", viewModel.ReportScopeSummary, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("SETEMBRO", viewModel.ReportScopeSummary, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ProjectedHistoryNamesTheClientPeriodAndDocumentType()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 13, 15, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var document = CreateReviewDocument(
            clientId,
            groupId,
            "fgts-historico.pdf",
            RecognizedDocumentType.FgtsDigital,
            2026,
            8,
            timestamp,
            "Empresa Histórico");
        var group = CreateReviewGroup(
            groupId,
            clientId,
            "Empresa Histórico",
            "2026-08",
            "08/2026",
            [document.Id],
            timestamp);
        var dispatchItem = CreateDispatchItem(
            Guid.NewGuid(),
            "Empresa Histórico",
            DispatchItemState.AcceptedByProvider,
            timestamp,
            [],
            attachmentCount: 1) with
        {
            ClientId = clientId,
            GroupId = groupId,
            PeriodLabel = "08/2026",
            Message = CreateDispatchMessage("fgts-historico.pdf", RecognizedDocumentType.FgtsDigital, timestamp),
        };
        var viewModel = new MainViewModel();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);
        viewModel.ReviewDocuments.Add(document);
        viewModel.ReviewGroups.Add(group);
        viewModel.DispatchItems.Add(dispatchItem);
        viewModel.ReviewAuditEvents.Add(new ReviewAuditEvent(
            Guid.NewGuid(),
            "ui-test",
            "synthetic-operator",
            timestamp,
            "document.grouped",
            document.Id,
            groupId,
            null,
            null,
            "Documento sintético organizado.",
            "history-review"));
        viewModel.DispatchAuditEvents.Add(new DispatchAuditEvent(
            Guid.NewGuid(),
            "ui-test",
            "synthetic-operator",
            timestamp.AddMinutes(1),
            "dispatch.executed",
            dispatchItem.BatchId,
            dispatchItem.Id,
            groupId,
            "ReadyForApproval",
            null,
            "history-dispatch"));

        var reviewRow = Assert.Single(viewModel.VisibleReviewHistoryRows);
        Assert.Equal("Empresa Histórico", reviewRow.ClientName);
        Assert.Contains("08/2026", reviewRow.Context, StringComparison.Ordinal);
        Assert.Contains("FGTS Digital", reviewRow.Context, StringComparison.Ordinal);
        Assert.DoesNotContain("fgts-historico.pdf", reviewRow.Context, StringComparison.OrdinalIgnoreCase);

        var dispatchRow = Assert.Single(viewModel.VisibleDispatchHistoryRows);
        Assert.Equal("Empresa Histórico", dispatchRow.ClientName);
        Assert.Contains("08/2026", dispatchRow.Context, StringComparison.Ordinal);
        Assert.Contains("FGTS Digital", dispatchRow.Context, StringComparison.Ordinal);
        Assert.Contains("1 anexo(s)", dispatchRow.Context, StringComparison.Ordinal);
        Assert.Equal("Pronto para aprovação", dispatchRow.Result);
    }

    [Fact]
    public void AggregateClientApprovalRemainsVisibleInEachRecordedCompetence()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 13, 15, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var audit = new ReviewAuditEvent(
            Guid.NewGuid(),
            "ui-test",
            "synthetic-operator",
            timestamp,
            "groups.client_approved",
            null,
            null,
            null,
            $"client:{clientId:N};groups:3;documents:5;competences:3;periods:08/2026|09/2026|12/2026",
            "Aprovação sintética do cliente.",
            "history-client-approval");
        var viewModel = new MainViewModel();
        viewModel.ReviewAuditEvents.Add(audit);
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 9);

        Assert.Equal(audit.Id, Assert.Single(viewModel.VisibleReviewAuditEvents).Id);
        var row = Assert.Single(viewModel.VisibleReviewHistoryRows);
        Assert.Contains("5 documento(s)", row.Context, StringComparison.Ordinal);
        Assert.Contains("09/2026", row.Context, StringComparison.Ordinal);

        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 10);

        Assert.Empty(viewModel.VisibleReviewAuditEvents);
    }

    [Fact]
    public void RemovedDocumentHistoryKeepsClientPeriodAndDocumentTypeWithoutTheActiveDocument()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 13, 15, 0, TimeSpan.Zero);
        var clientId = Guid.NewGuid();
        var audit = new ReviewAuditEvent(
            Guid.NewGuid(),
            "ui-test",
            "synthetic-operator",
            timestamp,
            "document.removed_from_review",
            Guid.NewGuid(),
            Guid.NewGuid(),
            $"client:{clientId:N};client-name:Empresa Retirada;type:FgtsDigital;period:month:2026-08;sha256:synthetic",
            null,
            "Documento retirado por engano.",
            "history-document-removal");
        var viewModel = new MainViewModel();
        viewModel.ReviewAuditEvents.Add(audit);
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);

        var row = Assert.Single(viewModel.VisibleReviewHistoryRows);

        Assert.Equal("Empresa Retirada", row.ClientName);
        Assert.Contains("08/2026", row.Context, StringComparison.Ordinal);
        Assert.Contains("FGTS Digital", row.Context, StringComparison.Ordinal);
    }

    [Fact]
    public async Task HistoryHourSelectionAlwaysPersistsOneWholeClockHour()
    {
        var preferences = new InMemoryWorkspacePreferencesStore();
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            new EmptyReviewService(),
            null,
            null,
            preferences);
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedHistoryCleanupScope = viewModel.HistoryCleanupScopes.Single(option =>
            option.Value == HistoryVisibilityRuleKind.ClockHour);
        viewModel.HistoryRangeStartDate = new DateTimeOffset(2026, 8, 24, 0, 0, 0, TimeSpan.Zero);
        viewModel.HistoryRangeStartTime = new TimeSpan(10, 47, 0);
        viewModel.SelectedHistoryHour = viewModel.HistoryHourOptions.Single(option => option.Hour == 10);

        await viewModel.ArchiveHistoryViewCommand.ExecuteAsync(null);

        var rule = Assert.Single(preferences.SavedPreferences?.HistoryVisibility?.Rules ?? []);
        Assert.Equal(HistoryVisibilityRuleKind.ClockHour, rule.Kind);
        Assert.NotNull(rule.StartUtc);
        Assert.NotNull(rule.EndExclusiveUtc);
        Assert.Equal(TimeSpan.FromHours(1), rule.EndExclusiveUtc!.Value - rule.StartUtc!.Value);
        var localStart = TimeZoneInfo.ConvertTime(rule.StartUtc.Value, TimeZoneInfo.Local);
        Assert.Equal(10, localStart.Hour);
        Assert.Equal(0, localStart.Minute);
        Assert.Contains("10:00–10:59", viewModel.HistoryCleanupSelectionSummary, StringComparison.Ordinal);
        Assert.Equal("Ocultar este bloco de uma hora", viewModel.HistoryCleanupActionLabel);
    }

    [Fact]
    public async Task CatalogHistoryLoadsTheClientChosenInTheHistoryFilter()
    {
        var firstClientId = Guid.NewGuid();
        var audit = new AuditEventModel(
            Guid.NewGuid(),
            "client",
            firstClientId.ToString("D"),
            "updated",
            "catalog",
            "info",
            "{}",
            new DateTimeOffset(2026, 8, 24, 14, 0, 0, TimeSpan.Zero),
            Guid.NewGuid());
        var client = new ClientDetails(
            firstClientId,
            PersonTypeModel.LegalEntity,
            "Empresa com histórico LTDA",
            "Empresa com histórico",
            null,
            "11222333000181",
            true,
            null,
            null,
            null,
            1,
            audit.TimestampUtc,
            audit.TimestampUtc,
            [],
            [],
            [],
            []);
        var catalog = new InMemoryClientCatalog(client)
        {
            AuditTrail = [audit],
        };
        var viewModel = new MainViewModel(
            catalog,
            new UnusedRecognitionService(),
            new EmptyReviewService());

        await viewModel.ShowHistoryCommand.ExecuteAsync(null);
        viewModel.SelectedHistoryClientFilter = viewModel.HistoryClientFilters.Single(item =>
            item.ClientId == firstClientId);
        await viewModel.ApplyHistoryFiltersCommand.ExecuteAsync(null);

        Assert.Equal(audit.Id, Assert.Single(viewModel.VisibleCatalogAuditEvents).Id);
        Assert.True(viewModel.HasVisibleCatalogAuditEvents);
        Assert.Contains("Empresa com histórico", viewModel.CatalogHistoryHeader, StringComparison.Ordinal);

        viewModel.SelectedHistoryClientFilter = viewModel.HistoryClientFilters.Single(item =>
            item.ClientId is null);
        await viewModel.ApplyHistoryFiltersCommand.ExecuteAsync(null);

        Assert.Empty(viewModel.VisibleCatalogAuditEvents);
        Assert.False(viewModel.HasVisibleCatalogAuditEvents);
        Assert.Contains("Escolha um cliente", viewModel.CatalogHistoryEmptyMessage, StringComparison.Ordinal);
    }

    [Fact]
    public async Task HistoryCombinesDayClientAndDocumentFiltersAndStartsCollapsed()
    {
        var clientId = Guid.NewGuid();
        var firstGroupId = Guid.NewGuid();
        var secondGroupId = Guid.NewGuid();
        var firstTimestamp = new DateTimeOffset(2026, 8, 24, 13, 0, 0, TimeSpan.Zero);
        var secondTimestamp = firstTimestamp.AddDays(1);
        var firstDocument = CreateReviewDocument(
            clientId,
            firstGroupId,
            "folha-boreal.pdf",
            RecognizedDocumentType.Payroll,
            2026,
            8,
            firstTimestamp,
            "BOREAL TECNOLOGIA SINTETICA LTDA");
        var secondDocument = CreateReviewDocument(
            clientId,
            secondGroupId,
            "rescisao-boreal.pdf",
            RecognizedDocumentType.Termination,
            2026,
            8,
            secondTimestamp,
            "BOREAL TECNOLOGIA SINTETICA LTDA");
        var viewModel = new MainViewModel
        {
            SelectedOperationalYear = OperationalYearOption.All,
            SelectedOperationalMonth = OperationalMonthOption.Create().Single(option => option.Month is null),
        };
        viewModel.ReviewDocuments.Add(firstDocument);
        viewModel.ReviewDocuments.Add(secondDocument);
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            firstGroupId,
            clientId,
            "BOREAL TECNOLOGIA SINTETICA LTDA",
            "2026-08",
            "08/2026",
            [firstDocument.Id],
            firstTimestamp));
        viewModel.ReviewGroups.Add(CreateReviewGroup(
            secondGroupId,
            clientId,
            "BOREAL TECNOLOGIA SINTETICA LTDA",
            "2026-08",
            "08/2026",
            [secondDocument.Id],
            secondTimestamp));
        viewModel.ReviewAuditEvents.Add(new ReviewAuditEvent(
            Guid.NewGuid(), "ui-test", "operator", firstTimestamp, "document.reviewed",
            firstDocument.Id, firstGroupId, null, null, "Primeiro documento", "history-filter-1"));
        viewModel.ReviewAuditEvents.Add(new ReviewAuditEvent(
            Guid.NewGuid(), "ui-test", "operator", secondTimestamp, "document.reviewed",
            secondDocument.Id, secondGroupId, null, null, "Segundo documento", "history-filter-2"));

        await viewModel.ShowHistoryCommand.ExecuteAsync(null);
        viewModel.SelectedHistoryTimeScope = viewModel.HistoryTimeScopes.Single(option =>
            option.Value == FolhasDaMichelly.Application.History.HistoryTimeScope.CalendarDay);
        viewModel.HistoryFilterStartDate = new DateTimeOffset(2026, 8, 24, 0, 0, 0, TimeSpan.Zero);
        viewModel.SelectedHistoryClientFilter = viewModel.HistoryClientFilters.Single(option =>
            option.ClientId == clientId);
        viewModel.SelectedHistoryDocumentFilter = viewModel.HistoryDocumentFilters.Single(option =>
            option.DocumentId == firstDocument.Id);

        await viewModel.ApplyHistoryFiltersCommand.ExecuteAsync(null);

        var row = Assert.Single(viewModel.VisibleReviewHistoryRows);
        Assert.Contains("Folha de pagamento", row.Context, StringComparison.Ordinal);
        Assert.False(viewModel.IsReviewHistoryExpanded);
        Assert.False(viewModel.IsDispatchHistoryExpanded);
        Assert.Contains("BOREAL", viewModel.HistoryAppliedFilterSummary, StringComparison.Ordinal);
        Assert.Contains("24/08/2026", viewModel.HistoryAppliedFilterSummary, StringComparison.Ordinal);
    }

    [Fact]
    public async Task HistoryOffersDocumentsPreservedOnlyInAMessageSnapshot()
    {
        var timestamp = new DateTimeOffset(2026, 8, 25, 13, 0, 0, TimeSpan.Zero);
        var item = CreateDispatchItem(
            Guid.NewGuid(),
            "BOREAL TECNOLOGIA SINTETICA LTDA",
            DispatchItemState.Completed,
            timestamp,
            [],
            attachmentCount: 1);
        var attachment = Assert.Single(item.Message!.Attachments);
        var viewModel = new MainViewModel();
        viewModel.DispatchItems.Add(item);

        await viewModel.ShowHistoryCommand.ExecuteAsync(null);

        Assert.Same(
            viewModel.SelectedHistoryClientFilter,
            viewModel.HistoryClientFilters.Single(option => option.ClientId is null));
        Assert.Equal("Todos os clientes", viewModel.SelectedHistoryClientFilter.Label);
        Assert.Same(
            viewModel.SelectedHistoryDocumentFilter,
            viewModel.HistoryDocumentFilters.Single(option => option.DocumentId is null));
        Assert.Equal("Todos os documentos", viewModel.SelectedHistoryDocumentFilter.Label);
        var option = Assert.Single(viewModel.HistoryDocumentFilters, candidate =>
            candidate.DocumentId == attachment.DocumentId);
        Assert.Contains(attachment.FileName, option.Label, StringComparison.Ordinal);
        Assert.Contains("BOREAL", option.Label, StringComparison.Ordinal);
    }

    [Fact]
    public async Task HistoryCompetenceChangeRefreshesRowsAndAppliedSummary()
    {
        var review = new PeriodBatchReviewService();
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            review);
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedOperationalYear = OperationalYearOption.All;
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month is null);
        await viewModel.ShowHistoryCommand.ExecuteAsync(null);

        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);

        Assert.Equal(review.AugustAuditId, Assert.Single(viewModel.VisibleReviewAuditEvents).Id);
        Assert.Equal(1, viewModel.VisibleReviewHistoryCount);
        Assert.Contains(viewModel.WorkPeriodLabel, viewModel.HistoryAppliedFilterSummary, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ClearCurrentHistoryViewHidesOnlyEventsVisibleThroughTheCurrentFilters()
    {
        var review = new PeriodBatchReviewService();
        var preferences = new InMemoryWorkspacePreferencesStore();
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            review,
            null,
            null,
            preferences);
        await viewModel.LoadReviewWorkspaceAsync();
        viewModel.SelectedOperationalYear = viewModel.OperationalYears.Single(option => option.Year == 2026);
        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 8);

        await viewModel.ArchiveHistoryViewCommand.ExecuteAsync(null);

        var rule = Assert.Single(preferences.SavedPreferences?.HistoryVisibility?.Rules ?? []);
        Assert.Null(rule.StartUtc);
        Assert.Null(rule.EndExclusiveUtc);
        Assert.Equal([review.AugustAuditId], rule.EventIds);
        Assert.Empty(viewModel.VisibleReviewAuditEvents);

        viewModel.SelectedOperationalMonth = viewModel.OperationalMonths.Single(option => option.Month == 9);

        Assert.Equal(review.SeptemberAuditId, Assert.Single(viewModel.VisibleReviewAuditEvents).Id);
    }

    [Fact]
    public async Task CatalogHistoryFailureDoesNotPretendTheFiltersWereApplied()
    {
        var clientId = Guid.NewGuid();
        var client = new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa com falha sintética LTDA",
            "Empresa com falha",
            null,
            "11222333000181",
            true,
            null,
            null,
            null,
            1,
            DateTimeOffset.UtcNow,
            DateTimeOffset.UtcNow,
            [],
            [],
            [],
            []);
        var catalog = new InMemoryClientCatalog(client);
        var viewModel = new MainViewModel(
            catalog,
            new UnusedRecognitionService(),
            new EmptyReviewService());
        await viewModel.ShowHistoryCommand.ExecuteAsync(null);
        var previousSummary = viewModel.HistoryAppliedFilterSummary;
        viewModel.SelectedHistoryClientFilter = viewModel.HistoryClientFilters.Single(option =>
            option.ClientId == clientId);
        catalog.FailOnGetAudit = true;

        await viewModel.ApplyHistoryFiltersCommand.ExecuteAsync(null);

        Assert.True(viewModel.HasHistoryFilterError);
        Assert.Contains("não foram aplicados", viewModel.HistoryFilterError, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(previousSummary, viewModel.HistoryAppliedFilterSummary);
        Assert.Empty(viewModel.VisibleCatalogAuditEvents);
    }

    [Fact]
    public void HistoryWarnsWhenMoreThanFiveHundredVisibleEventsWouldBeTruncated()
    {
        var timestamp = new DateTimeOffset(2026, 8, 24, 15, 0, 0, TimeSpan.Zero);
        var viewModel = new MainViewModel
        {
            SelectedOperationalYear = OperationalYearOption.All,
            SelectedOperationalMonth = OperationalMonthOption.Create().Single(option => option.Month is null),
        };
        for (var index = 0; index < 501; index++)
        {
            viewModel.ReviewAuditEvents.Add(new ReviewAuditEvent(
                Guid.NewGuid(),
                "ui-test",
                "synthetic-operator",
                timestamp.AddSeconds(index),
                "document.imported",
                null,
                null,
                null,
                null,
                "Registro sintético.",
                $"history-{index}"));
        }

        Assert.Equal(500, viewModel.VisibleReviewAuditEvents.Count());
        Assert.Equal(1, viewModel.TruncatedHistoryEventCount);
        Assert.True(viewModel.HasTruncatedHistoryEvents);
        Assert.Contains("continuam preservados", viewModel.HistoryTruncationMessage, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ExpandingOperationalYearsRebindsAndNotifiesEveryReportYearSelection()
    {
        const int expandedYear = 2042;
        var timestamp = new DateTimeOffset(2042, 1, 15, 9, 0, 0, TimeSpan.Zero);
        var document = CreateReviewDocument(
            Guid.NewGuid(),
            Guid.NewGuid(),
            "folha-2042.pdf",
            RecognizedDocumentType.Payroll,
            expandedYear,
            1,
            timestamp);
        var review = new StaticReviewService(new DocumentReviewWorkspace(
            "ui-test",
            [document],
            [],
            []));
        var viewModel = new MainViewModel(
            new InMemoryClientCatalog(),
            new UnusedRecognitionService(),
            review);
        var selectedYear = Assert.IsType<OperationalYearOption>(viewModel.SelectedReportYear);
        viewModel.SelectedReportStartYear = selectedYear;
        viewModel.SelectedReportEndYear = selectedYear;
        var changedProperties = new List<string>();
        viewModel.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName is not null)
            {
                changedProperties.Add(args.PropertyName);
            }
        };

        await viewModel.LoadReviewWorkspaceAsync();

        Assert.Contains(viewModel.ReportYearOptions, option => option.Year == expandedYear);
        var reboundYear = viewModel.ReportYearOptions.Single(option => option.Year == selectedYear.Year);
        Assert.Same(reboundYear, viewModel.SelectedReportYear);
        Assert.Same(reboundYear, viewModel.SelectedReportStartYear);
        Assert.Same(reboundYear, viewModel.SelectedReportEndYear);
        Assert.Contains(nameof(MainViewModel.ReportYearOptions), changedProperties);
    }

    private static ReviewDocument CreateReviewDocument(
        Guid clientId,
        Guid groupId,
        string fileName,
        RecognizedDocumentType documentType,
        int year,
        int month,
        DateTimeOffset timestamp,
        string clientDisplayName = "Empresa Sintética") => new(
            Guid.NewGuid(),
            $"/synthetic/{fileName}",
            fileName,
            Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(fileName))),
            1_024,
            1,
            documentType,
            "synthetic-profile",
            clientId,
            null,
            clientDisplayName,
            "**.***.***/****-81",
            ClientResolutionMethod.ExactClientTaxId,
            .99m,
            [],
            [],
            [],
            [],
            new DocumentPeriod(
                DocumentPeriodKind.Monthly,
                month,
                year,
                null,
                null,
                null,
                $"{month:00}/{year}"),
            $"synthetic:{fileName}",
            ReviewDocumentState.Grouped,
            1,
            groupId,
            [],
            timestamp,
            timestamp);

    private static DocumentDispatchGroup CreateReviewGroup(
        Guid groupId,
        Guid clientId,
        string clientDisplayName,
        string periodKey,
        string periodLabel,
        IReadOnlyList<Guid> documentIds,
        DateTimeOffset timestamp) => new(
            groupId,
            $"synthetic:{clientId:N}:{periodKey}:{groupId:N}",
            "synthetic-policy",
            "1",
            clientId,
            null,
            clientDisplayName,
            periodKey,
            periodLabel,
            ReviewGroupState.ReadyForReview,
            1,
            documentIds,
            [],
            null,
            timestamp,
            timestamp);

    private static RenderedMessageSnapshot CreateDispatchMessage(
        string fileName,
        RecognizedDocumentType documentType,
        DateTimeOffset timestamp)
    {
        var fingerprint = Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(fileName)));
        return new RenderedMessageSnapshot(
            Guid.NewGuid(),
            1,
            "Assunto sintético",
            Guid.NewGuid(),
            1,
            "Corpo sintético",
            "fake://local",
            [],
            [],
            ["auditoria@example.invalid"],
            [],
            "Teste seguro",
            "Corpo sintético.",
            "<p>Corpo sintético.</p>",
            [new DispatchAttachmentSnapshot(
                Guid.NewGuid(),
                $"/synthetic/{fileName}",
                fileName,
                fingerprint,
                1_024,
                documentType)],
            fingerprint,
            timestamp);
    }

    private static ClientListRow CreateClientRow(Guid clientId, string displayName) => new(
        new ClientListItem(
            clientId,
            PersonTypeModel.LegalEntity,
            displayName,
            "**.***.***/****-81",
            null,
            true,
            1,
            1,
            1,
            new DateTimeOffset(2026, 8, 24, 12, 0, 0, TimeSpan.Zero)),
        "11222333000181",
        true);

    private static void AssertReportFilter(
        DispatchReportFilter filter,
        DispatchReportScope expectedScope,
        Guid expectedClientId,
        int? year = null,
        int? month = null)
    {
        Assert.Equal(expectedScope, filter.Scope);
        Assert.Equal(expectedClientId, filter.ClientId);
        Assert.Equal(year, filter.Year);
        Assert.Equal(month, filter.Month);
    }

    private static DispatchWorkspace CreateMixedDispatchBatchWorkspace()
    {
        var timestamp = new DateTimeOffset(2026, 8, 23, 12, 0, 0, TimeSpan.Zero);
        var batchId = Guid.NewGuid();
        var ready = CreateDispatchItem(
            batchId,
            "Cliente pronto",
            DispatchItemState.ReadyForApproval,
            timestamp,
            []);
        var approved = CreateDispatchItem(
            batchId,
            "Cliente aprovado",
            DispatchItemState.Approved,
            timestamp.AddMinutes(1),
            []);
        approved = approved with
        {
            Approval = new DispatchApprovalSnapshot(
                Guid.NewGuid(),
                approved.Id,
                approved.Revision,
                Guid.NewGuid(),
                "review-content-hash",
                approved.Message!.DispatchFingerprint,
                "synthetic-operator",
                timestamp),
        };
        var blocked = CreateDispatchItem(
            batchId,
            "Cliente com pendência",
            DispatchItemState.Blocked,
            timestamp.AddMinutes(2),
            [new DispatchBlock("SYNTHETIC_BLOCK", ValidationSeverity.Blocker, "Pendência sintética.")]);
        var batch = new ProcessingBatch(
            batchId,
            "ui-test",
            ProcessingSelectionMode.Batch,
            DispatchOperationMode.Test,
            ProcessingBatchState.ReadyForReview,
            [ready.GroupId, approved.GroupId, blocked.GroupId],
            [ready.Id, approved.Id, blocked.Id],
            "synthetic-operator",
            timestamp,
            timestamp);
        return new DispatchWorkspace("ui-test", [batch], [ready, approved, blocked], [], []);
    }

    private static DispatchWorkspace CreateSendDispatchBatchWorkspace()
    {
        var timestamp = new DateTimeOffset(2026, 8, 23, 12, 0, 0, TimeSpan.Zero);
        var batchId = Guid.NewGuid();
        var first = CreateDispatchItem(
            batchId,
            "Cliente A",
            DispatchItemState.Approved,
            timestamp,
            [],
            DispatchOperationMode.Send,
            1);
        var second = CreateDispatchItem(
            batchId,
            "Cliente B",
            DispatchItemState.Approved,
            timestamp.AddMinutes(1),
            [],
            DispatchOperationMode.Send,
            2);
        first = AddDispatchApproval(first, timestamp);
        second = AddDispatchApproval(second, timestamp.AddMinutes(1));
        var batch = new ProcessingBatch(
            batchId,
            "ui-test",
            ProcessingSelectionMode.Batch,
            DispatchOperationMode.Send,
            ProcessingBatchState.Approved,
            [first.GroupId, second.GroupId],
            [first.Id, second.Id],
            "synthetic-operator",
            timestamp,
            timestamp);
        return new DispatchWorkspace("ui-test", [batch], [first, second], [], []);
    }

    private static DispatchWorkspace CreateCompletedDispatchWorkspace(
        string providerKey,
        string senderAccountId)
    {
        var timestamp = new DateTimeOffset(2026, 8, 23, 12, 0, 0, TimeSpan.Zero);
        var batchId = Guid.NewGuid();
        var item = CreateDispatchItem(
            batchId,
            "Cliente com resultado",
            DispatchItemState.AcceptedByProvider,
            timestamp,
            [],
            DispatchOperationMode.Send,
            1);
        item = item with { Message = item.Message! with { SenderAccountId = senderAccountId } };
        var attempt = new DeliveryAttempt(
            Guid.NewGuid(),
            batchId,
            item.Id,
            item.GroupId,
            1,
            DispatchOperationMode.Send,
            DeliveryAttemptState.AcceptedByProvider,
            providerKey,
            "synthetic-idempotency-key",
            item.Message.DispatchFingerprint,
            "synthetic-provider-message-id",
            null,
            null,
            null,
            timestamp,
            timestamp.AddSeconds(1));
        var batch = new ProcessingBatch(
            batchId,
            "ui-test",
            ProcessingSelectionMode.Individual,
            DispatchOperationMode.Send,
            ProcessingBatchState.Completed,
            [item.GroupId],
            [item.Id],
            "synthetic-operator",
            timestamp,
            timestamp.AddSeconds(1));
        return new DispatchWorkspace("ui-test", [batch], [item], [attempt], []);
    }

    private static DispatchItem AddDispatchApproval(DispatchItem item, DateTimeOffset timestamp) => item with
    {
        Approval = new DispatchApprovalSnapshot(
            Guid.NewGuid(),
            item.Id,
            item.Revision,
            Guid.NewGuid(),
            "review-content-hash",
            item.Message!.DispatchFingerprint,
            "synthetic-operator",
            timestamp),
    };

    private static DispatchItem CreateDispatchItem(
        Guid batchId,
        string clientDisplayName,
        DispatchItemState state,
        DateTimeOffset timestamp,
        IReadOnlyList<DispatchBlock> blocks,
        DispatchOperationMode mode = DispatchOperationMode.Test,
        int attachmentCount = 0)
    {
        var itemId = Guid.NewGuid();
        var attachments = Enumerable.Range(0, attachmentCount)
            .Select(index => new DispatchAttachmentSnapshot(
                Guid.NewGuid(),
                $"/synthetic/document-{index + 1}.pdf",
                $"document-{index + 1}.pdf",
                new string((char)('a' + index), 64),
                1_024,
                RecognizedDocumentType.Payroll))
            .ToArray();
        var message = new RenderedMessageSnapshot(
            Guid.NewGuid(),
            1,
            "Assunto sintético",
            Guid.NewGuid(),
            1,
            "Corpo sintético",
            "fake://local",
            [],
            [],
            ["auditoria@example.invalid"],
            [],
            "Teste seguro",
            "Corpo sintético.",
            "<p>Corpo sintético.</p>",
            attachments,
            Convert.ToHexString(SHA256.HashData(itemId.ToByteArray())),
            timestamp);
        return new DispatchItem(
            itemId,
            batchId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            clientDisplayName,
            null,
            "08/2026",
            mode,
            FakeDeliveryScenario.Success,
            "auditoria@example.invalid",
            state,
            1,
            message,
            null,
            blocks,
            timestamp,
            timestamp);
    }

    private static DispatchWorkspace CreateFailedDispatchWorkspace(DeliveryAttemptState attemptState)
    {
        var timestamp = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        var itemId = Guid.NewGuid();
        var batchId = Guid.NewGuid();
        var groupId = Guid.NewGuid();
        var fingerprint = new string('a', 64);
        var message = new RenderedMessageSnapshot(
            Guid.NewGuid(),
            1,
            "Assunto sintético",
            Guid.NewGuid(),
            1,
            "Corpo sintético",
            "fake-account",
            [],
            [],
            ["auditoria@example.invalid"],
            [],
            "Teste seguro",
            "Corpo sintético.",
            "<p>Corpo sintético.</p>",
            [],
            fingerprint,
            timestamp);
        var approval = new DispatchApprovalSnapshot(
            Guid.NewGuid(),
            itemId,
            1,
            Guid.NewGuid(),
            "review-content-hash",
            fingerprint,
            "synthetic-operator",
            timestamp);
        var item = new DispatchItem(
            itemId,
            batchId,
            groupId,
            Guid.NewGuid(),
            "Cliente sintético",
            null,
            "08/2026",
            DispatchOperationMode.Test,
            FakeDeliveryScenario.TransientFailure,
            "auditoria@example.invalid",
            DispatchItemState.Failed,
            1,
            message,
            approval,
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
            DispatchWorkflowOptions.FakeProviderKey,
            $"ui-test:{itemId:N}:1",
            fingerprint,
            null,
            null,
            attemptState == DeliveryAttemptState.FailedTransient ? "TEMPORARY" : "PERMANENT",
            "Falha sintética.",
            timestamp,
            timestamp);
        return new DispatchWorkspace("ui-test", [], [item], [attempt], []);
    }

    private sealed class RecordingClientApprovalReviewService : IDocumentReviewService
    {
        private readonly DateTimeOffset timestamp = new(2026, 8, 24, 9, 30, 0, TimeSpan.Zero);
        private DocumentReviewWorkspace workspace;

        public RecordingClientApprovalReviewService()
        {
            ClientId = Guid.NewGuid();
            var augustGroupId = Guid.NewGuid();
            var septemberGroupId = Guid.NewGuid();
            var decemberGroupId = Guid.NewGuid();
            var augustDocuments = new[]
            {
                CreateReviewDocument(ClientId, augustGroupId, "folha-agosto.pdf", RecognizedDocumentType.Payroll, 2026, 8, timestamp),
                CreateReviewDocument(ClientId, augustGroupId, "fgts-agosto.pdf", RecognizedDocumentType.FgtsDigital, 2026, 8, timestamp),
                CreateReviewDocument(ClientId, augustGroupId, "pro-labore-agosto.pdf", RecognizedDocumentType.ProLabore, 2026, 8, timestamp),
            };
            var septemberDocument = CreateReviewDocument(
                ClientId,
                septemberGroupId,
                "folha-setembro.pdf",
                RecognizedDocumentType.Payroll,
                2026,
                9,
                timestamp);
            var decemberDocument = CreateReviewDocument(
                ClientId,
                decemberGroupId,
                "decimo-terceiro.pdf",
                RecognizedDocumentType.ThirteenthSalary,
                2026,
                12,
                timestamp);
            var groups = new[]
            {
                CreateReviewGroup(
                    augustGroupId,
                    ClientId,
                    "Empresa Sintética Multiperíodo",
                    "2026-08",
                    "08/2026",
                    augustDocuments.Select(document => document.Id).ToArray(),
                    timestamp),
                CreateReviewGroup(
                    septemberGroupId,
                    ClientId,
                    "Empresa Sintética Multiperíodo",
                    "2026-09",
                    "09/2026",
                    [septemberDocument.Id],
                    timestamp.AddMonths(1)),
                CreateReviewGroup(
                    decemberGroupId,
                    ClientId,
                    "Empresa Sintética Multiperíodo",
                    "2026-12",
                    "12/2026",
                    [decemberDocument.Id],
                    timestamp.AddMonths(4)),
            };
            ExpectedGroupIds = groups.Select(group => group.Id).ToArray();
            workspace = new DocumentReviewWorkspace(
                "ui-test",
                [.. augustDocuments, septemberDocument, decemberDocument],
                groups,
                []);
        }

        public Guid ClientId { get; }

        public IReadOnlyList<Guid> ExpectedGroupIds { get; }

        public Guid? ApprovedClientId { get; private set; }

        public IReadOnlyList<Guid> ApprovedGroupIds { get; private set; } = [];

        public int ApproveClientGroupsCallCount { get; private set; }

        public int ApproveGroupsCallCount { get; private set; }

        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(workspace);

        public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) =>
            Task.FromResult(workspace);

        public Task<DocumentReviewWorkspace> ApproveClientGroupsAsync(
            Guid clientId,
            IReadOnlyCollection<Guid> groupIds,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            ApproveClientGroupsCallCount++;
            ApprovedClientId = clientId;
            ApprovedGroupIds = groupIds.ToArray();
            var approvedIds = groupIds.ToHashSet();
            workspace = workspace with
            {
                Groups = workspace.Groups.Select(group => approvedIds.Contains(group.Id)
                    ? group with
                    {
                        State = ReviewGroupState.Approved,
                        ApprovalSnapshot = new GroupApprovalSnapshot(
                            Guid.NewGuid(),
                            group.Id,
                            group.Revision,
                            $"synthetic:{group.Id:N}",
                            "synthetic-operator",
                            timestamp,
                            []),
                    }
                    : group).ToArray(),
            };
            return Task.FromResult(workspace);
        }

        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(
            IReadOnlyCollection<Guid> groupIds,
            CancellationToken cancellationToken)
        {
            ApproveGroupsCallCount++;
            return Task.FromResult(workspace);
        }

        public Task<DocumentReviewWorkspace> ImportAsync(
            string localPath,
            DocumentRecognitionResult recognition,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(
            Guid documentId,
            DocumentPeriod period,
            string reason,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(
            Guid documentId,
            string reason,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(
            Guid documentId,
            string reason,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> OverrideClientAsync(
            Guid documentId,
            ClientResolutionCandidate candidate,
            string reason,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> SplitGroupAsync(
            Guid groupId,
            IReadOnlyCollection<Guid> documentIds,
            string reason,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> MergeGroupsAsync(
            Guid targetGroupId,
            Guid sourceGroupId,
            string reason,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupAsync(
            Guid groupId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }

    private sealed class CapturingReportDispatchService(DispatchWorkspace workspace) : IDispatchWorkflowService
    {
        public DispatchWorkspace Workspace { get; } = workspace;

        public List<DispatchReportFilter> Filters { get; } = [];

        public Task<DispatchWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(Workspace);

        public Task<DispatchReportResult> ExportReportsAsync(
            string directory,
            DispatchReportFilter filter,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Filters.Add(filter);
            return Task.FromResult(new DispatchReportResult(
                Path.Combine(directory, "relatorio-sintetico.xlsx"),
                [],
                Workspace.Items.Count,
                new DateTimeOffset(2026, 8, 24, 12, 30, 0, TimeSpan.Zero),
                Path.Combine(directory, "relatorio-sintetico.pdf")));
        }

        public Task<DispatchReportResult> ExportReportsAsync(
            string directory,
            CancellationToken cancellationToken) =>
            ExportReportsAsync(directory, DispatchReportFilter.AllPeriods, cancellationToken);

        public Task<DispatchWorkspace> PrepareAsync(
            PrepareDispatchRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ApproveAsync(
            Guid dispatchItemId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ApproveBatchAsync(
            Guid batchId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ExecuteAsync(
            ExecuteDispatchRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ExecuteBatchAsync(
            Guid batchId,
            string? confirmationPhrase,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ReconcileAsync(
            Guid dispatchItemId,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class RecordingDispatchService(DispatchWorkspace workspace) : IDispatchWorkflowService
    {
        public DispatchWorkspace Workspace { get; private set; } = workspace;

        public int ExecuteCount { get; private set; }

        public Task<DispatchWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(Workspace);

        public Task<DispatchWorkspace> ExecuteAsync(
            ExecuteDispatchRequest request,
            CancellationToken cancellationToken)
        {
            ExecuteCount++;
            Workspace = Workspace with
            {
                Items = Workspace.Items.Select(item => item.Id == request.DispatchItemId
                    ? item with { State = DispatchItemState.AcceptedByProvider }
                    : item).ToArray(),
            };
            return Task.FromResult(Workspace);
        }

        public Task<DispatchWorkspace> PrepareAsync(
            PrepareDispatchRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ApproveAsync(
            Guid dispatchItemId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ApproveBatchAsync(
            Guid batchId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ExecuteBatchAsync(
            Guid batchId,
            string? confirmationPhrase,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ReconcileAsync(
            Guid dispatchItemId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchReportResult> ExportReportsAsync(
            string directory,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class RecordingBatchDispatchService(DispatchWorkspace workspace) : IDispatchWorkflowService
    {
        public DispatchWorkspace Workspace { get; private set; } = workspace;

        public int ApproveBatchCount { get; private set; }

        public int ExecuteBatchCount { get; private set; }

        public string? LastConfirmationPhrase { get; private set; }

        public Task<DispatchWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(Workspace);

        public Task<DispatchWorkspace> ApproveBatchAsync(Guid batchId, CancellationToken cancellationToken)
        {
            ApproveBatchCount++;
            Workspace = Workspace with
            {
                Items = Workspace.Items.Select(item =>
                {
                    if (item.BatchId != batchId ||
                        item.State != DispatchItemState.ReadyForApproval ||
                        item.PreventsApproval ||
                        item.Message is null)
                    {
                        return item;
                    }

                    return item with
                    {
                        State = DispatchItemState.Approved,
                        Approval = new DispatchApprovalSnapshot(
                            Guid.NewGuid(),
                            item.Id,
                            item.Revision,
                            Guid.NewGuid(),
                            "review-content-hash",
                            item.Message.DispatchFingerprint,
                            "synthetic-operator",
                            item.UpdatedAtUtc),
                    };
                }).ToArray(),
            };
            return Task.FromResult(Workspace);
        }

        public Task<DispatchWorkspace> ExecuteBatchAsync(
            Guid batchId,
            string? confirmationPhrase,
            CancellationToken cancellationToken)
        {
            ExecuteBatchCount++;
            LastConfirmationPhrase = confirmationPhrase;
            Workspace = Workspace with
            {
                Items = Workspace.Items.Select(item => item.BatchId == batchId && item.IsApproved
                    ? item with { State = DispatchItemState.AcceptedByProvider }
                    : item).ToArray(),
                Batches = Workspace.Batches.Select(batch => batch.Id == batchId
                    ? batch with { State = ProcessingBatchState.Completed }
                    : batch).ToArray(),
            };
            return Task.FromResult(Workspace);
        }

        public Task<DispatchWorkspace> PrepareAsync(
            PrepareDispatchRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ApproveAsync(
            Guid dispatchItemId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ExecuteAsync(
            ExecuteDispatchRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchWorkspace> ReconcileAsync(
            Guid dispatchItemId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<DispatchReportResult> ExportReportsAsync(
            string directory,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class FakeAppUpdateService : IAppUpdateService
    {
        public AppUpdateChannel? CheckedChannel { get; private set; }

        public int ApplyCount { get; private set; }

        public Task<AppUpdateSnapshot> CheckAsync(
            AppUpdateChannel channel,
            CancellationToken cancellationToken)
        {
            CheckedChannel = channel;
            return Task.FromResult(new AppUpdateSnapshot(
                AppUpdateState.Available,
                "0.11.0",
                "0.11.1",
                channel,
                0,
                "Atualização sintética disponível."));
        }

        public Task<AppUpdateSnapshot> DownloadAsync(
            IProgress<int>? progress,
            CancellationToken cancellationToken)
        {
            progress?.Report(100);
            return Task.FromResult(new AppUpdateSnapshot(
                AppUpdateState.ReadyToRestart,
                "0.11.0",
                "0.11.1",
                CheckedChannel ?? AppUpdateChannel.Stable,
                100,
                "Atualização sintética conferida."));
        }

        public void ApplyAndRestart() => ApplyCount++;
    }

    private sealed class StaticEmailAccountConnectionService(EmailAccountConnectionStatus status)
        : IEmailAccountConnectionService
    {
        public Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken) =>
            Task.FromResult(status);

        public Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken) =>
            Task.FromResult(status);

        public Task DisconnectAsync(CancellationToken cancellationToken) => Task.CompletedTask;
    }

    private sealed class InMemoryClientCatalog(ClientDetails? initialClient = null)
        : IClientCatalogService, ILocalClientCatalogMaintenance
    {
        private ClientDetails? client = initialClient;
        private readonly List<MessageTemplateModel> templates = [];
        private int searchCallCount;
        private int statusChangeCount;

        public int SaveCount { get; private set; }

        public int TemplateSaveCount { get; private set; }

        public MessageTemplateMutationRequest? LastTemplateRequest { get; private set; }

        public int? FailOnSearchCall { get; init; }

        public bool FailOnGet { get; init; }

        public bool FailReadinessAfterStatusChange { get; init; }

        public bool FailOnGetAudit { get; set; }

        public IReadOnlyList<string> ReadinessBlockCodes { get; init; } = [];

        public IReadOnlyList<AuditEventModel> AuditTrail { get; init; } = [];

        public Task<ClientSearchResponse> SearchAsync(string? search, bool? isActive, PersonTypeModel? personType, CancellationToken cancellationToken)
        {
            searchCallCount++;
            if (searchCallCount == FailOnSearchCall)
            {
                throw new InvalidOperationException("Falha sintética ao pesquisar o catálogo.");
            }

            var normalizedSearch = new string((search ?? string.Empty).Where(char.IsLetterOrDigit).ToArray());
            var matchesFilter = client is not null &&
                (!isActive.HasValue || client.IsActive == isActive.Value) &&
                (string.IsNullOrWhiteSpace(normalizedSearch) ||
                    client.LegalNameOrFullName.Contains(search ?? string.Empty, StringComparison.OrdinalIgnoreCase) ||
                    client.PrimaryTaxId.Contains(normalizedSearch, StringComparison.Ordinal));
            var current = client!;
            IReadOnlyList<ClientListItem> items = !matchesFilter
                ? []
                : [new ClientListItem(
                    current.Id,
                    current.PersonType,
                    current.PreferredName ?? current.LegalNameOrFullName,
                    "**.***.***/****-81",
                    current.InternalCode,
                    current.IsActive,
                    current.Version,
                    current.Establishments.Count,
                    current.Recipients.Count,
                    current.UpdatedAtUtc)];
            return Task.FromResult(new ClientSearchResponse(items, items.Count, 0, 200));
        }

        public Task<ClientDetails?> GetAsync(Guid clientId, CancellationToken cancellationToken)
        {
            if (FailOnGet)
            {
                throw new InvalidOperationException("Falha sintética ao carregar o cadastro.");
            }

            return Task.FromResult(client?.Id == clientId ? client : null);
        }

        public Task<ClientDetails> SaveAsync(Guid? clientId, ClientMutationRequest request, CancellationToken cancellationToken)
        {
            SaveCount++;
            var now = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
            client = new ClientDetails(
                clientId ?? Guid.NewGuid(),
                request.PersonType,
                request.LegalNameOrFullName,
                request.PreferredName,
                request.InternalCode,
                new string(request.PrimaryTaxId.Where(char.IsAsciiDigit).ToArray()),
                request.IsActive,
                request.DefaultSubjectTemplateId,
                request.DefaultBodyTemplateId,
                request.Notes,
                request.ExpectedVersion + 1,
                now,
                now,
                request.Identifiers,
                request.Establishments,
                request.Recipients,
                request.Partners);
            return Task.FromResult(client);
        }

        public Task<ClientReadinessResponse?> GetReadinessAsync(Guid clientId, CancellationToken cancellationToken)
        {
            if (FailReadinessAfterStatusChange && statusChangeCount > 0)
            {
                throw new InvalidOperationException("Falha sintética ao atualizar a prontidão.");
            }

            return Task.FromResult<ClientReadinessResponse?>(new(
                clientId,
                ReadinessBlockCodes.Count == 0,
                ReadinessBlockCodes));
        }

        public Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(Guid? clientId, bool includeInactive, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<MessageTemplateModel>>(templates
                .Where(template => template.ClientId is null || template.ClientId == clientId)
                .Where(template => includeInactive || template.IsActive)
                .ToArray());

        public Task<MessageTemplateModel> SaveTemplateAsync(Guid? templateId, MessageTemplateMutationRequest request, CancellationToken cancellationToken)
        {
            TemplateSaveCount++;
            LastTemplateRequest = request;
            var now = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
            var template = new MessageTemplateModel(
                templateId ?? Guid.NewGuid(),
                request.ClientId,
                request.DocumentTypeId,
                request.Name,
                request.SubjectTemplate,
                request.BodyTemplate,
                request.SignatureMode,
                request.IsDefault,
                request.IsActive,
                request.ExpectedVersion + 1,
                now);
            var existingIndex = templates.FindIndex(item => item.Id == template.Id);
            if (existingIndex < 0)
            {
                templates.Add(template);
            }
            else
            {
                templates[existingIndex] = template;
            }

            return Task.FromResult(template);
        }

        public Task<ClientCatalogTransferDocument> ExportAsync(CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<ClientCatalogImportResult> ImportAsync(ClientCatalogImportRequest request, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<IReadOnlyList<AuditEventModel>> GetAuditAsync(Guid clientId, CancellationToken cancellationToken)
        {
            if (FailOnGetAudit)
            {
                throw new HttpRequestException("Falha sintética na auditoria cadastral.");
            }

            return Task.FromResult<IReadOnlyList<AuditEventModel>>(AuditTrail
                .Where(item => Guid.TryParse(item.EntityId, out var entityId) && entityId == clientId)
                .ToArray());
        }

        public Task<ClientDetails> SetClientActiveAsync(
            Guid clientId,
            long expectedVersion,
            bool isActive,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (client is null || client.Id != clientId)
            {
                throw new InvalidOperationException("Cliente sintético não encontrado.");
            }

            if (client.Version != expectedVersion)
            {
                throw new InvalidOperationException("Versão sintética divergente.");
            }

            if (client.IsActive == isActive)
            {
                return Task.FromResult(client);
            }

            SaveCount++;
            statusChangeCount++;
            client = client with
            {
                IsActive = isActive,
                Version = client.Version + 1,
                UpdatedAtUtc = client.UpdatedAtUtc.AddMinutes(1),
            };
            return Task.FromResult(client);
        }

        public Task<MessageTemplateModel> SetTemplateActiveAsync(
            Guid templateId,
            long expectedVersion,
            bool isActive,
            CancellationToken cancellationToken)
        {
            var template = templates.Single(item => item.Id == templateId);
            return SaveTemplateAsync(
                templateId,
                new MessageTemplateMutationRequest(
                    expectedVersion,
                    template.ClientId,
                    template.DocumentTypeId,
                    template.Name,
                    template.SubjectTemplate,
                    template.BodyTemplate,
                    template.SignatureMode,
                    template.IsDefault,
                    isActive),
                cancellationToken);
        }

        public Task<LocalCatalogArchiveResult> ArchiveClientAsync(
            Guid clientId,
            long expectedVersion,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<LocalCatalogArchiveResult> ArchiveTemplateAsync(
            Guid templateId,
            long expectedVersion,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private static MainViewModel CreateViewModel(IWorkspacePreferencesStore? preferencesStore = null) => new(
        new InMemoryClientCatalog(),
        new UnusedRecognitionService(),
        new EmptyReviewService(),
        null,
        null,
        preferencesStore);

    private sealed class InMemoryWorkspacePreferencesStore : IWorkspacePreferencesStore
    {
        private readonly object sync = new();
        private WorkspacePreferences? preferences;

        public WorkspacePreferences? SavedPreferences
        {
            get
            {
                lock (sync)
                {
                    return preferences;
                }
            }
        }

        public Task<WorkspacePreferences?> LoadAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (sync)
            {
                return Task.FromResult(preferences);
            }
        }

        public Task SaveAsync(WorkspacePreferences preferences, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (sync)
            {
                this.preferences = preferences;
            }

            return Task.CompletedTask;
        }
    }

    private sealed class UnusedRecognitionService : IDocumentRecognitionService
    {
        public Task<DocumentRecognitionResult> RecognizeAsync(string filePath, CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }

    private sealed class PeriodRecognitionService : IDocumentRecognitionService
    {
        public Task<DocumentRecognitionResult> RecognizeAsync(string filePath, CancellationToken cancellationToken)
        {
            var month = Path.GetFileName(filePath).StartsWith("agosto", StringComparison.Ordinal) ? 8 : 9;
            var bytes = File.ReadAllBytes(filePath);
            return Task.FromResult(new DocumentRecognitionResult(
                Path.GetFileName(filePath),
                Convert.ToHexString(SHA256.HashData(bytes)),
                "application/pdf",
                bytes.Length,
                1,
                RecognizedDocumentType.Payroll,
                "ui-test",
                RecognitionConfidence.High,
                .95m,
                false,
                false,
                [new RecognizedField(
                    "Competencia",
                    $"{month:00}/2026",
                    $"{month:00}/2026",
                    SemanticFieldRole.Competence,
                    .99m,
                    new EvidenceBox(1, 0, 0, 10, 10, "competência sintética"))],
                ClientResolutionResult.Unresolved("client.not_resolved"),
                []));
        }
    }

    private sealed class ImportingReviewService : IDocumentReviewService
    {
        private readonly DocumentPeriodParser parser = new();

        public DocumentReviewWorkspace Workspace { get; private set; } = DocumentReviewWorkspace.Empty("ui-test");

        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) => Task.FromResult(Workspace);

        public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) => Task.FromResult(Workspace);

        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(Guid documentId, DocumentPeriod period, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ImportAsync(string localPath, DocumentRecognitionResult recognition, CancellationToken cancellationToken)
        {
            var now = new DateTimeOffset(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
            var document = new ReviewDocument(
                Guid.NewGuid(),
                localPath,
                recognition.FileName,
                recognition.Sha256,
                recognition.FileSizeBytes,
                recognition.PageCount,
                recognition.DocumentType,
                recognition.ProfileVersion,
                null,
                null,
                null,
                null,
                ClientResolutionMethod.None,
                0m,
                [],
                ["client.not_resolved"],
                recognition.Fields,
                recognition.Findings,
                parser.Parse(recognition.Fields),
                string.Empty,
                ReviewDocumentState.Blocked,
                1,
                null,
                [],
                now,
                now);
            Workspace = Workspace with { Documents = [.. Workspace.Documents, document] };
            return Task.FromResult(Workspace);
        }

        public Task<DocumentReviewWorkspace> OverrideClientAsync(Guid documentId, ClientResolutionCandidate candidate, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> SplitGroupAsync(Guid groupId, IReadOnlyCollection<Guid> documentIds, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> MergeGroupsAsync(Guid targetGroupId, Guid sourceGroupId, string reason, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> ApproveGroupAsync(Guid groupId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(IReadOnlyCollection<Guid> groupIds, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class StaticReviewService(DocumentReviewWorkspace workspace) : IDocumentReviewService
    {
        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(workspace);

        public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) =>
            Task.FromResult(workspace);

        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(Guid documentId, DocumentPeriod period, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ImportAsync(string localPath, DocumentRecognitionResult recognition, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> OverrideClientAsync(Guid documentId, ClientResolutionCandidate candidate, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> SplitGroupAsync(Guid groupId, IReadOnlyCollection<Guid> documentIds, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> MergeGroupsAsync(Guid targetGroupId, Guid sourceGroupId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupAsync(Guid groupId, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(IReadOnlyCollection<Guid> groupIds, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }

    private sealed class EmptyReviewService : IDocumentReviewService
    {
        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(DocumentReviewWorkspace.Empty("ui-test"));

        public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) =>
            Task.FromResult(DocumentReviewWorkspace.Empty("ui-test"));

        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(Guid documentId, DocumentPeriod period, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ImportAsync(string localPath, DocumentRecognitionResult recognition, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> OverrideClientAsync(Guid documentId, ClientResolutionCandidate candidate, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> SplitGroupAsync(Guid groupId, IReadOnlyCollection<Guid> documentIds, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> MergeGroupsAsync(Guid targetGroupId, Guid sourceGroupId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupAsync(Guid groupId, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(IReadOnlyCollection<Guid> groupIds, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }

    private sealed class FailingImportReviewService : IDocumentReviewService
    {
        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            Task.FromResult(DocumentReviewWorkspace.Empty("ui-test"));

        public Task<DocumentReviewWorkspace> ImportAsync(
            string localPath,
            DocumentRecognitionResult recognition,
            CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Falha sintética de persistência.");

        public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(Guid documentId, DocumentPeriod period, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> OverrideClientAsync(Guid documentId, ClientResolutionCandidate candidate, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> SplitGroupAsync(Guid groupId, IReadOnlyCollection<Guid> documentIds, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> MergeGroupsAsync(Guid targetGroupId, Guid sourceGroupId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupAsync(Guid groupId, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(IReadOnlyCollection<Guid> groupIds, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }

    private sealed class PeriodBatchReviewService : IDocumentReviewService
    {
        private readonly DateTimeOffset timestamp = new(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
        private DocumentReviewWorkspace workspace;

        public PeriodBatchReviewService()
        {
            AugustGroupId = Guid.NewGuid();
            SeptemberGroupId = Guid.NewGuid();
            AugustAuditId = Guid.NewGuid();
            SeptemberAuditId = Guid.NewGuid();
            workspace = new DocumentReviewWorkspace(
                "ui-test",
                [],
                [CreateGroup(AugustGroupId, "2026-08", "08/2026"), CreateGroup(SeptemberGroupId, "2026-09", "09/2026")],
                [
                    CreateAudit(AugustAuditId, AugustGroupId, "agosto"),
                    CreateAudit(SeptemberAuditId, SeptemberGroupId, "setembro"),
                ]);
        }

        public Guid AugustGroupId { get; }

        public Guid SeptemberGroupId { get; }

        public Guid AugustAuditId { get; }

        public Guid SeptemberAuditId { get; }

        public List<Guid> ApprovedGroupIds { get; } = [];

        public int ApproveGroupsCallCount { get; private set; }

        public string ApprovedDocumentStateSummary => new ReviewDocument(
            Guid.NewGuid(),
            "synthetic.pdf",
            "synthetic.pdf",
            new string('A', 64),
            100,
            1,
            RecognizedDocumentType.Payroll,
            "synthetic-profile",
            null,
            null,
            null,
            null,
            ClientResolutionMethod.None,
            0m,
            [],
            [],
            [],
            [],
            new DocumentPeriod(DocumentPeriodKind.Unknown, null, null, null, null, null, null),
            string.Empty,
            ReviewDocumentState.Approved,
            1,
            null,
            [],
            timestamp,
            timestamp).ReviewSummary;

        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) => Task.FromResult(workspace);

        public Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken) => Task.FromResult(workspace);

        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(Guid documentId, DocumentPeriod period, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(Guid documentId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ImportAsync(string localPath, DocumentRecognitionResult recognition, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> OverrideClientAsync(Guid documentId, ClientResolutionCandidate candidate, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> SplitGroupAsync(Guid groupId, IReadOnlyCollection<Guid> documentIds, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> MergeGroupsAsync(Guid targetGroupId, Guid sourceGroupId, string reason, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<DocumentReviewWorkspace> ApproveGroupAsync(Guid groupId, CancellationToken cancellationToken)
            => ApproveGroupsAsync([groupId], cancellationToken);

        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(
            IReadOnlyCollection<Guid> groupIds,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            ApproveGroupsCallCount++;
            var requestedIds = groupIds.ToHashSet();
            ApprovedGroupIds.AddRange(requestedIds);
            workspace = workspace with
            {
                Groups = workspace.Groups.Select(group => requestedIds.Contains(group.Id)
                    ? group with
                    {
                        State = ReviewGroupState.Approved,
                        ApprovalSnapshot = new GroupApprovalSnapshot(
                            Guid.NewGuid(),
                            group.Id,
                            group.Revision,
                            "synthetic-hash",
                            "synthetic-operator",
                            timestamp,
                            []),
                    }
                    : group).ToArray(),
            };
            return Task.FromResult(workspace);
        }

        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) =>
            throw new InvalidOperationException("A interface não deve chamar a aprovação global.");

        private DocumentDispatchGroup CreateGroup(Guid id, string periodKey, string periodLabel) => new(
            id,
            $"synthetic|{periodKey}",
            "synthetic-policy",
            "1",
            Guid.NewGuid(),
            null,
            $"Cliente {periodLabel}",
            periodKey,
            periodLabel,
            ReviewGroupState.ReadyForReview,
            1,
            [Guid.NewGuid()],
            [],
            null,
            timestamp,
            timestamp);

        private ReviewAuditEvent CreateAudit(Guid id, Guid groupId, string correlationSuffix) => new(
            id,
            "ui-test",
            "synthetic-operator",
            timestamp,
            "group.approved",
            null,
            groupId,
            null,
            null,
            "Evento sintético",
            $"period-{correlationSuffix}");
    }
}
