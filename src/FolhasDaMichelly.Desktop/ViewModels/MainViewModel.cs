using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.History;
using FolhasDaMichelly.Application.Incidents;
using FolhasDaMichelly.Application.Pilot;
using FolhasDaMichelly.Application.Preferences;
using FolhasDaMichelly.Application.Production;
using FolhasDaMichelly.Application.Security;
using FolhasDaMichelly.Application.Updates;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Desktop.Models;
using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Desktop.ViewModels;

public partial class MainViewModel : ViewModelBase
{
    private readonly IClientCatalogService? catalogService;
    private readonly DocumentPeriodParser documentPeriodParser = new();
    private readonly IDocumentRecognitionService? documentRecognitionService;
    private readonly IDocumentReviewService? documentReviewService;
    private readonly IDispatchWorkflowService? dispatchWorkflowService;
    private readonly IEmailAccountConnectionService? emailAccountConnectionService;
    private readonly IWorkspacePreferencesStore? workspacePreferencesStore;
    private readonly IProtectedBackupService? protectedBackupService;
    private readonly IIncidentService? incidentService;
    private readonly IAppUpdateService? appUpdateService;
    private readonly IPilotReadinessService? pilotReadinessService;
    private readonly PilotModeOptions pilotModeOptions = new();
    private readonly ProductionRolloutOptions productionRolloutOptions = new();
    private readonly ProductionReadinessSnapshot productionReadinessSnapshot =
        ProductionReadinessEvaluator.Evaluate(new ProductionRolloutOptions(), false, false, false);
    private PilotReadinessSnapshot? pilotReadinessSnapshot;
    private IReadOnlyList<DispatchOperationMode> dispatchOperationModes =
        Enum.GetValues<DispatchOperationMode>();
    private static readonly JsonSerializerOptions TransferSerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
    };
    private Guid? currentClientId;
    private Guid? editingTemplateId;
    private long editingTemplateVersion;
    private Guid? editingTemplateDocumentTypeId;
    private SignatureModeModel editingTemplateSignatureMode = SignatureModeModel.Organization;
    private bool editingTemplateIsDefault;
    private bool editingTemplateIsActive = true;
    private Guid? defaultSubjectTemplateId;
    private Guid? defaultBodyTemplateId;
    private long currentVersion;
    private bool isApplyingPreferences;
    private bool isLoadingPreferences;
    private bool isRebuildingOperationalYears;
    private bool isFormattingTaxId;
    private bool preferencesLoaded;
    private readonly object preferencesSaveSync = new();
    private readonly Dictionary<Guid, string> clientTaxIds = [];
    private Task pendingPreferencesSave = Task.CompletedTask;
    private CancellationTokenSource? documentImportCancellation;
    private HistoryVisibilityState historyVisibility = HistoryVisibilityState.Empty;
    private HistoryFilterCriteria activeHistoryFilter = HistoryFilterCriteria.All;
    private Guid? loadedHistoryCatalogClientId;
    private bool partnerInputErrorTargetsCpf;

    public MainViewModel()
    {
        RefreshAllowedDispatchModes();
        InitializePeriodOptions(DateTimeOffset.Now.Year, DateTimeOffset.Now.Month);
    }

    public MainViewModel(IAppUpdateService appUpdateService)
        : this()
    {
        this.appUpdateService = appUpdateService;
    }

    public MainViewModel(PilotModeOptions pilotModeOptions)
        : this()
    {
        this.pilotModeOptions = pilotModeOptions ?? throw new ArgumentNullException(nameof(pilotModeOptions));
        RefreshAllowedDispatchModes();
    }

    public MainViewModel(
        ProductionRolloutOptions productionRolloutOptions,
        ProductionReadinessSnapshot productionReadinessSnapshot)
        : this()
    {
        this.productionRolloutOptions = productionRolloutOptions ??
            throw new ArgumentNullException(nameof(productionRolloutOptions));
        this.productionReadinessSnapshot = productionReadinessSnapshot ??
            throw new ArgumentNullException(nameof(productionReadinessSnapshot));
        RefreshAllowedDispatchModes();
    }

    public MainViewModel(
        IClientCatalogService catalogService,
        IDocumentRecognitionService documentRecognitionService,
        IDocumentReviewService documentReviewService)
        : this(catalogService, documentRecognitionService, documentReviewService, null)
    {
    }

    public MainViewModel(
        IClientCatalogService catalogService,
        IDocumentRecognitionService documentRecognitionService,
        IDocumentReviewService documentReviewService,
        IDispatchWorkflowService? dispatchWorkflowService)
        : this(catalogService, documentRecognitionService, documentReviewService, dispatchWorkflowService, null)
    {
    }

    public MainViewModel(
        IClientCatalogService catalogService,
        IDocumentRecognitionService documentRecognitionService,
        IDocumentReviewService documentReviewService,
        IDispatchWorkflowService? dispatchWorkflowService,
        IEmailAccountConnectionService? emailAccountConnectionService)
        : this(
            catalogService,
            documentRecognitionService,
            documentReviewService,
            dispatchWorkflowService,
            emailAccountConnectionService,
            null,
            null)
    {
    }

    public MainViewModel(
        IClientCatalogService catalogService,
        IDocumentRecognitionService documentRecognitionService,
        IDocumentReviewService documentReviewService,
        IDispatchWorkflowService? dispatchWorkflowService,
        IEmailAccountConnectionService? emailAccountConnectionService,
        IWorkspacePreferencesStore? workspacePreferencesStore,
        IProtectedBackupService? protectedBackupService = null,
        IIncidentService? incidentService = null,
        IAppUpdateService? appUpdateService = null,
        IPilotReadinessService? pilotReadinessService = null,
        PilotModeOptions? pilotModeOptions = null,
        ProductionRolloutOptions? productionRolloutOptions = null,
        ProductionReadinessSnapshot? productionReadinessSnapshot = null)
    {
        this.catalogService = catalogService;
        this.documentRecognitionService = documentRecognitionService;
        this.documentReviewService = documentReviewService;
        this.dispatchWorkflowService = dispatchWorkflowService;
        this.emailAccountConnectionService = emailAccountConnectionService;
        this.workspacePreferencesStore = workspacePreferencesStore;
        this.protectedBackupService = protectedBackupService;
        this.incidentService = incidentService;
        this.appUpdateService = appUpdateService;
        this.pilotReadinessService = pilotReadinessService;
        this.pilotModeOptions = pilotModeOptions ?? new PilotModeOptions();
        this.productionRolloutOptions = productionRolloutOptions ?? new ProductionRolloutOptions();
        this.productionReadinessSnapshot = productionReadinessSnapshot ??
            ProductionReadinessEvaluator.Evaluate(this.productionRolloutOptions, false, false, false);
        RefreshAllowedDispatchModes();

        InitializePeriodOptions(DateTimeOffset.Now.Year, DateTimeOffset.Now.Month);
    }

    public string ProductName { get; } = "Folhas da Michelly";
    public string Subtitle { get; } = "Clientes, documentos e envios em um fluxo simples e seguro";
    public string Phase { get; } = "Fase 12 — Produção gradual";
    public string Environment { get; } = ".NET 10 LTS • Avalonia 12 • Windows + macOS";
    public string CurrentApplicationVersion { get; } = AppVersionInfo.Current;
    public IReadOnlyList<PersonTypeModel> PersonTypes { get; } = Enum.GetValues<PersonTypeModel>();
    public IReadOnlyList<ClientIdentifierTypeModel> IdentifierTypes { get; } =
    [
        ClientIdentifierTypeModel.LegalNameAlias,
        ClientIdentifierTypeModel.InternalCode,
        ClientIdentifierTypeModel.Other,
    ];
    public IReadOnlyList<DeliveryRoleModel> DeliveryRoles { get; } =
        Enum.GetValues<DeliveryRoleModel>();
    public ObservableCollection<ClientListRow> Clients { get; } = [];
    public ObservableCollection<ClientListRow> ReportClients { get; } = [];
    public ObservableCollection<HistoryClientFilterOption> HistoryClientFilters { get; } = [];
    public ObservableCollection<HistoryDocumentFilterOption> HistoryDocumentFilters { get; } = [];
    public ObservableCollection<ClientIdentifierModel> Identifiers { get; } = [];
    public ObservableCollection<EstablishmentModel> Establishments { get; } = [];
    public ObservableCollection<RecipientModel> Recipients { get; } = [];
    public ObservableCollection<ClientPartnerModel> Partners { get; } = [];
    public ObservableCollection<MessageTemplateModel> Templates { get; } = [];
    public ObservableCollection<AuditEventModel> AuditEvents { get; } = [];
    public ObservableCollection<AuditEventModel> HistoryCatalogAuditEvents { get; } = [];
    public ObservableCollection<DocumentRecognitionResult> RecognizedDocuments { get; } = [];
    public ObservableCollection<ReviewDocument> ReviewDocuments { get; } = [];
    public ObservableCollection<DocumentDispatchGroup> ReviewGroups { get; } = [];
    public ObservableCollection<ReviewAuditEvent> ReviewAuditEvents { get; } = [];
    public ObservableCollection<ProcessingBatch> ProcessingBatches { get; } = [];
    public ObservableCollection<DispatchItem> DispatchItems { get; } = [];
    public ObservableCollection<DeliveryAttempt> DeliveryAttempts { get; } = [];
    public ObservableCollection<DispatchAuditEvent> DispatchAuditEvents { get; } = [];
    public ObservableCollection<IncidentRecord> Incidents { get; } = [];
    public ObservableCollection<IncidentAuditEvent> IncidentAuditEvents { get; } = [];
    public IReadOnlyList<IncidentCategoryOption> IncidentCategories { get; } =
    [
        new(IncidentCategory.PotentialWrongRecipient, "Possível destinatário incorreto"),
        new(IncidentCategory.PotentialWrongAttachment, "Possível anexo incorreto"),
        new(IncidentCategory.DuplicateDelivery, "Possível envio repetido"),
        new(IncidentCategory.AmbiguousProviderResult, "Resultado incerto do provedor"),
        new(IncidentCategory.CredentialExposure, "Possível exposição de acesso"),
        new(IncidentCategory.LocalDataExposure, "Possível exposição de arquivo local"),
        new(IncidentCategory.Other, "Outra ocorrência"),
    ];
    public IReadOnlyList<IncidentSeverityOption> IncidentSeverities { get; } =
    [
        new(IncidentSeverity.Low, "Baixa"),
        new(IncidentSeverity.Medium, "Média"),
        new(IncidentSeverity.High, "Alta"),
        new(IncidentSeverity.Critical, "Crítica"),
    ];
    public IReadOnlyList<IncidentStatusOption> IncidentStatuses { get; } =
    [
        new(IncidentStatus.Contained, "Contido"),
        new(IncidentStatus.Investigating, "Em apuração"),
        new(IncidentStatus.Resolved, "Resolvido"),
        new(IncidentStatus.Closed, "Encerrado"),
    ];
    public IReadOnlyList<DispatchOperationMode> DispatchOperationModes => dispatchOperationModes;
    public IReadOnlyList<FakeDeliveryScenario> FakeDeliveryScenarios { get; } =
        Enum.GetValues<FakeDeliveryScenario>();
    public IReadOnlyList<ClientPartnerRoleModel> PartnerRoles { get; } =
        Enum.GetValues<ClientPartnerRoleModel>();
    public IReadOnlyList<TemplatePlaceholderTargetOption> TemplatePlaceholderTargetOptions { get; } =
    [
        new("body", "Texto do e-mail"),
        new("subject", "Assunto do e-mail"),
    ];
    public ObservableCollection<OperationalYearOption> OperationalYears { get; } = [];
    public IReadOnlyList<OperationalMonthOption> OperationalMonths { get; } =
        OperationalMonthOption.Create();
    public IReadOnlyList<RetentionOption> RetentionOptions { get; } =
    [
        new(0, "Sem exclusão automática"),
        new(2, "Revisar itens com mais de 2 meses"),
        new(3, "Revisar itens com mais de 3 meses"),
        new(6, "Revisar itens com mais de 6 meses"),
        new(12, "Revisar após 1 ano"),
        new(24, "Revisar após 2 anos"),
        new(60, "Revisar após 5 anos"),
        new(120, "Revisar após 10 anos"),
    ];
    public IReadOnlyList<ReleaseChannelOption> ReleaseChannels { get; } =
    [
        new(
            AppUpdateChannel.Stable,
            "Estável",
            "Versões promovidas depois dos testes e da aprovação operacional."),
        new(
            AppUpdateChannel.Beta,
            "Beta",
            "Antecipação para uma máquina de teste; pode mudar antes da versão estável."),
    ];
    public IReadOnlyList<ReportScopeOption> ReportScopes { get; } =
    [
        new(DispatchReportScope.Month, "Um mês", "Escolha uma competência mensal sem alterar o período de trabalho do restante do aplicativo."),
        new(DispatchReportScope.Year, "Um ano", "Inclui todos os meses do ano escolhido."),
        new(DispatchReportScope.Range, "Intervalo de meses", "Inclui as competências entre o mês inicial e o final, inclusive."),
        new(DispatchReportScope.AllPeriods, "Todos os períodos", "Consolida todo o acervo disponível nesta instalação."),
    ];
    public IReadOnlyList<ReportClientFilterOption> ReportClientFilters { get; } =
    [
        new(ReportClientFilterKind.AllClients, "Todos os clientes", "Inclui todos os clientes encontrados no período escolhido."),
        new(ReportClientFilterKind.SelectedClient, "Um cliente específico", "Inclui somente o cliente escolhido, sem perder o filtro de período."),
    ];
    public IReadOnlyList<DispatchQueueFilterOption> DispatchQueueFilters { get; } =
    [
        new(DispatchQueueFilterKind.Pending, "A fazer"),
        new(DispatchQueueFilterKind.Completed, "Concluídas"),
        new(DispatchQueueFilterKind.All, "Todas"),
    ];
    public IReadOnlyList<HistoryHourOption> HistoryHourOptions { get; } = HistoryHourOption.Create();
    public IReadOnlyList<HistoryCleanupScopeOption> HistoryCleanupScopes { get; } =
    [
        new(HistoryVisibilityRuleKind.AllUntilNow, "Tudo que aparece agora", "Oculta da tela todo o histórico atual; novos acontecimentos continuarão aparecendo."),
        new(HistoryVisibilityRuleKind.SelectedOperationalPeriod, "Competência selecionada", "Oculta somente ações ligadas ao mês e ano escolhidos no cabeçalho."),
        new(HistoryVisibilityRuleKind.CalendarDay, "Um dia", "Oculta as ações do dia escolhido."),
        new(HistoryVisibilityRuleKind.ClockHour, "Uma hora", "Oculta as ações da hora escolhida."),
        new(HistoryVisibilityRuleKind.CustomInterval, "Intervalo personalizado", "Oculta as ações entre as datas e horas informadas."),
    ];
    public IReadOnlyList<HistoryTimeScopeOption> HistoryTimeScopes { get; } =
    [
        new(HistoryTimeScope.All, "Todo o histórico", "Mostra todos os dias preservados, respeitando a competência escolhida no cabeçalho."),
        new(HistoryTimeScope.CalendarDay, "Um dia específico", "Mostra somente as ações registradas no dia escolhido."),
        new(HistoryTimeScope.CalendarMonth, "Um mês específico", "Mostra as ações de todo o mês e ano escolhidos."),
        new(HistoryTimeScope.CalendarYear, "Um ano específico", "Mostra as ações de todo o ano escolhido."),
        new(HistoryTimeScope.CustomInterval, "Intervalo com data e hora", "Mostra as ações entre o início e o fim informados."),
    ];

    [ObservableProperty]
    public partial string StatusMessage { get; set; } =
        "Tudo pronto. Escolha uma área no menu ou importe os documentos do mês.";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasClientFeedback))]
    public partial string ClientFeedbackMessage { get; set; } =
        "Preencha os dados essenciais. Campos opcionais podem ficar em branco.";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ClientFeedbackBackground))]
    [NotifyPropertyChangedFor(nameof(ClientFeedbackBorderBrush))]
    [NotifyPropertyChangedFor(nameof(ClientFeedbackForeground))]
    [NotifyPropertyChangedFor(nameof(ClientFeedbackIcon))]
    public partial bool ClientFeedbackIsError { get; set; }

    [ObservableProperty]
    public partial bool IsSavingClient { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ClientListPanelActionLabel))]
    public partial bool IsClientListPanelExpanded { get; set; } = true;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ClientEditorPanelActionLabel))]
    public partial bool IsClientEditorPanelExpanded { get; set; } = true;

    public bool HasClientFeedback => !string.IsNullOrWhiteSpace(ClientFeedbackMessage);

    public string ClientFeedbackBackground => ClientFeedbackIsError ? "#FFF0ED" : "#EDF5EA";

    public string ClientFeedbackBorderBrush => ClientFeedbackIsError ? "#D99A8C" : "#A8C9A5";

    public string ClientFeedbackForeground => ClientFeedbackIsError ? "#7A3328" : "#315E36";

    public string ClientFeedbackIcon => ClientFeedbackIsError ? "!" : "✓";

    public string ClientListPanelActionLabel => IsClientListPanelExpanded
        ? "Recolher lista"
        : "Mostrar lista";

    public string ClientEditorPanelActionLabel => IsClientEditorPanelExpanded
        ? "Recolher cadastro"
        : "Mostrar cadastro";

    [ObservableProperty]
    public partial string TechnicalDetails { get; set; } =
        "Modo protegido ativo. Integrações externas permanecem sujeitas às autorizações e travas de segurança.";

    [ObservableProperty]
    public partial bool PilotNonProductionDataConfirmed { get; set; }

    [ObservableProperty]
    public partial bool PilotControlledAccountConfirmed { get; set; }

    [ObservableProperty]
    public partial bool PilotMacOsStationValidated { get; set; }

    [ObservableProperty]
    public partial bool PilotWindowsStationValidated { get; set; }

    [ObservableProperty]
    public partial bool PilotBackupRestoreValidated { get; set; }

    [ObservableProperty]
    public partial bool PilotRollbackValidated { get; set; }

    [ObservableProperty]
    public partial bool IsPilotReady { get; set; }

    [ObservableProperty]
    public partial int PilotChecklistProgress { get; set; }

    [ObservableProperty]
    public partial string PilotStatusTitle { get; set; } = "Em preparação";

    [ObservableProperty]
    public partial string PilotStatusSummary { get; set; } =
        "Conclua o checklist nas duas plataformas antes de iniciar o uso supervisionado.";

    [ObservableProperty]
    public partial string PilotMetricsSummary { get; set; } =
        "Nenhuma operação do piloto foi registrada nesta instalação.";

    public bool IsPilotMode => pilotModeOptions.Enabled;

    public string PilotEnvironmentLabel => string.Equals(
        pilotModeOptions.EnvironmentName,
        "staging",
        StringComparison.OrdinalIgnoreCase)
        ? "Ambiente de homologação"
        : "Ambiente não validado";

    public string PilotSafeguardSummary => pilotModeOptions.AllowSend
        ? "Atenção: a trava de envio do piloto está aberta."
        : "Teste e Rascunho disponíveis. Envio aos clientes bloqueado.";

    public bool IsProductionRolloutVisible =>
        productionRolloutOptions.EnforceForExternalSend &&
        productionRolloutOptions.Stage != ProductionRolloutStage.Closed;

    public bool IsProductionReady => productionReadinessSnapshot.IsReadyForSend;

    public string ProductionStatusTitle => productionReadinessSnapshot.IsReadyForSend
        ? "Abertura gradual autorizada"
        : "Produção bloqueada";

    public string ProductionStatusSummary => productionReadinessSnapshot.IsReadyForSend
        ? "Os controles formais estão válidos. Cada envio ainda exige papel autorizado, MFA, aprovação e preflight central."
        : productionReadinessSnapshot.Blockers.Count > 0
            ? productionReadinessSnapshot.Blockers[0]
            : "A produção permanece fechada até a aprovação de todos os controles externos.";

    public string ProductionStageLabel => productionRolloutOptions.Stage switch
    {
        ProductionRolloutStage.Limited => "Etapa limitada",
        ProductionRolloutStage.Gradual => "Etapa gradual",
        _ => "Etapa fechada",
    };

    public string ProductionLimitsSummary =>
        $"Até {productionRolloutOptions.MaximumBatchSize} mensagem(ns) por sequência e " +
        $"{productionRolloutOptions.MaximumDailySends} por dia, com contagem central por organização.";

    public string ProductionRolesSummary => productionReadinessSnapshot.AllowedRoles.Count == 0
        ? "Nenhum papel possui liberação válida."
        : "Papéis previstos: Gestor, Administrador e Proprietário técnico — sempre com MFA.";

    public string ProductionPilotGate => GateLabel(
        productionRolloutOptions.PilotApproved && !pilotModeOptions.Enabled,
        "Piloto aceito formalmente");

    public string ProductionStableGate => GateLabel(
        productionRolloutOptions.StableReleaseApproved,
        "Stable assinada e aprovada");

    public string ProductionBackupGate => GateLabel(
        productionRolloutOptions.BackupRestoreDrillCompleted,
        "Backup e restauração exercitados");

    public string ProductionMonitoringGate => GateLabel(
        productionRolloutOptions.MonitoringReady,
        "Monitoramento e alertas prontos");

    public string ProductionIncidentGate => GateLabel(
        productionRolloutOptions.IncidentResponseReady,
        "Resposta a incidentes validada");

    public string ProductionSupportGate => GateLabel(
        productionRolloutOptions.SupportReady,
        "Suporte e responsáveis confirmados");

    [ObservableProperty]
    public partial string EmailProviderConnectionStatus { get; set; } =
        "Nenhuma conta de e-mail foi conectada nesta instalação.";

    [ObservableProperty]
    public partial string EmailProviderDisplayName { get; set; } = "Modo seguro local";

    [ObservableProperty]
    public partial string EmailProviderConnectionHelp { get; set; } =
        "O modo Teste seguro funciona sem conta. Gmail e Outlook serão liberados após o registro oficial desta instalação em cada provedor.";

    [ObservableProperty]
    public partial bool CanConnectEmailAccount { get; set; }

    [ObservableProperty]
    public partial bool CanDisconnectEmailAccount { get; set; }

    [ObservableProperty]
    public partial bool IsEmailAccountConnected { get; set; }

    [ObservableProperty]
    public partial bool IsExternalEmailProviderConfigured { get; set; }

    [ObservableProperty]
    public partial string ActiveEmailProviderKey { get; set; } = DispatchWorkflowOptions.FakeProviderKey;

    [ObservableProperty]
    public partial bool KeepEmailSession { get; set; } = true;

    [ObservableProperty]
    public partial RetentionOption SelectedRetentionOption { get; set; } =
        new(0, "Sem exclusão automática");

    [ObservableProperty]
    public partial ReleaseChannelOption SelectedReleaseChannel { get; set; } = new(
        AppUpdateChannel.Stable,
        "Estável",
        "Versões promovidas depois dos testes e da aprovação operacional.");

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanCheckForUpdates))]
    public partial bool IsUpdatingApplication { get; set; }

    [ObservableProperty]
    public partial int AppUpdateProgress { get; set; }

    [ObservableProperty]
    public partial string AppUpdateSummary { get; set; } =
        "Nenhuma atualização é aplicada automaticamente. A verificação depende do canal oficial configurado.";

    [ObservableProperty]
    public partial bool CanDownloadApplicationUpdate { get; set; }

    [ObservableProperty]
    public partial bool CanApplyApplicationUpdate { get; set; }

    public bool CanCheckForUpdates => !IsUpdatingApplication;

    [ObservableProperty]
    public partial string RetentionReviewSummary { get; set; } =
        "Nenhum documento é excluído automaticamente. Escolha um prazo apenas para localizar itens que merecem revisão.";

    [ObservableProperty]
    public partial string InputFolderPath { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string DocumentArchiveDirectory { get; set; } = Path.Combine(
        System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData),
        "FolhasDaMichelly",
        "Documentos");

    [ObservableProperty]
    public partial string ReportOutputDirectory { get; set; } = Path.Combine(
        System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData),
        "FolhasDaMichelly",
        "Reports");

    [ObservableProperty]
    public partial bool IncludeSubfolders { get; set; } = true;

    [ObservableProperty]
    public partial OperationalYearOption? SelectedOperationalYear { get; set; }

    [ObservableProperty]
    public partial OperationalMonthOption? SelectedOperationalMonth { get; set; }

    [ObservableProperty]
    public partial DocumentRecognitionResult? SelectedRecognizedDocument { get; set; }

    [ObservableProperty]
    public partial ReviewDocument? SelectedReviewDocument { get; set; }

    [ObservableProperty]
    public partial DocumentDispatchGroup? SelectedReviewGroup { get; set; }

    [ObservableProperty]
    public partial ManualGroupOption? SelectedMergeGroupOption { get; set; }

    [ObservableProperty]
    public partial DispatchItem? SelectedDispatchItem { get; set; }

    [ObservableProperty]
    public partial string DispatchQueueSearchText { get; set; } = string.Empty;

    [ObservableProperty]
    public partial DispatchQueueFilterOption SelectedDispatchQueueFilter { get; set; } =
        new(DispatchQueueFilterKind.Pending, "A fazer");

    [ObservableProperty]
    public partial DeliveryAttempt? SelectedIncidentAttempt { get; set; }

    [ObservableProperty]
    public partial IncidentRecord? SelectedIncident { get; set; }

    [ObservableProperty]
    public partial IncidentCategoryOption SelectedIncidentCategory { get; set; } =
        new(IncidentCategory.AmbiguousProviderResult, "Resultado incerto do provedor");

    [ObservableProperty]
    public partial IncidentSeverityOption SelectedIncidentSeverity { get; set; } =
        new(IncidentSeverity.Medium, "Média");

    [ObservableProperty]
    public partial IncidentStatusOption SelectedIncidentStatus { get; set; } =
        new(IncidentStatus.Investigating, "Em apuração");

    [ObservableProperty]
    public partial string IncidentSummary { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string IncidentTransitionNote { get; set; } = string.Empty;

    [ObservableProperty]
    public partial DispatchOperationMode SelectedDispatchOperationMode { get; set; } = DispatchOperationMode.Test;

    [ObservableProperty]
    public partial FakeDeliveryScenario SelectedFakeDeliveryScenario { get; set; } = FakeDeliveryScenario.Success;

    [ObservableProperty]
    public partial string TestDestination { get; set; } = "auditoria@example.invalid";

    [ObservableProperty]
    public partial string SendConfirmationPhrase { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string BatchSendConfirmationPhrase { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string DispatchWorkspaceSummary { get; set; } = "Nenhuma mensagem preparada.";

    [ObservableProperty]
    public partial string LastReportPath { get; set; } = "Nenhum relatório gerado nesta sessão.";

    [ObservableProperty]
    public partial string LastReportPdfPath { get; set; } = "Nenhum PDF gerado nesta sessão.";

    [ObservableProperty]
    public partial ReportScopeOption SelectedReportScope { get; set; } =
        new(DispatchReportScope.Month, "Um mês", "Escolha uma competência mensal sem alterar o período de trabalho do restante do aplicativo.");

    [ObservableProperty]
    public partial ReportClientFilterOption SelectedReportClientFilter { get; set; } =
        new(ReportClientFilterKind.AllClients, "Todos os clientes", "Inclui todos os clientes encontrados no período escolhido.");

    [ObservableProperty]
    public partial OperationalYearOption? SelectedReportYear { get; set; }

    [ObservableProperty]
    public partial OperationalMonthOption? SelectedReportMonth { get; set; }

    [ObservableProperty]
    public partial OperationalYearOption? SelectedReportStartYear { get; set; }

    [ObservableProperty]
    public partial OperationalMonthOption? SelectedReportStartMonth { get; set; }

    [ObservableProperty]
    public partial OperationalYearOption? SelectedReportEndYear { get; set; }

    [ObservableProperty]
    public partial OperationalMonthOption? SelectedReportEndMonth { get; set; }

    [ObservableProperty]
    public partial ClientListRow? SelectedReportClient { get; set; }

    [ObservableProperty]
    public partial HistoryCleanupScopeOption SelectedHistoryCleanupScope { get; set; } =
        new(HistoryVisibilityRuleKind.AllUntilNow, "Tudo que aparece agora", "Oculta da tela todo o histórico atual; novos acontecimentos continuarão aparecendo.");

    [ObservableProperty]
    public partial DateTimeOffset? HistoryRangeStartDate { get; set; } = DateTimeOffset.Now;

    [ObservableProperty]
    public partial TimeSpan? HistoryRangeStartTime { get; set; } = DateTimeOffset.Now.TimeOfDay;

    [ObservableProperty]
    public partial DateTimeOffset? HistoryRangeEndDate { get; set; } = DateTimeOffset.Now;

    [ObservableProperty]
    public partial TimeSpan? HistoryRangeEndTime { get; set; } = DateTimeOffset.Now.TimeOfDay;

    [ObservableProperty]
    public partial HistoryHourOption SelectedHistoryHour { get; set; } =
        HistoryHourOption.Create()[DateTimeOffset.Now.Hour];

    [ObservableProperty]
    public partial bool ShowHiddenHistory { get; set; }

    [ObservableProperty]
    public partial HistoryTimeScopeOption SelectedHistoryTimeScope { get; set; } =
        new(HistoryTimeScope.All, "Todo o histórico", "Mostra todos os dias preservados, respeitando a competência escolhida no cabeçalho.");

    [ObservableProperty]
    public partial DateTimeOffset? HistoryFilterStartDate { get; set; } = DateTimeOffset.Now;

    [ObservableProperty]
    public partial TimeSpan? HistoryFilterStartTime { get; set; } = TimeSpan.Zero;

    [ObservableProperty]
    public partial DateTimeOffset? HistoryFilterEndDate { get; set; } = DateTimeOffset.Now;

    [ObservableProperty]
    public partial TimeSpan? HistoryFilterEndTime { get; set; } = DateTimeOffset.Now.TimeOfDay;

    [ObservableProperty]
    public partial int SelectedHistoryCalendarYear { get; set; } = DateTimeOffset.Now.Year;

    [ObservableProperty]
    public partial OperationalMonthOption SelectedHistoryCalendarMonth { get; set; } =
        OperationalMonthOption.Create()[DateTimeOffset.Now.Month];

    [ObservableProperty]
    public partial HistoryClientFilterOption SelectedHistoryClientFilter { get; set; } =
        new(null, "Todos os clientes");

    [ObservableProperty]
    public partial HistoryDocumentFilterOption SelectedHistoryDocumentFilter { get; set; } =
        new(null, "Todos os documentos");

    [ObservableProperty]
    public partial string HistorySearchText { get; set; } = string.Empty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasHistoryFilterError))]
    public partial string HistoryFilterError { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool IsReviewHistoryExpanded { get; set; }

    [ObservableProperty]
    public partial bool IsDispatchHistoryExpanded { get; set; }

    public bool HasHistoryFilterError => !string.IsNullOrWhiteSpace(HistoryFilterError);

    [ObservableProperty]
    public partial ClientResolutionCandidate? SelectedOverrideCandidate { get; set; }

    [ObservableProperty]
    public partial string OverrideReason { get; set; } = string.Empty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanSplitSelectedDocument))]
    public partial string SplitGroupReason { get; set; } = string.Empty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanMergeSelectedGroups))]
    public partial string MergeGroupReason { get; set; } = string.Empty;

    [ObservableProperty]
    public partial OperationalYearOption? SelectedCorrectionYear { get; set; }

    [ObservableProperty]
    public partial OperationalMonthOption? SelectedCorrectionMonth { get; set; }

    [ObservableProperty]
    public partial string DocumentCorrectionReason { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string DocumentRemovalReason { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool IsDocumentRemovalConfirmationVisible { get; set; }

    [ObservableProperty]
    public partial string ReviewWorkspaceSummary { get; set; } =
        "Carregando revisão local recuperável…";

    [ObservableProperty]
    public partial bool IsRecognizingDocuments { get; set; }

    [ObservableProperty]
    public partial int DocumentImportProgress { get; set; }

    [ObservableProperty]
    public partial int DocumentImportTotal { get; set; }

    public string DocumentImportProgressText => DocumentImportTotal == 0
        ? "Aguardando documentos"
        : $"Processando {DocumentImportProgress} de {DocumentImportTotal}";

    [ObservableProperty]
    public partial string DocumentImportSummary { get; set; } =
        "Nenhum arquivo novo nesta sessão. PDFs: até 25 MB e 100 páginas por documento.";

    [ObservableProperty]
    public partial bool IsDocumentImportPanelExpanded { get; set; } = true;

    [ObservableProperty]
    public partial bool IsDocumentCorrectionPanelExpanded { get; set; }

    [ObservableProperty]
    public partial bool IsDocumentOrganizationPanelExpanded { get; set; }

    [ObservableProperty]
    public partial bool IsDocumentRecognitionPanelExpanded { get; set; }

    public bool ShowEmailConnectionWarning =>
        IsExternalEmailProviderConfigured && !IsEmailAccountConnected;

    public bool ShowEmailSetupNotice => IsLocalEmailSimulation || ShowEmailConnectionWarning;

    public string EmailSetupNoticeTitle => IsLocalEmailSimulation
        ? "E-mail real ainda não configurado"
        : "Conta de e-mail desconectada";

    public string EmailSetupNoticeText => IsLocalEmailSimulation
        ? "A simulação local está ativa e não envia mensagens. A conexão com Gmail ou Microsoft 365 exige configuração segura desta instalação."
        : "Conecte sua conta antes de concluir os envios. O restante do trabalho continua disponível.";

    public bool IsGmailActiveProvider =>
        ActiveEmailProviderKey == DispatchWorkflowOptions.GmailProviderKey;

    public bool IsMicrosoftActiveProvider =>
        ActiveEmailProviderKey == DispatchWorkflowOptions.MicrosoftGraphProviderKey;

    public bool CanConnectGmail => IsGmailActiveProvider && CanConnectEmailAccount;

    public bool CanConnectMicrosoft => IsMicrosoftActiveProvider && CanConnectEmailAccount;

    public bool HasExistingClient => currentClientId.HasValue;

    public bool CanChangePersonType => !HasExistingClient;

    public string ClientSaveActionLabel => HasExistingClient
        ? "Atualizar cadastro"
        : "Cadastrar cliente";

    public string ClientStatusLabel => IsClientActive ? "Ativo" : "Inativo";

    public string ClientStatusBackground => IsClientActive ? "#EAF4E8" : "#F2EEE7";

    public string ClientStatusForeground => IsClientActive ? "#2F6D3B" : "#6E6254";

    public bool IsLegalEntity => SelectedPersonType == PersonTypeModel.LegalEntity;

    public bool IsIndividual => SelectedPersonType == PersonTypeModel.Individual;

    public string PrimaryTaxIdLabel => IsLegalEntity ? "CNPJ" : "CPF";

    public string PrimaryTaxIdPlaceholder => IsLegalEntity
        ? "00.000.000/0001-00"
        : "000.000.000-00";

    public string PrimaryTaxIdHelp => IsLegalEntity
        ? "Pode digitar com ou sem pontuação. A validação confere os dígitos oficiais do CNPJ, sem consultar cadastro externo; o aplicativo também reconhece a raiz presente nos documentos."
        : "Pode digitar com ou sem pontos e hífen. A validação confere os dígitos oficiais do CPF, sem consultar cadastro externo.";

    public string LegalNameLabel => IsLegalEntity ? "Razão social" : "Nome completo";

    public string WorkPeriodLabel => (SelectedOperationalYear?.Year, SelectedOperationalMonth?.Month) switch
    {
        (null, null) => "Todos os períodos",
        (null, 0) => "Sem mês definido · todos os anos",
        (null, var month) => $"{GetOperationalMonthLabel(month)} · todos os anos",
        (var year, null) => $"Todos os meses de {year}",
        (var year, 0) => $"Sem mês definido · {year}",
        (var year, var month) => $"{GetOperationalMonthLabel(month)} {year}",
    };

    public string InputFolderDisplay => string.IsNullOrWhiteSpace(InputFolderPath)
        ? "Nenhuma pasta de entrada selecionada"
        : InputFolderPath;

    public bool HasConfiguredInputFolder => !string.IsNullOrWhiteSpace(InputFolderPath);

    public string SupportedDocumentFormatsHelp =>
        $"Processamento disponível agora no {ProductName}: PDF. Arquivos Word, planilhas e outros formatos não são analisados; ao ler uma pasta, o aplicativo os preserva e informa a recusa no resumo.";

    public IEnumerable<ReviewDocument> VisibleReviewDocuments =>
        ReviewDocuments.Where(document => MatchesSelectedPeriod(document.Period));

    public int HiddenReviewDocumentCount =>
        Math.Max(0, ReviewDocuments.Count - VisibleReviewDocuments.Count());

    public bool HasHiddenReviewDocuments => HiddenReviewDocumentCount > 0;

    public bool HasVisibleReviewDocuments => VisibleReviewDocuments.Any();

    public bool HasAnyReviewDocuments => ReviewDocuments.Count > 0;

    public bool ShowReviewEmptyState => HasAnyReviewDocuments && !HasVisibleReviewDocuments;

    public int VisibleDocumentCount => VisibleReviewDocuments.Count();

    public IEnumerable<DocumentDispatchGroup> VisibleReviewGroups =>
        ReviewGroups.Where(MatchesSelectedPeriod);

    public IEnumerable<DocumentDispatchGroup> VisibleApprovedReviewGroups =>
        VisibleReviewGroups.Where(group => group.IsApproved);

    public IEnumerable<ReviewDocument> SelectedReviewGroupDocuments =>
        SelectedReviewGroup is null
            ? []
            : SelectedReviewGroup.DocumentIds
                .Select(documentId => ReviewDocuments.FirstOrDefault(document => document.Id == documentId))
                .Where(document => document is not null)
                .Select(document => document!)
                .OrderBy(document => DocumentPresentation.ToPortugueseLabel(document.DocumentType), StringComparer.CurrentCultureIgnoreCase)
                .ThenBy(document => document.FileName, StringComparer.CurrentCultureIgnoreCase);

    public bool HasSelectedReviewGroupDocuments => SelectedReviewGroupDocuments.Any();

    public string SelectedGroupViewerHeader => SelectedReviewGroup is null
        ? "Ver o conjunto completo"
        : $"Ver o conjunto completo ({SelectedReviewGroup.DocumentIds.Count} documento(s))";

    public bool HasVisibleReviewGroups => VisibleReviewGroups.Any();

    public int ReadyReviewGroupCount => VisibleReviewGroups.Count(group =>
        group.State == ReviewGroupState.ReadyForReview && !group.PreventsApproval);

    private IEnumerable<DocumentDispatchGroup> ReadyGroupsForSelectedClient =>
        (SelectedReviewDocument?.ClientId ?? SelectedReviewGroup?.ClientId) is { } clientId
            ? ReviewGroups.Where(group =>
                group.ClientId == clientId &&
                group.State == ReviewGroupState.ReadyForReview &&
                !group.PreventsApproval)
            : [];

    public int SelectedClientReadyGroupCount => ReadyGroupsForSelectedClient.Count();

    public int SelectedClientReadyDocumentCount => ReadyGroupsForSelectedClient
        .SelectMany(group => group.DocumentIds)
        .Distinct()
        .Count();

    public int SelectedClientReadyPeriodCount => ReadyGroupsForSelectedClient
        .Select(group => group.PeriodLabel)
        .Distinct(StringComparer.CurrentCultureIgnoreCase)
        .Count();

    public int ReadyReviewDocumentCount => VisibleReviewGroups
        .Where(group => group.State == ReviewGroupState.ReadyForReview && !group.PreventsApproval)
        .SelectMany(group => group.DocumentIds)
        .Distinct()
        .Count();

    public bool ShowSelectedClientApproval => SelectedClientReadyGroupCount > 1;

    public bool CanApproveSelectedClientGroups => ShowSelectedClientApproval;

    public string SelectedSetApprovalLabel => SelectedReviewGroup is null
        ? "Liberar este conjunto"
        : $"Liberar este conjunto ({SelectedReviewGroup.DocumentIds.Count} documento(s))";

    public string SelectedClientApprovalLabel =>
        $"Liberar este cliente ({SelectedClientReadyGroupCount} conjuntos / {SelectedClientReadyDocumentCount} documentos / {SelectedClientReadyPeriodCount} competências)";

    public string SelectedClientApprovalHelp =>
        $"Os {SelectedClientReadyGroupCount} conjuntos continuarão separados por competência e originarão mensagens independentes; erros, bloqueios e repetidos ficam de fora.";

    public string AllReadyApprovalLabel =>
        $"Liberar todos os clientes prontos ({ReadyReviewGroupCount} conjuntos / {ReadyReviewDocumentCount} documentos)";

    public bool ShowBulkDocumentApproval => ReadyReviewGroupCount > 1;

    public IEnumerable<ManualGroupOption> CompatibleMergeGroupOptions
    {
        get
        {
            if (SelectedReviewGroup is null)
            {
                return [];
            }

            return ReviewGroups
                .Where(group =>
                    group.Id != SelectedReviewGroup.Id &&
                    group.ClientId == SelectedReviewGroup.ClientId &&
                    group.EstablishmentId == SelectedReviewGroup.EstablishmentId &&
                    string.Equals(group.PeriodKey, SelectedReviewGroup.PeriodKey, StringComparison.Ordinal) &&
                    string.Equals(
                        group.GroupingPolicyCode,
                        SelectedReviewGroup.GroupingPolicyCode,
                        StringComparison.Ordinal) &&
                    string.Equals(
                        group.GroupingPolicyVersion,
                        SelectedReviewGroup.GroupingPolicyVersion,
                        StringComparison.Ordinal))
                .OrderBy(group => group.CreatedAtUtc)
                .Select((group, index) => new ManualGroupOption(
                    group.Id,
                    $"Conjunto compatível {index + 1}",
                    $"{group.PeriodLabel} • {group.DocumentIds.Count} documento(s) • {DescribeGroupDocumentTypes(group)}"))
                .ToArray();
        }
    }

    public bool HasCompatibleMergeGroups => CompatibleMergeGroupOptions.Any();

    public bool CanMergeSelectedGroups =>
        SelectedReviewGroup is not null &&
        SelectedMergeGroupOption is not null &&
        MergeGroupReason.Trim().Length >= 10;

    private IEnumerable<DispatchItem> CurrentDispatchItems => DispatchItems
        .GroupBy(item => item.GroupId)
        .Select(group => group
            .Where(item => item.State != DispatchItemState.Cancelled)
            .OrderByDescending(item => item.Revision)
            .ThenByDescending(item => item.CreatedAtUtc)
            .ThenByDescending(item => item.UpdatedAtUtc)
            .ThenByDescending(item => item.Id)
            .FirstOrDefault())
        .Where(item => item is not null)
        .Select(item => item!);

    private IEnumerable<DispatchItem> PeriodDispatchItems =>
        CurrentDispatchItems.Where(item => MatchesSelectedPeriod(item.PeriodLabel));

    public IEnumerable<DispatchItem> VisibleDispatchItems => PeriodDispatchItems
        .Where(item => DispatchItemMatchesQueueFilter(item) && DispatchItemMatchesSearch(item))
        .OrderByDescending(item => item.UpdatedAtUtc);

    public int VisibleDispatchItemCount => VisibleDispatchItems.Count();

    public bool ShowDispatchQueueEmptyState => VisibleDispatchItemCount == 0;

    public string DispatchQueueEmptyMessage => DispatchQueueSearchText.Trim().Length > 0
        ? "Nenhuma mensagem corresponde à busca. Confira a referência, o cliente, a competência ou o nome do arquivo."
        : PeriodDispatchItems.Any()
            ? "Nenhuma mensagem aparece neste filtro. Escolha outro estado para continuar."
            : "Ainda não há mensagens preparadas nesta competência. Libere os documentos e prepare o cliente para continuar.";

    public string DispatchQueueSummary =>
        $"{PeriodDispatchItems.Count(item => !IsCompletedDispatchState(item.State))} a fazer • " +
        $"{PeriodDispatchItems.Count(item => IsCompletedDispatchState(item.State))} concluída(s) • " +
        $"{PeriodDispatchItems.Count(item => item.State is DispatchItemState.Blocked or DispatchItemState.Failed or DispatchItemState.Ambiguous)} precisam de atenção";

    private IEnumerable<ReviewAuditEvent> FilteredReviewAuditEvents
    {
        get
        {
            var index = CreateHistoryProjectionIndex();
            return HistoryFilterEngine.Apply(
                    ReviewAuditEvents.Where(AuditMatchesSelectedPeriod),
                    index.Project,
                    activeHistoryFilter)
                .Where(item => IsHistoryEventVisible(item.Id, item.TimestampUtc))
                .OrderByDescending(item => item.TimestampUtc)
                .ThenByDescending(item => item.Id);
        }
    }

    private IEnumerable<DispatchAuditEvent> FilteredDispatchAuditEvents
    {
        get
        {
            var index = CreateHistoryProjectionIndex();
            return HistoryFilterEngine.Apply(
                    DispatchAuditEvents.Where(AuditMatchesSelectedPeriod),
                    index.Project,
                    activeHistoryFilter)
                .Where(item => IsHistoryEventVisible(item.Id, item.TimestampUtc))
                .OrderByDescending(item => item.TimestampUtc)
                .ThenByDescending(item => item.Id);
        }
    }

    private Guid? CatalogHistoryClientId => loadedHistoryCatalogClientId;

    private IEnumerable<AuditEventModel> FilteredCatalogAuditEvents
    {
        get
        {
            if (CatalogHistoryClientId is not { } clientId)
            {
                return [];
            }

            var index = CreateHistoryProjectionIndex();
            var catalogCriteria = activeHistoryFilter with
            {
                ClientId = clientId,
                DocumentId = null,
                DocumentType = null,
                CompetenceYear = null,
                CompetenceMonth = null,
            };
            return HistoryFilterEngine.Apply(
                    HistoryCatalogAuditEvents,
                    item => index.Project(item, clientId, CatalogHistoryClientName),
                    catalogCriteria)
                .Where(item => IsHistoryEventVisible(item.Id, item.TimestampUtc))
                .OrderByDescending(item => item.TimestampUtc)
                .ThenByDescending(item => item.Id);
        }
    }

    public IEnumerable<ReviewAuditEvent> VisibleReviewAuditEvents => FilteredReviewAuditEvents.Take(500);

    public IEnumerable<DispatchAuditEvent> VisibleDispatchAuditEvents => FilteredDispatchAuditEvents.Take(500);

    public IEnumerable<AuditEventModel> VisibleCatalogAuditEvents => FilteredCatalogAuditEvents.Take(500);

    public bool HasVisibleCatalogAuditEvents => VisibleCatalogAuditEvents.Any();

    public string CatalogHistoryHeader => CatalogHistoryClientId is null
        ? "Alterações cadastrais"
        : $"Alterações do cadastro · {CatalogHistoryClientName}";

    public string CatalogHistoryEmptyMessage => CatalogHistoryClientId is null
        ? "Escolha um cliente no filtro acima e aplique para consultar as alterações desse cadastro."
        : $"Ainda não há alterações no período escolhido para {CatalogHistoryClientName}.";

    private string CatalogHistoryClientName => HistoryClientFilters
        .FirstOrDefault(item => item.ClientId == CatalogHistoryClientId)?.Label ??
        "cliente selecionado";

    public IEnumerable<HistoryTimelineRow> VisibleReviewHistoryRows =>
        VisibleReviewAuditEvents.Select(BuildReviewHistoryRow);

    public IEnumerable<HistoryTimelineRow> VisibleDispatchHistoryRows =>
        VisibleDispatchAuditEvents.Select(BuildDispatchHistoryRow);

    public int VisibleReviewHistoryCount => VisibleReviewHistoryRows.Count();

    public int VisibleDispatchHistoryCount => VisibleDispatchHistoryRows.Count();

    public string ReviewHistorySectionHeader =>
        $"Documentos conferidos e aprovados · {VisibleReviewHistoryCount}";

    public string DispatchHistorySectionHeader =>
        $"Mensagens preparadas e concluídas · {VisibleDispatchHistoryCount}";

    public int TruncatedHistoryEventCount =>
        Math.Max(0, FilteredReviewAuditEvents.Count() - 500) +
        Math.Max(0, FilteredDispatchAuditEvents.Count() - 500) +
        Math.Max(0, FilteredCatalogAuditEvents.Count() - 500);

    public bool HasTruncatedHistoryEvents => TruncatedHistoryEventCount > 0;

    public string HistoryTruncationMessage =>
        $"A tela mostra os 500 registros mais recentes de cada seção. " +
        $"{TruncatedHistoryEventCount} registro(s) adicional(is) continuam preservados na auditoria.";

    public int HiddenHistoryEventCount => ReviewAuditEvents
        .Select(item => (item.Id, item.TimestampUtc))
        .Concat(DispatchAuditEvents.Select(item => (item.Id, item.TimestampUtc)))
        .Concat(HistoryCatalogAuditEvents.Select(item => (item.Id, item.TimestampUtc)))
        .GroupBy(item => item.Id)
        .Select(group => group.First())
        .Count(item => !IsHistoryEventVisible(item.Id, item.TimestampUtc, ignoreShowHidden: true));

    public bool HasHiddenHistoryEvents => HiddenHistoryEventCount > 0;

    public bool IsCustomHistoryCleanup =>
        SelectedHistoryCleanupScope.Value == HistoryVisibilityRuleKind.CustomInterval;

    public bool IsHistoryDayCleanup =>
        SelectedHistoryCleanupScope.Value == HistoryVisibilityRuleKind.CalendarDay;

    public bool IsHistoryHourCleanup =>
        SelectedHistoryCleanupScope.Value == HistoryVisibilityRuleKind.ClockHour;

    public bool IsDayOrHourHistoryCleanup => SelectedHistoryCleanupScope.Value is
        HistoryVisibilityRuleKind.CalendarDay or HistoryVisibilityRuleKind.ClockHour;

    public string HistoryCleanupHelp => SelectedHistoryCleanupScope.Help;

    public IEnumerable<int> HistoryCalendarYears => OperationalYears
        .Where(option => option.Year.HasValue)
        .Select(option => option.Year!.Value)
        .Concat(ReviewAuditEvents.Select(item => TimeZoneInfo.ConvertTime(item.TimestampUtc, TimeZoneInfo.Local).Year))
        .Concat(DispatchAuditEvents.Select(item => TimeZoneInfo.ConvertTime(item.TimestampUtc, TimeZoneInfo.Local).Year))
        .Concat(HistoryCatalogAuditEvents.Select(item => TimeZoneInfo.ConvertTime(item.TimestampUtc, TimeZoneInfo.Local).Year))
        .Distinct()
        .OrderByDescending(year => year);

    public IEnumerable<OperationalMonthOption> HistoryCalendarMonths => CorrectionMonthOptions;

    public string HistoryTimeScopeHelp => SelectedHistoryTimeScope.Help;

    public bool IsHistoryFilterDay =>
        SelectedHistoryTimeScope.Value == HistoryTimeScope.CalendarDay;

    public bool IsHistoryFilterMonth =>
        SelectedHistoryTimeScope.Value == HistoryTimeScope.CalendarMonth;

    public bool IsHistoryFilterYear =>
        SelectedHistoryTimeScope.Value == HistoryTimeScope.CalendarYear;

    public bool IsHistoryFilterCustomInterval =>
        SelectedHistoryTimeScope.Value == HistoryTimeScope.CustomInterval;

    public bool HasActiveHistoryFilters =>
        activeHistoryFilter != HistoryFilterCriteria.All ||
        SelectedOperationalYear?.Year is not null ||
        SelectedOperationalMonth?.Month is not null;

    public string HistoryAppliedFilterSummary { get; private set; } =
        "Exibindo todos os acontecimentos preservados.";

    public string HistoryCleanupSelectionSummary => SelectedHistoryCleanupScope.Value switch
    {
        HistoryVisibilityRuleKind.AllUntilNow => "Tudo que está visível agora será ocultado; novos registros continuarão aparecendo.",
        HistoryVisibilityRuleKind.SelectedOperationalPeriod => $"Competência escolhida: {WorkPeriodLabel}.",
        HistoryVisibilityRuleKind.CalendarDay when HistoryRangeStartDate is { } day =>
            $"Dia escolhido: {day:dd/MM/yyyy}.",
        HistoryVisibilityRuleKind.ClockHour when HistoryRangeStartDate is { } day =>
            $"Bloco escolhido: {day:dd/MM/yyyy}, {SelectedHistoryHour.Label}.",
        HistoryVisibilityRuleKind.CustomInterval when
            HistoryRangeStartDate is { } startDate && HistoryRangeStartTime is { } startTime &&
            HistoryRangeEndDate is { } endDate && HistoryRangeEndTime is { } endTime =>
            $"De {startDate:dd/MM/yyyy} às {startTime:hh\\:mm} até {endDate:dd/MM/yyyy} às {endTime:hh\\:mm}.",
        _ => "Complete o período que deseja retirar apenas desta visualização.",
    };

    public string HistoryCleanupActionLabel => SelectedHistoryCleanupScope.Value switch
    {
        HistoryVisibilityRuleKind.CalendarDay => "Ocultar este dia",
        HistoryVisibilityRuleKind.ClockHour => "Ocultar este bloco de uma hora",
        HistoryVisibilityRuleKind.CustomInterval => "Ocultar este intervalo",
        HistoryVisibilityRuleKind.SelectedOperationalPeriod => "Ocultar esta competência",
        _ => "Limpar a visualização atual",
    };

    public bool IsReportMonthScope => SelectedReportScope.Value == DispatchReportScope.Month;

    public bool IsReportYearScope => SelectedReportScope.Value == DispatchReportScope.Year;

    public bool IsReportRangeScope => SelectedReportScope.Value == DispatchReportScope.Range;

    public bool IsReportClientScope =>
        SelectedReportClientFilter.Value == ReportClientFilterKind.SelectedClient;

    public string ReportScopeHelp => $"{SelectedReportScope.Help} {SelectedReportClientFilter.Help}";

    public bool CanExportReport =>
        (SelectedReportClientFilter.Value == ReportClientFilterKind.AllClients || SelectedReportClient is not null) &&
        (SelectedReportScope.Value switch
        {
            DispatchReportScope.AllPeriods => true,
            DispatchReportScope.Month => SelectedReportYear?.Year is >= 1900 and <= 9999 &&
                SelectedReportMonth?.Month is >= 1 and <= 12,
            DispatchReportScope.Year => SelectedReportYear?.Year is >= 1900 and <= 9999,
            DispatchReportScope.Range =>
                TryGetReportRange(out var startYear, out var startMonth, out var endYear, out var endMonth) &&
                ReportPeriodKey(startYear, startMonth) <= ReportPeriodKey(endYear, endMonth),
            DispatchReportScope.Client => SelectedReportClient is not null,
            _ => false,
        });

    public string ReportScopeSummary
    {
        get
        {
            var client = SelectedReportClientFilter.Value == ReportClientFilterKind.SelectedClient
                ? SelectedReportClient?.DisplayName ?? "Escolha um cliente"
                : "Todos os clientes";
            var period = SelectedReportScope.Value switch
            {
                DispatchReportScope.AllPeriods => "todos os períodos",
                DispatchReportScope.Month when SelectedReportYear?.Year is { } year &&
                    SelectedReportMonth?.Month is { } month => $"{GetOperationalMonthLabel(month)} {year}",
                DispatchReportScope.Year when SelectedReportYear?.Year is { } year => $"ano de {year}",
                DispatchReportScope.Range when TryGetReportRange(
                    out var startYear,
                    out var startMonth,
                    out var endYear,
                    out var endMonth) =>
                    $"{GetOperationalMonthLabel(startMonth)} {startYear} a {GetOperationalMonthLabel(endMonth)} {endYear}",
                DispatchReportScope.Client => "todos os períodos",
                _ => "período ainda incompleto",
            };
            return $"{client} • {period}";
        }
    }

    public IEnumerable<CommunicationStatusRow> ReportCommunicationRows =>
        CurrentDispatchItems
            .Where(ReportItemMatchesScope)
            .OrderByDescending(item => item.UpdatedAtUtc)
            .Select(item =>
            {
                var presentation = DispatchOutcomePresenter.Present(item, LatestAttemptFor(item.Id));
                var attachments = item.Message?.Attachments ?? [];
                var documentTypes = attachments
                    .Select(attachment => DocumentPresentation.ToPortugueseLabel(attachment.DocumentType))
                    .Distinct(StringComparer.CurrentCultureIgnoreCase)
                    .OrderBy(type => type, StringComparer.CurrentCultureIgnoreCase)
                    .ToArray();
                var documentNames = attachments
                    .Select(attachment => attachment.FileName)
                    .Distinct(StringComparer.CurrentCultureIgnoreCase)
                    .OrderBy(fileName => fileName, StringComparer.CurrentCultureIgnoreCase)
                    .ToArray();
                return new CommunicationStatusRow(
                    item.Id,
                    item.ClientDisplayName,
                    item.PeriodLabel,
                    $"Mensagem #{item.Id.ToString("N")[..6].ToUpperInvariant()}",
                    $"Conjunto #{item.GroupId.ToString("N")[..6].ToUpperInvariant()} • {attachments.Count} documento(s)",
                    attachments.Count == 0
                        ? "Documentos ainda não preparados"
                        : $"{string.Join(", ", documentTypes)} • {string.Join(", ", documentNames)}",
                    presentation.OperationResult,
                    presentation.DeliveryStatus,
                    presentation.NextAction,
                    presentation.NeedsAttention);
            });

    public bool HasReportCommunicationRows => ReportCommunicationRows.Any();

    public int EligibleDocumentCount => VisibleReviewDocuments.Count(document =>
        document.State is ReviewDocumentState.Ready or ReviewDocumentState.Grouped or ReviewDocumentState.Approved);

    public int BlockedDocumentCount => VisibleReviewDocuments.Count(document =>
        document.State is ReviewDocumentState.Blocked or ReviewDocumentState.Duplicate);

    public int ApprovedGroupCount => VisibleReviewGroups.Count(group => group.IsApproved);

    public bool CanContinueToDispatch => ApprovedGroupCount > 0;

    public bool CanOpenReports => PeriodDispatchItems.Any();

    public bool CanExportSelectedPeriodReport => CanExportReport;

    public bool IsDocumentImportStepCurrent => !VisibleReviewDocuments.Any();

    public bool IsDocumentApprovalStepCurrent => VisibleReviewGroups.Any(group =>
        group.State is ReviewGroupState.ReadyForReview or ReviewGroupState.Approved);

    public bool IsDocumentReviewStepCurrent =>
        !IsDocumentImportStepCurrent && !IsDocumentApprovalStepCurrent;

    public bool IsDispatchPrepareStepCurrent => SelectedDispatchItem is null;

    public bool IsDispatchReviewStepCurrent =>
        SelectedDispatchItem?.State == DispatchItemState.Blocked;

    public bool IsDispatchApprovalStepCurrent =>
        SelectedDispatchItem?.State == DispatchItemState.ReadyForApproval;

    public bool IsDispatchCompletionStepCurrent => SelectedDispatchItem?.State is
        DispatchItemState.Approved or
        DispatchItemState.DraftCreating or
        DispatchItemState.DraftCreated or
        DispatchItemState.Sending or
        DispatchItemState.AcceptedByProvider or
        DispatchItemState.Failed or
        DispatchItemState.Ambiguous or
        DispatchItemState.Reconciled or
        DispatchItemState.Completed or
        DispatchItemState.Cancelled;

    public bool ShowDispatchApprovalPanel =>
        SelectedDispatchItem?.State == DispatchItemState.ReadyForApproval;

    public bool ShowDispatchCompletionPanel => IsDispatchCompletionStepCurrent;

    public bool HasApprovedGroupsForDispatch => VisibleApprovedReviewGroups.Any();

    public bool HasDispatchMessages => PeriodDispatchItems.Any();

    public bool HasSelectedDispatchItem => SelectedDispatchItem is not null;

    public bool HasSelectedDispatchBlocks => SelectedDispatchItem?.Blocks.Count > 0;

    public string SelectedDispatchReference => SelectedDispatchItem is null
        ? "Mensagem"
        : $"Mensagem #{SelectedDispatchItem.Id.ToString("N")[..6].ToUpperInvariant()}";

    public string SelectedDispatchContextSummary => SelectedDispatchItem is null
        ? "Escolha uma mensagem para conferir."
        : $"{SelectedDispatchItem.ClientDisplayName} • {SelectedDispatchItem.PeriodLabel} • " +
          $"Conjunto #{SelectedDispatchItem.GroupId.ToString("N")[..6].ToUpperInvariant()} • " +
          $"{SelectedDispatchItem.Message?.Attachments.Count ?? 0} anexo(s)";

    public bool CanPrepareSelectedDispatch => SelectedReviewGroup?.IsApproved == true &&
        MatchesSelectedPeriod(SelectedReviewGroup);

    public bool CanPrepareDispatchBatch => HasSingleMonthlyPeriodSelected() &&
        HasApprovedGroupsForDispatch;

    public bool CanApproveSelectedDispatch => SelectedDispatchItem is
    {
        State: DispatchItemState.ReadyForApproval,
        PreventsApproval: false,
        Message: not null,
    };

    public bool CanApproveSelectedDispatchBatch =>
        SelectedDispatchItem is not null &&
        IsSelectedPeriodBatchEligible(
            SelectedDispatchItem.BatchId,
            item => item.State == DispatchItemState.ReadyForApproval &&
                !item.PreventsApproval &&
                item.Message is not null);

    public bool CanExecuteSelectedDispatch =>
        SelectedDispatchItem?.State == DispatchItemState.Approved ||
        SelectedDispatchItem is
        {
            State: DispatchItemState.Failed,
            Message: not null,
            Approval: not null,
        } failedItem &&
        string.Equals(
            failedItem.Approval.DispatchFingerprint,
            failedItem.Message.DispatchFingerprint,
            StringComparison.Ordinal) &&
        DeliveryAttempts
            .Where(attempt =>
                attempt.DispatchItemId == failedItem.Id &&
                string.Equals(
                    attempt.DispatchFingerprint,
                    failedItem.Message.DispatchFingerprint,
                    StringComparison.Ordinal))
            .OrderByDescending(attempt => attempt.AttemptNumber)
            .FirstOrDefault()?.State == DeliveryAttemptState.FailedTransient;

    public bool CanExecuteSelectedDispatchBatch =>
        SelectedDispatchItem is not null &&
        IsSelectedPeriodBatchEligible(SelectedDispatchItem.BatchId, item => item.IsApproved);

    public bool CanReconcileSelectedDispatch => SelectedDispatchItem?.State == DispatchItemState.Ambiguous ||
        SelectedDispatchItem is not null && DeliveryAttempts.Any(attempt =>
            attempt.DispatchItemId == SelectedDispatchItem.Id &&
            attempt.State is DeliveryAttemptState.Pending or DeliveryAttemptState.Ambiguous);

    public bool HasReviewSelection => SelectedReviewDocument is not null;

    public IEnumerable<OperationalYearOption> CorrectionYearOptions =>
        OperationalYears.Where(option => option.Year.HasValue);

    public IEnumerable<OperationalMonthOption> CorrectionMonthOptions =>
        OperationalMonths.Where(option => option.Month is > 0 and <= 12);

    public IEnumerable<OperationalYearOption> ReportYearOptions => CorrectionYearOptions;

    public IEnumerable<OperationalMonthOption> ReportMonthOptions => CorrectionMonthOptions;

    public bool HasSelectedDocumentFindings =>
        SelectedReviewDocument?.Findings.Any(finding => !finding.IsResolved) == true;

    public bool SelectedDocumentHasInactiveClientIssue =>
        SelectedReviewDocument?.ResolutionBlockers.Contains("client.inactive", StringComparer.Ordinal) == true ||
        SelectedReviewDocument?.Findings.Any(finding =>
            !finding.IsResolved && string.Equals(finding.RuleCode, "client.inactive", StringComparison.Ordinal)) == true;

    public bool HasClientCorrectionAlternatives =>
        !SelectedDocumentHasInactiveClientIssue &&
        SelectedReviewDocument?.ClientAlternatives.Count > 0;

    public bool CanApproveSelectedReviewGroup =>
        SelectedReviewGroup is
        {
            State: ReviewGroupState.ReadyForReview,
            PreventsApproval: false,
        } && SelectedReviewGroup.DocumentIds.Count > 0;

    public bool CanSplitSelectedDocument =>
        SelectedReviewDocument?.GroupId is { } groupId &&
        ReviewGroups.FirstOrDefault(group => group.Id == groupId)?.DocumentIds.Count > 1 &&
        SplitGroupReason.Trim().Length >= 10;

    public bool CanRestoreSelectedDocumentPeriod => SelectedReviewDocument?.PeriodOverride is not null;

    public string SelectedDocumentClientSummary => SelectedReviewDocument switch
    {
        null => "Selecione um documento para conferir o cliente.",
        { ClientId: null } => "Cliente ainda não identificado. Resolva esta pendência antes de liberar o documento.",
        { ResolutionMethod: ClientResolutionMethod.ManualOverride, ClientDisplayName: { Length: > 0 } name, ClientTaxIdMasked: { Length: > 0 } taxId } =>
            $"Cliente confirmado por você: {name} • {taxId}",
        { ClientDisplayName: { Length: > 0 } name, ClientTaxIdMasked: { Length: > 0 } taxId } =>
            $"Cliente identificado: {name} • {taxId}",
        { ClientDisplayName: { Length: > 0 } name } => $"Cliente identificado: {name}",
        _ => "Cliente identificado pelo cadastro local.",
    };

    public string SelectedDocumentRecognitionMethodSummary => SelectedReviewDocument switch
    {
        null => "Selecione um documento para ver como o cliente foi reconhecido.",
        { ClientId: null } => "Nenhuma associação foi feita automaticamente.",
        { ResolutionMethod: var method } =>
            $"Como foi identificado: {FriendlyTextConverter.ToFriendlyText(method)}.",
    };

    public string SelectedReviewGroupStatusSummary => SelectedReviewGroup?.ApprovalSummary ??
        "Aguardando a correção deste documento.";

    public string SelectedDocumentGroupingSummary
    {
        get
        {
            if (SelectedReviewDocument is null)
            {
                return "Selecione um documento para ver como ele será organizado.";
            }

            if (SelectedReviewDocument.ClientId is null)
            {
                return "Este documento ainda não foi organizado porque o cliente não pôde ser confirmado.";
            }

            if (SelectedReviewGroup is null)
            {
                var sameClientCount = VisibleReviewDocuments.Count(document =>
                    document.ClientId == SelectedReviewDocument.ClientId);
                return sameClientCount == 1
                    ? "O cliente foi identificado, mas este documento ainda precisa das correções indicadas antes de seguir."
                    : $"Há {sameClientCount} documentos deste cliente na competência. Eles serão reunidos quando as pendências e as regras contábeis permitirem.";
            }

            var fileCount = SelectedReviewGroup.DocumentIds.Count;
            return fileCount == 1
                ? $"Este documento foi organizado automaticamente para {SelectedReviewGroup.ClientDisplayName}, na competência {SelectedReviewGroup.PeriodLabel}."
                : $"{fileCount} documentos de {SelectedReviewGroup.ClientDisplayName} foram organizados automaticamente para seguirem juntos na competência {SelectedReviewGroup.PeriodLabel}.";
        }
    }

    public string SelectedDocumentGuidance => SelectedReviewDocument switch
    {
        null => "Selecione um documento à esquerda para ver o que falta e as ações disponíveis.",
        { State: ReviewDocumentState.Duplicate } =>
            "Este conteúdo já foi importado. Confira o arquivo original e retire esta cópia da revisão se ela entrou por engano.",
        _ when SelectedDocumentHasInactiveClientIssue =>
            "O cadastro associado está inativo. Abra-o e clique em Reativar agora; ao voltar, a análise será atualizada.",
        { ResolutionBlockers.Count: > 0 } =>
            "O cliente não pôde ser confirmado. Escolha uma alternativa autenticada ou abra o cadastro para corrigir os identificadores.",
        { Period.Kind: DocumentPeriodKind.Unknown } =>
            "A competência não foi reconhecida. Informe o mês e o ano abaixo e registre o motivo da correção.",
        { BlockingFindingCount: > 0 } =>
            "Leia cada pendência abaixo. As correções disponíveis aparecem neste mesmo painel.",
        _ => "Documento conferido. Se o conjunto estiver pronto, libere-o para preparar a mensagem.",
    };

    public bool HasSelectedTemplate => SelectedTemplate is not null;

    public bool IsEditingTemplate => editingTemplateId.HasValue;

    public string TemplateEditorTitle => IsEditingTemplate
        ? "Editar mensagem personalizada"
        : "Nova mensagem personalizada";

    public string TemplateSaveActionLabel => IsEditingTemplate
        ? "Atualizar mensagem"
        : "Criar mensagem";

    public string TemplateStatusActionLabel => SelectedTemplate?.IsActive == true
        ? "Inativar mensagem"
        : "Reativar mensagem";

    public int TemplateNameLength => TemplateName.Length;

    public int TemplateSubjectLength => TemplateSubject.Length;

    public int TemplateBodyLength => TemplateBody.Length;

    public bool IsLocalEmailSimulation =>
        ActiveEmailProviderKey == DispatchWorkflowOptions.FakeProviderKey;

    public bool CanShowGmailConnectionAction =>
        ActiveEmailProviderKey == DispatchWorkflowOptions.GmailProviderKey;

    public bool CanShowMicrosoftConnectionAction =>
        ActiveEmailProviderKey == DispatchWorkflowOptions.MicrosoftGraphProviderKey;

    [ObservableProperty]
    public partial AppSection CurrentSection { get; set; } = AppSection.Home;

    public bool IsHomeSection => CurrentSection == AppSection.Home;

    public bool IsClientsSection => CurrentSection == AppSection.Clients;

    public bool IsDocumentsSection => CurrentSection == AppSection.Documents;

    public bool IsDispatchSection => CurrentSection == AppSection.Dispatch;

    public bool IsReportsSection => CurrentSection == AppSection.Reports;

    public bool IsHistorySection => CurrentSection == AppSection.History;

    public bool IsSettingsSection => CurrentSection == AppSection.Settings;

    public string SectionTitle => CurrentSection switch
    {
        AppSection.Home => "Visão geral",
        AppSection.Clients => "Clientes",
        AppSection.Documents => "Documentos",
        AppSection.Dispatch => "Mensagens e envios",
        AppSection.Reports => "Relatórios",
        AppSection.History => "Histórico",
        AppSection.Settings => "Configurações",
        _ => ProductName,
    };

    public string SectionDescription => CurrentSection switch
    {
        AppSection.Home => "Acompanhe o trabalho e continue de onde parou.",
        AppSection.Clients => "Dados essenciais, contato de entrega e preferências em um único cadastro.",
        AppSection.Documents => "Importe, confira as pendências e aprove somente o que estiver correto.",
        AppSection.Dispatch => "Prepare a mensagem, confira anexos e conclua cada comunicação.",
        AppSection.Reports => "Gere registros claros para conferência e prestação de contas.",
        AppSection.History => "Consulte as decisões e alterações realizadas no aplicativo.",
        AppSection.Settings => "Contas de e-mail, cópia de segurança e informações do aplicativo.",
        _ => string.Empty,
    };

    private DispatchOperationMode EffectiveDispatchOperationMode =>
        SelectedDispatchItem?.Mode ?? SelectedDispatchOperationMode;

    private DispatchOutcomePresentation? SelectedDispatchOutcome => SelectedDispatchItem is null
        ? null
        : DispatchOutcomePresenter.Present(SelectedDispatchItem, LatestAttemptFor(SelectedDispatchItem.Id));

    private bool IsSelectedDispatchSimulation
    {
        get
        {
            if (SelectedDispatchItem is null)
            {
                return IsLocalEmailSimulation;
            }

            var latestAttempt = LatestAttemptFor(SelectedDispatchItem.Id);
            return SelectedDispatchItem.Message is null && latestAttempt is null
                ? IsLocalEmailSimulation
                : DispatchOutcomePresenter.Present(SelectedDispatchItem, latestAttempt).IsSimulation;
        }
    }

    private string ActiveProviderConfirmationSuffix() => ActiveEmailProviderKey switch
    {
        DispatchWorkflowOptions.GmailProviderKey => " GMAIL",
        DispatchWorkflowOptions.MicrosoftGraphProviderKey => " GRAPH",
        _ => string.Empty,
    };

    public bool CanSelectDispatchOperationMode => SelectedDispatchItem is null;

    public bool IsSendMode => EffectiveDispatchOperationMode == DispatchOperationMode.Send;

    public string DispatchActionLabel => EffectiveDispatchOperationMode switch
    {
        DispatchOperationMode.Test => "Gerar teste seguro",
        DispatchOperationMode.Draft => "Criar rascunho",
        DispatchOperationMode.Send => "Enviar agora",
        _ => "Concluir",
    };

    public string DispatchConfirmationGuidance
    {
        get
        {
            if (!IsSendMode || SelectedDispatchItem?.Message is null)
            {
                return string.Empty;
            }

            var provider = ActiveProviderConfirmationSuffix();
            return $"Para confirmar somente esta mensagem, digite exatamente: CONFIRMAR{provider} {SelectedDispatchItem.Message.Attachments.Count}";
        }
    }

    public bool ShowDispatchBatchConfirmation => IsSendMode && CanExecuteSelectedDispatchBatch;

    public string DispatchBatchConfirmationGuidance
    {
        get
        {
            if (!ShowDispatchBatchConfirmation || SelectedDispatchItem is null)
            {
                return string.Empty;
            }

            var batch = ProcessingBatches.FirstOrDefault(item => item.Id == SelectedDispatchItem.BatchId);
            if (batch is null)
            {
                return string.Empty;
            }

            var approvedItems = DispatchItems
                .Where(item => batch.DispatchItemIds.Contains(item.Id) && item.IsApproved)
                .ToArray();
            var attachmentCount = approvedItems.Sum(item => item.Message?.Attachments.Count ?? 0);
            return $"Para concluir as {approvedItems.Length} mensagens aprovadas, digite exatamente: " +
                $"CONFIRMAR{ActiveProviderConfirmationSuffix()} LOTE {approvedItems.Length} {attachmentCount}";
        }
    }

    public string DispatchModeHelp => SelectedDispatchItem is not null
        ? $"Esta mensagem foi preparada como {FriendlyTextConverter.ToFriendlyText(SelectedDispatchItem.Mode)}. Para escolher outro modo, use “Preparar outra mensagem”."
        : SelectedDispatchOperationMode switch
        {
            DispatchOperationMode.Test => "Confere todo o fluxo somente dentro do aplicativo; nenhum e-mail é enviado.",
            DispatchOperationMode.Draft => "Cria a mensagem na conta conectada para uma última conferência, sem enviar.",
            DispatchOperationMode.Send => "Solicita o envio somente depois da conferência, aprovação e confirmação final.",
            _ => string.Empty,
        };

    public string DispatchSafetyTitle => IsSelectedDispatchSimulation
        ? "Simulação local — nenhum e-mail será enviado"
        : !IsEmailAccountConnected
            ? "Conta de e-mail desconectada"
            : EffectiveDispatchOperationMode == DispatchOperationMode.Draft
                ? "Rascunho na conta conectada"
                : "Operação na conta conectada";

    public string DispatchSafetyMessage => IsSelectedDispatchSimulation
        ? "Você pode preparar, conferir, aprovar e concluir o teste. O resultado fica somente neste aplicativo e não chega a nenhuma caixa postal."
        : !IsEmailAccountConnected
            ? "Conecte a conta em Configurações antes de criar um rascunho ou solicitar um envio real."
            : EffectiveDispatchOperationMode switch
            {
                DispatchOperationMode.Test => "O destinatário do cliente será substituído pela caixa controlada de teste.",
                DispatchOperationMode.Draft => "O aplicativo criará um rascunho para nova conferência; não enviará a mensagem.",
                DispatchOperationMode.Send => "Somente esta opção solicita o envio depois da aprovação e da confirmação final.",
                _ => string.Empty,
            };

    public string DispatchSafetyBackground => IsSelectedDispatchSimulation || !IsEmailAccountConnected
        ? "#FFF4D8"
        : "#EAF4E8";

    public string DispatchSafetyForeground => IsSelectedDispatchSimulation || !IsEmailAccountConnected
        ? "#694B10"
        : "#2D6535";

    public bool HasDispatchOutcome => SelectedDispatchItem?.State is
        DispatchItemState.DraftCreated or
        DispatchItemState.AcceptedByProvider or
        DispatchItemState.Reconciled or
        DispatchItemState.Completed or
        DispatchItemState.Failed or
        DispatchItemState.Ambiguous;

    public string DispatchOutcomeTitle => SelectedDispatchOutcome?.OperationResult ?? string.Empty;

    public string DispatchOutcomeMessage => SelectedDispatchOutcome is { } outcome
        ? $"{outcome.DeliveryStatus}. {outcome.Evidence}. {outcome.NextAction}"
        : string.Empty;

    partial void OnCurrentSectionChanged(AppSection value)
    {
        OnPropertyChanged(nameof(IsHomeSection));
        OnPropertyChanged(nameof(IsClientsSection));
        OnPropertyChanged(nameof(IsDocumentsSection));
        OnPropertyChanged(nameof(IsDispatchSection));
        OnPropertyChanged(nameof(IsReportsSection));
        OnPropertyChanged(nameof(IsHistorySection));
        OnPropertyChanged(nameof(IsSettingsSection));
        OnPropertyChanged(nameof(SectionTitle));
        OnPropertyChanged(nameof(SectionDescription));
    }

    partial void OnSelectedDispatchOperationModeChanged(DispatchOperationMode value)
    {
        if (SelectedDispatchItem is not null && value != SelectedDispatchItem.Mode)
        {
            SelectedDispatchOperationMode = SelectedDispatchItem.Mode;
            return;
        }

        SendConfirmationPhrase = string.Empty;
        BatchSendConfirmationPhrase = string.Empty;
        OnPropertyChanged(nameof(IsSendMode));
        OnPropertyChanged(nameof(CanSelectDispatchOperationMode));
        OnPropertyChanged(nameof(DispatchActionLabel));
        OnPropertyChanged(nameof(DispatchModeHelp));
        OnPropertyChanged(nameof(DispatchConfirmationGuidance));
        OnPropertyChanged(nameof(ShowDispatchBatchConfirmation));
        OnPropertyChanged(nameof(DispatchBatchConfirmationGuidance));
        RefreshDispatchSafetyPresentation();
    }

    partial void OnSelectedReportScopeChanged(ReportScopeOption value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportClientFilterChanged(ReportClientFilterOption value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportYearChanged(OperationalYearOption? value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportMonthChanged(OperationalMonthOption? value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportStartYearChanged(OperationalYearOption? value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportStartMonthChanged(OperationalMonthOption? value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportEndYearChanged(OperationalYearOption? value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportEndMonthChanged(OperationalMonthOption? value) =>
        RefreshReportPresentation();

    partial void OnSelectedReportClientChanged(ClientListRow? value) =>
        RefreshReportPresentation();

    partial void OnSelectedHistoryCleanupScopeChanged(HistoryCleanupScopeOption value)
    {
        OnPropertyChanged(nameof(IsCustomHistoryCleanup));
        OnPropertyChanged(nameof(IsHistoryDayCleanup));
        OnPropertyChanged(nameof(IsHistoryHourCleanup));
        OnPropertyChanged(nameof(IsDayOrHourHistoryCleanup));
        OnPropertyChanged(nameof(HistoryCleanupHelp));
        OnPropertyChanged(nameof(HistoryCleanupSelectionSummary));
        OnPropertyChanged(nameof(HistoryCleanupActionLabel));
    }

    partial void OnHistoryRangeStartDateChanged(DateTimeOffset? value) =>
        OnPropertyChanged(nameof(HistoryCleanupSelectionSummary));

    partial void OnHistoryRangeStartTimeChanged(TimeSpan? value) =>
        OnPropertyChanged(nameof(HistoryCleanupSelectionSummary));

    partial void OnHistoryRangeEndDateChanged(DateTimeOffset? value) =>
        OnPropertyChanged(nameof(HistoryCleanupSelectionSummary));

    partial void OnHistoryRangeEndTimeChanged(TimeSpan? value) =>
        OnPropertyChanged(nameof(HistoryCleanupSelectionSummary));

    partial void OnSelectedHistoryHourChanged(HistoryHourOption value) =>
        OnPropertyChanged(nameof(HistoryCleanupSelectionSummary));

    partial void OnSelectedHistoryTimeScopeChanged(HistoryTimeScopeOption value)
    {
        HistoryFilterError = string.Empty;
        OnPropertyChanged(nameof(HistoryTimeScopeHelp));
        OnPropertyChanged(nameof(IsHistoryFilterDay));
        OnPropertyChanged(nameof(IsHistoryFilterMonth));
        OnPropertyChanged(nameof(IsHistoryFilterYear));
        OnPropertyChanged(nameof(IsHistoryFilterCustomInterval));
    }

    partial void OnDispatchQueueSearchTextChanged(string value) => RefreshDispatchQueuePresentation();

    partial void OnSelectedDispatchQueueFilterChanged(DispatchQueueFilterOption value) =>
        RefreshDispatchQueuePresentation();

    partial void OnSelectedMergeGroupOptionChanged(ManualGroupOption? value) =>
        OnPropertyChanged(nameof(CanMergeSelectedGroups));

    partial void OnShowHiddenHistoryChanged(bool value) => RefreshHistoryPresentation();

    partial void OnSelectedOperationalYearChanged(OperationalYearOption? value)
    {
        if (isRebuildingOperationalYears)
        {
            return;
        }

        RefreshPeriodPresentation();
        QueuePreferencesSave();
    }

    partial void OnSelectedOperationalMonthChanged(OperationalMonthOption? value)
    {
        RefreshPeriodPresentation();
        QueuePreferencesSave();
    }

    partial void OnSelectedPersonTypeChanged(PersonTypeModel value)
    {
        OnPropertyChanged(nameof(IsLegalEntity));
        OnPropertyChanged(nameof(IsIndividual));
        OnPropertyChanged(nameof(PrimaryTaxIdLabel));
        OnPropertyChanged(nameof(PrimaryTaxIdPlaceholder));
        OnPropertyChanged(nameof(PrimaryTaxIdHelp));
        OnPropertyChanged(nameof(LegalNameLabel));
        if (!string.IsNullOrWhiteSpace(PrimaryTaxId))
        {
            var digits = new string(PrimaryTaxId.Where(char.IsAsciiDigit).ToArray());
            var expectedLength = value == PersonTypeModel.LegalEntity ? 14 : 11;
            if (digits.Length > expectedLength)
            {
                PrimaryTaxId = string.Empty;
                SetClientFeedback(
                    $"O {PrimaryTaxIdLabel} foi limpo ao trocar o tipo de cliente para evitar uma associação incorreta.");
            }
            else
            {
                PrimaryTaxId = FormatTaxId(PrimaryTaxId, value);
            }
        }
    }

    partial void OnMaskClientTaxIdsChanged(bool value)
    {
        for (var index = 0; index < Clients.Count; index++)
        {
            Clients[index] = Clients[index] with { MaskTaxId = value };
        }

        StatusMessage = value
            ? "CPF e CNPJ estão protegidos na lista. O cadastro aberto continua disponível para conferência."
            : "CPF e CNPJ completos estão visíveis nesta tela até você ativar a proteção novamente.";
    }

    partial void OnPrimaryTaxIdChanged(string value)
    {
        if (isFormattingTaxId)
        {
            return;
        }

        var formatted = FormatTaxId(value, SelectedPersonType);
        if (!string.Equals(formatted, value, StringComparison.Ordinal))
        {
            isFormattingTaxId = true;
            try
            {
                PrimaryTaxId = formatted;
            }
            finally
            {
                isFormattingTaxId = false;
            }
        }
    }

    partial void OnNewPartnerCpfChanged(string value)
    {
        if (partnerInputErrorTargetsCpf)
        {
            var validation = ClientPartnerInputValidator.ValidateOptionalCpf(value);
            PartnerInputError = validation.ErrorMessage ?? string.Empty;
            partnerInputErrorTargetsCpf = !validation.IsValid;
        }

        if (value.Any(character =>
                !char.IsAsciiDigit(character) &&
                !char.IsWhiteSpace(character) &&
                character is not '.' and not '-'))
        {
            return;
        }

        var formatted = FormatTaxId(value, PersonTypeModel.Individual);
        if (!string.Equals(formatted, value, StringComparison.Ordinal))
        {
            NewPartnerCpf = formatted;
        }
    }

    partial void OnInputFolderPathChanged(string value)
    {
        OnPropertyChanged(nameof(InputFolderDisplay));
        OnPropertyChanged(nameof(HasConfiguredInputFolder));
        QueuePreferencesSave();
    }

    partial void OnIsClientListPanelExpandedChanged(bool value)
    {
        if (!value && !IsClientEditorPanelExpanded)
        {
            IsClientEditorPanelExpanded = true;
        }
    }

    partial void OnIsClientEditorPanelExpandedChanged(bool value)
    {
        if (!value && !IsClientListPanelExpanded)
        {
            IsClientListPanelExpanded = true;
        }
    }

    partial void OnReportOutputDirectoryChanged(string value) => QueuePreferencesSave();

    partial void OnDocumentArchiveDirectoryChanged(string value) => QueuePreferencesSave();

    partial void OnIncludeSubfoldersChanged(bool value) => QueuePreferencesSave();

    partial void OnKeepEmailSessionChanged(bool value) => QueuePreferencesSave();

    partial void OnSelectedRetentionOptionChanged(RetentionOption value) => QueuePreferencesSave();

    partial void OnSelectedReleaseChannelChanged(ReleaseChannelOption value)
    {
        CanDownloadApplicationUpdate = false;
        CanApplyApplicationUpdate = false;
        AppUpdateProgress = 0;
        AppUpdateSummary =
            $"Canal {value.Label}: {value.Help} Verifique manualmente quando desejar.";
        QueuePreferencesSave();
    }

    [RelayCommand]
    private void ShowHome()
    {
        CurrentSection = AppSection.Home;
        StatusMessage = "Tudo pronto. Escolha uma área no menu ou importe os documentos do mês.";
    }

    [RelayCommand]
    private async Task ShowClientsAsync()
    {
        CurrentSection = AppSection.Clients;
        if (!IsClientListPanelExpanded && !IsClientEditorPanelExpanded)
        {
            IsClientListPanelExpanded = true;
        }

        if (Clients.Count == 0 && catalogService is not null)
        {
            await LoadClientsAsync();
        }
    }

    [RelayCommand]
    private void ToggleClientListPanel() =>
        IsClientListPanelExpanded = !IsClientListPanelExpanded;

    [RelayCommand]
    private void ToggleClientEditorPanel() =>
        IsClientEditorPanelExpanded = !IsClientEditorPanelExpanded;

    [RelayCommand]
    private void ShowDocuments()
    {
        CurrentSection = AppSection.Documents;
        if (ReviewDocuments.Count > 0 && !VisibleReviewDocuments.Any())
        {
            StatusMessage =
                $"Há {HiddenReviewDocumentCount} documento(s) fora da competência selecionada. Use “Mostrar todos” se desejar consultá-los.";
        }
        else
        {
            StatusMessage = ReviewDocuments.Count == 0
                ? "Comece escolhendo PDFs ou uma pasta de entrada."
                : $"Confira os documentos de {WorkPeriodLabel} e resolva as pendências antes de aprovar.";
        }

        if (SelectedReviewDocument is not null &&
            !VisibleReviewDocuments.Any(document => document.Id == SelectedReviewDocument.Id))
        {
            SelectedReviewDocument = null;
        }

        SelectedReviewDocument ??= VisibleReviewDocuments.FirstOrDefault(document =>
            document.State is ReviewDocumentState.Blocked or ReviewDocumentState.Duplicate) ??
            VisibleReviewDocuments.FirstOrDefault();
        SelectedReviewGroup = SelectedReviewDocument?.GroupId is { } selectedGroupId
            ? ReviewGroups.FirstOrDefault(group => group.Id == selectedGroupId)
            : null;
    }

    [RelayCommand]
    private void ShowAllPeriods()
    {
        SelectedOperationalYear = OperationalYears.FirstOrDefault(item => item.Year is null);
        SelectedOperationalMonth = OperationalMonths.FirstOrDefault(item => item.Month is null);
        StatusMessage = "Exibindo documentos, conjuntos e mensagens de todos os períodos.";
    }

    [RelayCommand]
    private void ShowDispatch()
    {
        CurrentSection = AppSection.Dispatch;
        StatusMessage = $"Envios usa somente conjuntos liberados em Documentos para {WorkPeriodLabel}.";
    }

    [RelayCommand]
    private async Task ShowReportsAsync()
    {
        CurrentSection = AppSection.Reports;
        StatusMessage = "Escolha o cliente e o período de forma independente para gerar a planilha e o PDF.";
        await ExecuteAsync(async () =>
        {
            await LoadReportClientsAsync(CancellationToken.None);
            StatusMessage = ReportClients.Count == 0
                ? "Nenhum cliente com documentos ou mensagens foi encontrado. Os relatórios gerais por período continuam disponíveis."
                : "Escolha o cliente e o período de forma independente para gerar a planilha e o PDF.";
        });
    }

    [RelayCommand]
    private async Task ShowHistoryAsync()
    {
        CurrentSection = AppSection.History;
        StatusMessage = $"Histórico filtrado por {WorkPeriodLabel}.";
        await ExecuteAsync(async () =>
        {
            await LoadHistoryFilterOptionsAsync(CancellationToken.None);
            RefreshHistoryPresentation();
        });
    }

    [RelayCommand]
    private async Task ApplyHistoryFiltersAsync()
    {
        HistoryFilterError = string.Empty;
        if (!TryBuildHistoryTimeWindow(out var timeWindow, out var error))
        {
            HistoryFilterError = error;
            StatusMessage = error;
            return;
        }

        var candidateFilter = new HistoryFilterCriteria(
            timeWindow,
            ClientId: SelectedHistoryClientFilter.ClientId,
            DocumentId: SelectedHistoryDocumentFilter.DocumentId,
            SearchText: EmptyToNull(HistorySearchText));

        IReadOnlyList<AuditEventModel> candidateCatalogAudit = [];
        Guid? candidateCatalogClientId = null;
        if (SelectedHistoryClientFilter.ClientId is { } clientId && catalogService is not null)
        {
            var loaded = await ExecuteAsync(async () =>
            {
                candidateCatalogAudit = await catalogService.GetAuditAsync(clientId, CancellationToken.None);
                candidateCatalogClientId = clientId;
            });
            if (!loaded)
            {
                HistoryFilterError = $"Os filtros não foram aplicados. {StatusMessage}";
                StatusMessage = HistoryFilterError;
                return;
            }
        }

        activeHistoryFilter = candidateFilter;
        Replace(HistoryCatalogAuditEvents, candidateCatalogAudit);
        loadedHistoryCatalogClientId = candidateCatalogClientId;
        HistoryAppliedFilterSummary = BuildHistoryAppliedFilterSummary();
        OnPropertyChanged(nameof(HistoryAppliedFilterSummary));
        OnPropertyChanged(nameof(HasActiveHistoryFilters));
        RefreshHistoryPresentation();
        StatusMessage = HistoryAppliedFilterSummary;
    }

    [RelayCommand]
    private void ClearHistoryFilters()
    {
        SelectedHistoryTimeScope = HistoryTimeScopes[0];
        HistoryFilterStartDate = DateTimeOffset.Now;
        HistoryFilterStartTime = TimeSpan.Zero;
        HistoryFilterEndDate = DateTimeOffset.Now;
        HistoryFilterEndTime = DateTimeOffset.Now.TimeOfDay;
        SelectedHistoryCalendarYear = DateTimeOffset.Now.Year;
        SelectedHistoryCalendarMonth = OperationalMonths.First(item => item.Month == DateTimeOffset.Now.Month);
        SelectedHistoryClientFilter = HistoryClientFilters.FirstOrDefault() ?? new(null, "Todos os clientes");
        SelectedHistoryDocumentFilter = HistoryDocumentFilters.FirstOrDefault() ?? new(null, "Todos os documentos");
        HistorySearchText = string.Empty;
        HistoryFilterError = string.Empty;
        activeHistoryFilter = HistoryFilterCriteria.All;
        loadedHistoryCatalogClientId = null;
        HistoryCatalogAuditEvents.Clear();
        HistoryAppliedFilterSummary = "Exibindo todos os acontecimentos preservados.";
        OnPropertyChanged(nameof(HistoryAppliedFilterSummary));
        OnPropertyChanged(nameof(HasActiveHistoryFilters));
        RefreshHistoryPresentation();
        StatusMessage = "Filtros próprios do Histórico removidos. A competência do cabeçalho foi mantida.";
    }

    [RelayCommand]
    private async Task ArchiveHistoryViewAsync()
    {
        var now = DateTimeOffset.UtcNow;
        var selected = SelectedHistoryCleanupScope.Value;
        var eventIds = Array.Empty<Guid>();
        DateTimeOffset? startUtc = null;
        DateTimeOffset? endExclusiveUtc = null;

        switch (selected)
        {
            case HistoryVisibilityRuleKind.AllUntilNow:
                eventIds = VisibleReviewAuditEvents.Select(item => item.Id)
                    .Concat(VisibleDispatchAuditEvents.Select(item => item.Id))
                    .Concat(VisibleCatalogAuditEvents.Select(item => item.Id))
                    .Distinct()
                    .ToArray();
                if (eventIds.Length == 0)
                {
                    StatusMessage = "Não há acontecimentos visíveis nos filtros atuais para ocultar.";
                    return;
                }
                break;
            case HistoryVisibilityRuleKind.SelectedOperationalPeriod:
                if (!HasSingleMonthlyPeriodSelected())
                {
                    StatusMessage = "Escolha um único mês e ano no cabeçalho para limpar essa competência da visualização.";
                    return;
                }

                eventIds = ReviewAuditEvents.Where(AuditMatchesSelectedPeriod).Select(item => item.Id)
                    .Concat(DispatchAuditEvents.Where(AuditMatchesSelectedPeriod).Select(item => item.Id))
                    .Distinct()
                    .ToArray();
                if (eventIds.Length == 0)
                {
                    StatusMessage = "Não há ações dessa competência para ocultar da tela.";
                    return;
                }
                break;
            case HistoryVisibilityRuleKind.CalendarDay:
                if (HistoryRangeStartDate is null)
                {
                    StatusMessage = "Escolha o dia que deseja limpar da visualização.";
                    return;
                }

                startUtc = LocalDateTimeToUtc(HistoryRangeStartDate.Value, TimeSpan.Zero);
                endExclusiveUtc = LocalDateTimeToUtc(HistoryRangeStartDate.Value.AddDays(1), TimeSpan.Zero);
                break;
            case HistoryVisibilityRuleKind.ClockHour:
                if (HistoryRangeStartDate is null)
                {
                    StatusMessage = "Escolha o dia e a hora que deseja limpar da visualização.";
                    return;
                }

                var hour = new TimeSpan(SelectedHistoryHour.Hour, 0, 0);
                startUtc = LocalDateTimeToUtc(HistoryRangeStartDate.Value, hour);
                endExclusiveUtc = startUtc.Value.AddHours(1);
                break;
            case HistoryVisibilityRuleKind.CustomInterval:
                if (HistoryRangeStartDate is null || HistoryRangeStartTime is null ||
                    HistoryRangeEndDate is null || HistoryRangeEndTime is null)
                {
                    StatusMessage = "Informe o início e o fim do intervalo que deseja ocultar.";
                    return;
                }

                startUtc = LocalDateTimeToUtc(HistoryRangeStartDate.Value, HistoryRangeStartTime.Value);
                endExclusiveUtc = LocalDateTimeToUtc(HistoryRangeEndDate.Value, HistoryRangeEndTime.Value);
                if (endExclusiveUtc <= startUtc)
                {
                    StatusMessage = "O fim do intervalo deve ser posterior ao início.";
                    return;
                }
                break;
        }

        var rule = new HistoryVisibilityRule(
            Guid.NewGuid(),
            selected,
            now,
            startUtc,
            endExclusiveUtc,
            SelectedOperationalYear?.Year,
            SelectedOperationalMonth?.Month,
            eventIds);
        historyVisibility = new HistoryVisibilityState([.. historyVisibility.Rules, rule]);
        ShowHiddenHistory = false;
        await SavePreferencesAsync(CancellationToken.None);
        RefreshHistoryPresentation();
        StatusMessage = "Histórico limpo da visualização. Os registros de auditoria foram preservados e podem ser restaurados na tela.";
    }

    [RelayCommand]
    private async Task RestoreHistoryViewAsync()
    {
        historyVisibility = HistoryVisibilityState.Empty;
        ShowHiddenHistory = false;
        await SavePreferencesAsync(CancellationToken.None);
        RefreshHistoryPresentation();
        StatusMessage = "Todo o histórico preservado voltou a aparecer na tela.";
    }

    [RelayCommand]
    private void ShowSettings()
    {
        CurrentSection = AppSection.Settings;
        StatusMessage = "Preferências locais, contas de e-mail, pastas, backup e retenção.";
    }

    [RelayCommand]
    private void ShowEmailSettings()
    {
        CurrentSection = AppSection.Settings;
        StatusMessage = "Consulte Google Gmail e Microsoft Outlook/Microsoft 365. A conexão só é liberada após configuração segura pelo suporte.";
    }

    [RelayCommand]
    private async Task CheckForApplicationUpdatesAsync()
    {
        if (appUpdateService is null)
        {
            AppUpdateSummary = "O atualizador não está disponível nesta instalação.";
            return;
        }

        IsUpdatingApplication = true;
        AppUpdateProgress = 0;
        CanDownloadApplicationUpdate = false;
        CanApplyApplicationUpdate = false;
        AppUpdateSummary = "Consultando o canal oficial sem enviar dados de clientes…";
        try
        {
            ApplyUpdateSnapshot(await appUpdateService.CheckAsync(
                SelectedReleaseChannel.Value,
                CancellationToken.None));
        }
        catch (AppUpdateException exception)
        {
            AppUpdateSummary = exception.Message;
        }
        finally
        {
            IsUpdatingApplication = false;
        }
    }

    [RelayCommand]
    private async Task DownloadApplicationUpdateAsync()
    {
        if (appUpdateService is null || !CanDownloadApplicationUpdate)
        {
            AppUpdateSummary = "Verifique novamente antes de baixar uma atualização.";
            return;
        }

        IsUpdatingApplication = true;
        CanDownloadApplicationUpdate = false;
        CanApplyApplicationUpdate = false;
        AppUpdateSummary = "Baixando e conferindo o pacote…";
        var progress = new Progress<int>(value => AppUpdateProgress = value);
        try
        {
            ApplyUpdateSnapshot(await appUpdateService.DownloadAsync(
                progress,
                CancellationToken.None));
        }
        catch (AppUpdateException exception)
        {
            AppUpdateProgress = 0;
            AppUpdateSummary = exception.Message;
        }
        finally
        {
            IsUpdatingApplication = false;
        }
    }

    [RelayCommand]
    private void ApplyApplicationUpdate()
    {
        if (appUpdateService is null || !CanApplyApplicationUpdate)
        {
            AppUpdateSummary = "Baixe e confira a atualização antes de reiniciar.";
            return;
        }

        try
        {
            AppUpdateSummary = "Fechando para aplicar a atualização conferida…";
            appUpdateService.ApplyAndRestart();
        }
        catch (AppUpdateException exception)
        {
            AppUpdateSummary = exception.Message;
        }
    }

    [RelayCommand]
    private void ContinueToDispatch()
    {
        if (!CanContinueToDispatch)
        {
            StatusMessage = $"Nenhum conjunto foi liberado em {WorkPeriodLabel}. Confira os documentos antes de continuar.";
            return;
        }

        CurrentSection = AppSection.Dispatch;
        SelectedReviewGroup = VisibleApprovedReviewGroups.FirstOrDefault();
        StatusMessage = $"Conjuntos liberados de {WorkPeriodLabel} carregados para preparar as mensagens.";
    }

    [RelayCommand]
    private async Task ContinueToReportsAsync()
    {
        CurrentSection = AppSection.Reports;
        await ExecuteAsync(async () =>
        {
            await LoadReportClientsAsync(CancellationToken.None);
            StatusMessage = $"Relatórios prontos para o recorte {WorkPeriodLabel}.";
        });
    }

    [RelayCommand]
    private void StartIncidentForSelectedDispatch()
    {
        if (SelectedDispatchItem is null)
        {
            StatusMessage = "Selecione uma mensagem com tentativa registrada para relatar uma ocorrência.";
            return;
        }

        SelectedIncidentAttempt = DeliveryAttempts
            .Where(attempt => attempt.DispatchItemId == SelectedDispatchItem.Id)
            .OrderByDescending(attempt => attempt.StartedAtUtc)
            .FirstOrDefault();
        CurrentSection = AppSection.History;
        StatusMessage = SelectedIncidentAttempt is null
            ? "Ainda não há tentativa registrada para esta mensagem."
            : "Descreva apenas o necessário; dados fiscais, e-mails e segredos serão redigidos.";
    }

    [RelayCommand]
    private async Task OpenIncidentAsync()
    {
        if (incidentService is null || SelectedIncidentAttempt is null)
        {
            StatusMessage = "Selecione uma tentativa de entrega antes de abrir a ocorrência.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            ApplyIncidentWorkspace(await incidentService.OpenAsync(
                new OpenIncidentRequest(
                    SelectedIncidentAttempt.Id,
                    SelectedIncidentCategory.Value,
                    SelectedIncidentSeverity.Value,
                    IncidentSummary),
                CancellationToken.None));
            IncidentSummary = string.Empty;
            StatusMessage = "Ocorrência registrada com histórico protegido. Agora contenha e apure antes de repetir qualquer envio.";
        });
    }

    [RelayCommand]
    private async Task TransitionIncidentAsync()
    {
        if (incidentService is null || SelectedIncident is null)
        {
            StatusMessage = "Selecione uma ocorrência ativa para atualizar.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            ApplyIncidentWorkspace(await incidentService.TransitionAsync(
                new TransitionIncidentRequest(
                    SelectedIncident.Id,
                    SelectedIncident.Version,
                    SelectedIncidentStatus.Value,
                    IncidentTransitionNote),
                CancellationToken.None));
            IncidentTransitionNote = string.Empty;
            StatusMessage = "Situação da ocorrência atualizada; o evento anterior foi preservado.";
        });
    }

    partial void OnSelectedReviewDocumentChanged(ReviewDocument? value)
    {
        OnPropertyChanged(nameof(HasReviewSelection));
        OnPropertyChanged(nameof(HasSelectedDocumentFindings));
        OnPropertyChanged(nameof(SelectedDocumentHasInactiveClientIssue));
        OnPropertyChanged(nameof(HasClientCorrectionAlternatives));
        OnPropertyChanged(nameof(CanRestoreSelectedDocumentPeriod));
        OnPropertyChanged(nameof(SelectedDocumentGuidance));
        OnPropertyChanged(nameof(SelectedDocumentClientSummary));
        OnPropertyChanged(nameof(SelectedDocumentRecognitionMethodSummary));
        OnPropertyChanged(nameof(SelectedDocumentGroupingSummary));
        SelectedOverrideCandidate = value?.ClientAlternatives.FirstOrDefault();
        SelectedReviewGroup = value?.GroupId is { } groupId
            ? ReviewGroups.FirstOrDefault(group => group.Id == groupId)
            : null;
        var year = value is null ? DateTimeOffset.Now.Year : GetEffectiveYear(value.Period) ?? DateTimeOffset.Now.Year;
        var month = value is null ? DateTimeOffset.Now.Month : GetEffectiveMonth(value.Period) ?? DateTimeOffset.Now.Month;
        if (OperationalYears.All(option => option.Year != year))
        {
            EnsureOperationalYears([year]);
        }
        SelectedCorrectionYear = OperationalYears.FirstOrDefault(option => option.Year == year);
        SelectedCorrectionMonth = OperationalMonths.FirstOrDefault(option => option.Month == month);
        DocumentCorrectionReason = string.Empty;
        DocumentRemovalReason = string.Empty;
        SplitGroupReason = string.Empty;
        MergeGroupReason = string.Empty;
        IsDocumentRemovalConfirmationVisible = false;
        IsDocumentCorrectionPanelExpanded = false;
        IsDocumentOrganizationPanelExpanded = false;
        IsDocumentRecognitionPanelExpanded = false;
        OnPropertyChanged(nameof(SelectedClientReadyGroupCount));
        OnPropertyChanged(nameof(SelectedClientReadyDocumentCount));
        OnPropertyChanged(nameof(SelectedClientReadyPeriodCount));
        OnPropertyChanged(nameof(ShowSelectedClientApproval));
        OnPropertyChanged(nameof(CanApproveSelectedClientGroups));
        OnPropertyChanged(nameof(SelectedClientApprovalLabel));
        OnPropertyChanged(nameof(SelectedClientApprovalHelp));
        OnPropertyChanged(nameof(SelectedSetApprovalLabel));
        OnPropertyChanged(nameof(CanSplitSelectedDocument));
        OnPropertyChanged(nameof(SelectedReviewGroupDocuments));
        OnPropertyChanged(nameof(HasSelectedReviewGroupDocuments));
        OnPropertyChanged(nameof(SelectedGroupViewerHeader));
    }

    partial void OnSelectedReviewGroupChanged(DocumentDispatchGroup? value)
    {
        OnPropertyChanged(nameof(CanApproveSelectedReviewGroup));
        OnPropertyChanged(nameof(SelectedSetApprovalLabel));
        OnPropertyChanged(nameof(CanSplitSelectedDocument));
        OnPropertyChanged(nameof(SelectedDocumentGuidance));
        OnPropertyChanged(nameof(SelectedReviewGroupStatusSummary));
        OnPropertyChanged(nameof(SelectedDocumentGroupingSummary));
        OnPropertyChanged(nameof(CanPrepareSelectedDispatch));
        OnPropertyChanged(nameof(SelectedReviewGroupDocuments));
        OnPropertyChanged(nameof(HasSelectedReviewGroupDocuments));
        OnPropertyChanged(nameof(SelectedGroupViewerHeader));
        OnPropertyChanged(nameof(CompatibleMergeGroupOptions));
        OnPropertyChanged(nameof(HasCompatibleMergeGroups));
        SelectedMergeGroupOption = CompatibleMergeGroupOptions.FirstOrDefault();
        OnPropertyChanged(nameof(CanMergeSelectedGroups));
    }

    public async Task LoadReviewWorkspaceAsync(CancellationToken cancellationToken = default)
    {
        if (documentReviewService is null)
        {
            return;
        }

        await ExecuteAsync(async () =>
        {
            await LoadPreferencesAsync(cancellationToken);
            ApplyWorkspace(await documentReviewService.LoadAsync(cancellationToken));
            if (dispatchWorkflowService is not null)
            {
                ApplyDispatchWorkspace(await dispatchWorkflowService.LoadAsync(cancellationToken));
            }

            if (incidentService is not null)
            {
                ApplyIncidentWorkspace(await incidentService.LoadAsync(cancellationToken));
            }

            await RefreshEmailConnectionStatusAsync(cancellationToken);
            await RefreshPilotReadinessAsync(cancellationToken);
        });
    }

    [RelayCommand]
    private async Task SavePilotChecklistAsync()
    {
        if (pilotReadinessService is null || !IsPilotMode)
        {
            StatusMessage = "O checklist do piloto não está disponível nesta instalação.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            var snapshot = await pilotReadinessService.UpdateAsync(
                new PilotChecklistUpdate(
                    PilotNonProductionDataConfirmed,
                    PilotControlledAccountConfirmed,
                    PilotMacOsStationValidated,
                    PilotWindowsStationValidated,
                    PilotBackupRestoreValidated,
                    PilotRollbackValidated),
                BuildPilotMetrics(),
                CancellationToken.None);
            ApplyPilotSnapshot(snapshot);
            StatusMessage = snapshot.IsReady
                ? "Checklist concluído. O piloto está pronto apenas para execução supervisionada em homologação."
                : "Checklist salvo. Os itens pendentes continuam bloqueando o início do piloto.";
        });
    }

    [RelayCommand]
    private async Task ConnectEmailAccountAsync()
    {
        if (emailAccountConnectionService is null)
        {
            StatusMessage = "A conexão de e-mail ainda não está disponível nesta instalação.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            var status = await emailAccountConnectionService.ConnectAsync(CancellationToken.None);
            ApplyEmailConnectionStatus(status);
            StatusMessage = status.IsConnected
                ? "Conta de e-mail conectada. Os envios continuam sujeitos à conferência e à confirmação final."
                : "A conta de e-mail não foi conectada.";
        });
    }

    [RelayCommand]
    private async Task DisconnectEmailAccountAsync()
    {
        if (emailAccountConnectionService is null)
        {
            return;
        }

        await ExecuteAsync(async () =>
        {
            await emailAccountConnectionService.DisconnectAsync(CancellationToken.None);
            await RefreshEmailConnectionStatusAsync(CancellationToken.None);
            StatusMessage = "Conta de e-mail desconectada com segurança.";
            TechnicalDetails = "A sessão local de autorização foi removida do cofre protegido do sistema.";
        });
    }

    public async Task ImportDocumentsAsync(
        IReadOnlyList<string> filePaths,
        CancellationToken cancellationToken = default)
    {
        if (filePaths.Count == 0)
        {
            return;
        }

        if (documentRecognitionService is null || documentReviewService is null)
        {
            StatusMessage = "A importação local não está disponível nesta instalação.";
            return;
        }

        if (IsRecognizingDocuments)
        {
            StatusMessage = "Já existe uma importação em andamento.";
            return;
        }

        documentImportCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var importToken = documentImportCancellation.Token;
        IsRecognizingDocuments = true;
        DocumentImportProgress = 0;
        DocumentImportTotal = filePaths.Distinct(StringComparer.OrdinalIgnoreCase).Count();
        OnPropertyChanged(nameof(DocumentImportProgressText));
        var imported = 0;
        var alreadyImported = 0;
        var wasCancelled = false;
        var failures = new List<string>();
        var importedPeriods = new List<(int Year, int Month)>();
        var importedHashes = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var existingPaths = ReviewDocuments
            .Select(document => NormalizePath(document.LocalPath))
            .Where(path => path is not null)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var existingHashes = ReviewDocuments
            .Select(document => document.Sha256)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var filePath in filePaths.Distinct(StringComparer.OrdinalIgnoreCase))
            {
                try
                {
                    importToken.ThrowIfCancellationRequested();
                    var result = await documentRecognitionService.RecognizeAsync(
                        filePath,
                        importToken);
                    if (existingHashes.Contains(result.Sha256))
                    {
                        alreadyImported++;
                        continue;
                    }

                    var recognizedPeriod = documentPeriodParser.Parse(result.Fields);
                    var stagedDocument = await StageDocumentAsync(filePath, recognizedPeriod, importToken);
                    var normalizedPath = NormalizePath(stagedDocument.Path);
                    if (normalizedPath is not null && existingPaths.Contains(normalizedPath))
                    {
                        alreadyImported++;
                        continue;
                    }

                    DocumentReviewWorkspace persistedWorkspace;
                    try
                    {
                        persistedWorkspace = await documentReviewService.ImportAsync(
                            stagedDocument.Path,
                            result,
                            importToken);
                    }
                    catch (Exception persistenceException)
                    {
                        RollbackStagedDocument(stagedDocument, persistenceException);
                        throw;
                    }

                    ApplyWorkspace(persistedWorkspace);
                    RecognizedDocuments.Insert(0, result);
                    SelectedRecognizedDocument = result;
                    imported++;
                    importedHashes.Add(result.Sha256);
                    existingHashes.Add(result.Sha256);
                    var importedReview = ReviewDocuments.FirstOrDefault(document =>
                        string.Equals(document.Sha256, result.Sha256, StringComparison.OrdinalIgnoreCase));
                    if (importedReview is not null &&
                        TryGetYearMonth(importedReview.Period, out var year, out var month))
                    {
                        importedPeriods.Add((year, month));
                    }
                    if (normalizedPath is not null)
                    {
                        existingPaths.Add(normalizedPath);
                    }
                }
                catch (DocumentImportException exception)
                {
                    failures.Add($"{Path.GetFileName(filePath)}: {exception.Message}");
                }
                catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
                {
                    failures.Add($"{Path.GetFileName(filePath)}: não foi possível organizar o arquivo na pasta do acervo");
                }
                catch (OperationCanceledException)
                {
                    throw;
                }
                catch (Exception)
                {
                    failures.Add($"{Path.GetFileName(filePath)}: a importação não pôde ser concluída e nenhuma cópia parcial foi mantida");
                }
                finally
                {
                    DocumentImportProgress++;
                    OnPropertyChanged(nameof(DocumentImportProgressText));
                }
            }
        }
        catch (OperationCanceledException)
        {
            wasCancelled = true;
        }
        finally
        {
            IsRecognizingDocuments = false;
            documentImportCancellation.Dispose();
            documentImportCancellation = null;
        }

        var distinctPeriods = importedPeriods.Distinct().ToArray();

        if (importedHashes.Count > 0)
        {
            SelectedReviewDocument = ReviewDocuments.FirstOrDefault(document =>
                importedHashes.Contains(document.Sha256));
        }

        var ignoredText = alreadyImported == 0
            ? string.Empty
            : $" • {alreadyImported} já estava(m) importado(s) e não foi(ram) duplicado(s)";
        DocumentImportSummary = wasCancelled
            ? $"Importação interrompida com segurança após {imported} documento(s). O que já foi concluído permanece disponível."
            : failures.Count == 0
            ? distinctPeriods.Length > 1
                ? $"{imported} documento(s) importado(s) em {distinctPeriods.Length} competências{ignoredText}. A competência global foi mantida em {WorkPeriodLabel}; use o seletor acima para conferir cada mês."
                : $"{imported} documento(s) importado(s), reconhecido(s) e organizado(s){ignoredText}. A competência global continua em {WorkPeriodLabel}."
            : $"{imported} reconhecido(s){ignoredText}; {failures.Count} rejeitado(s): {string.Join(" | ", failures)}";
        StatusMessage = DocumentImportSummary;
        OnPropertyChanged(nameof(HiddenReviewDocumentCount));
        OnPropertyChanged(nameof(HasHiddenReviewDocuments));
        OnPropertyChanged(nameof(HasVisibleReviewDocuments));
        OnPropertyChanged(nameof(HasAnyReviewDocuments));
        OnPropertyChanged(nameof(ShowReviewEmptyState));
        OnPropertyChanged(nameof(VisibleDocumentCount));
    }

    [RelayCommand]
    private void CancelDocumentImport()
    {
        documentImportCancellation?.Cancel();
        StatusMessage = "Interrompendo após o documento atual…";
    }

    public async Task ImportInputFolderAsync(CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(InputFolderPath) || !Directory.Exists(InputFolderPath))
        {
            StatusMessage = "Escolha uma pasta de entrada válida antes de importar.";
            return;
        }

        var searchOption = IncludeSubfolders ? SearchOption.AllDirectories : SearchOption.TopDirectoryOnly;
        string[] folderFiles;
        try
        {
            folderFiles = Directory.EnumerateFiles(InputFolderPath, "*", searchOption)
                .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
                .ToArray();
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            StatusMessage = "Não foi possível ler a pasta de entrada. Confira a permissão da pasta e tente novamente.";
            return;
        }

        var files = folderFiles
            .Where(path => string.Equals(Path.GetExtension(path), ".pdf", StringComparison.OrdinalIgnoreCase))
            .ToArray();
        var unsupportedFiles = folderFiles
            .Where(path => !string.Equals(Path.GetExtension(path), ".pdf", StringComparison.OrdinalIgnoreCase))
            .ToArray();
        var unsupportedSummary = BuildUnsupportedFolderFilesSummary(unsupportedFiles);

        if (files.Length == 0)
        {
            DocumentImportSummary = unsupportedFiles.Length == 0
                ? "Nenhum arquivo foi encontrado na pasta de entrada selecionada."
                : $"Nenhum PDF foi importado. {unsupportedSummary}";
            StatusMessage = DocumentImportSummary;
            return;
        }

        await ImportDocumentsAsync(files, cancellationToken);
        if (unsupportedFiles.Length > 0)
        {
            DocumentImportSummary = $"{DocumentImportSummary} • {unsupportedSummary}";
            StatusMessage = DocumentImportSummary;
        }
    }

    public async Task SetInputFolderAsync(string path, CancellationToken cancellationToken)
    {
        InputFolderPath = path;
        await SavePreferencesAsync(cancellationToken);
        StatusMessage = "Pasta de entrada selecionada. Use “Importar desta pasta” sempre que novos PDFs forem adicionados.";
    }

    public async Task SetReportOutputDirectoryAsync(string path, CancellationToken cancellationToken)
    {
        ReportOutputDirectory = path;
        await SavePreferencesAsync(cancellationToken);
        StatusMessage = "Pasta dos relatórios atualizada.";
    }

    public async Task SetDocumentArchiveDirectoryAsync(string path, CancellationToken cancellationToken)
    {
        DocumentArchiveDirectory = path;
        await SavePreferencesAsync(cancellationToken);
        StatusMessage = "Pasta do acervo atualizada. As próximas importações serão organizadas por ano e mês.";
    }

    [RelayCommand]
    private void ReviewRetention()
    {
        if (SelectedRetentionOption.Months == 0)
        {
            RetentionReviewSummary = "Retenção por tempo indeterminado: nenhum arquivo foi selecionado para revisão e nada foi excluído.";
            return;
        }

        if (!Directory.Exists(DocumentArchiveDirectory))
        {
            RetentionReviewSummary = "A pasta do acervo ainda não existe. Nenhum arquivo foi alterado.";
            return;
        }

        try
        {
            var cutoff = DateTimeOffset.Now.AddMonths(-SelectedRetentionOption.Months);
            var candidates = Directory.EnumerateFiles(DocumentArchiveDirectory, "*.pdf", SearchOption.AllDirectories)
                .Select(path => new FileInfo(path))
                .Where(file => file.LastWriteTimeUtc < cutoff.UtcDateTime)
                .ToArray();
            var totalBytes = candidates.Sum(file => file.Length);
            RetentionReviewSummary =
                $"Revisão encontrou {candidates.Length} PDF(s), somando {FormatBytes(totalBytes)}, anteriores a {cutoff:MM/yyyy}. " +
                "Nada foi excluído; confirme obrigações fiscais e a política do escritório antes de qualquer ação fora do aplicativo.";
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            RetentionReviewSummary = "Não foi possível ler todo o acervo. Confira as permissões; nenhum arquivo foi alterado.";
        }
    }

    [RelayCommand]
    private async Task RevalidateReviewAsync()
    {
        if (documentReviewService is null)
        {
            return;
        }

        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.RevalidateAsync(CancellationToken.None));
            StatusMessage = BlockedDocumentCount == 0
                ? "Análise atualizada: não há documentos bloqueados nesta competência."
                : $"Análise atualizada: {BlockedDocumentCount} documento(s) ainda precisam de correção. Selecione o primeiro para ver a causa e a ação recomendada.";
        });
    }

    [RelayCommand]
    private async Task OpenInactiveClientFromDocumentAsync()
    {
        if (catalogService is null || SelectedReviewDocument is null)
        {
            StatusMessage = "Selecione um documento com pendência de cliente.";
            return;
        }

        var clientId = SelectedReviewDocument.ClientId ??
            SelectedOverrideCandidate?.ClientId ??
            (SelectedReviewDocument.ClientAlternatives.Count > 0
                ? SelectedReviewDocument.ClientAlternatives[0].ClientId
                : null);
        if (clientId is null)
        {
            StatusMessage = "O documento não contém uma alternativa de cliente autenticada. Cadastre o cliente ou corrija os identificadores do cadastro existente.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            IncludeInactive = true;
            SearchText = string.Empty;
            await LoadClientEditorAsync(clientId.Value, CancellationToken.None);
            await RefreshClientListAsync(CancellationToken.None);
            SelectedClient = Clients.FirstOrDefault(item => item.Id == clientId.Value);
            CurrentSection = AppSection.Clients;
            StatusMessage = IsClientActive
                ? "Cadastro aberto. Confira CPF/CNPJ, nomes e identificadores; depois atualize o cadastro."
                : "Cadastro inativo aberto. Clique em “Reativar agora”; os documentos serão analisados novamente automaticamente.";
        });
    }

    [RelayCommand]
    private async Task CorrectSelectedDocumentPeriodAsync()
    {
        if (documentReviewService is null || SelectedReviewDocument is null)
        {
            StatusMessage = "Selecione um documento antes de corrigir a competência.";
            return;
        }

        var month = SelectedCorrectionMonth?.Month;
        if (SelectedCorrectionYear?.Year is not { } year || month is not (>= 1 and <= 12))
        {
            StatusMessage = "Escolha um mês e um ano válidos para a competência.";
            return;
        }

        if (string.IsNullOrWhiteSpace(DocumentCorrectionReason) ||
            DocumentCorrectionReason.Trim().Length < 10)
        {
            StatusMessage = "Explique a correção da competência com pelo menos 10 caracteres.";
            return;
        }

        var documentId = SelectedReviewDocument.Id;
        var correctedPeriod = new DocumentPeriod(
            DocumentPeriodKind.Monthly,
            month.Value,
            year,
            null,
            null,
            SelectedReviewDocument.Period.DueDate,
            $"Correção manual: {month.Value:00}/{year:0000}");
        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.CorrectPeriodAsync(
                documentId,
                correctedPeriod,
                DocumentCorrectionReason,
                CancellationToken.None));
            DocumentCorrectionReason = string.Empty;
            StatusMessage = $"Competência corrigida para {month.Value:00}/{year:0000}, reagrupada e registrada no histórico.";
        });
    }

    [RelayCommand]
    private async Task RestoreSelectedDocumentPeriodAsync()
    {
        if (documentReviewService is null || SelectedReviewDocument?.PeriodOverride is null)
        {
            StatusMessage = "Este documento já usa a competência reconhecida no PDF.";
            return;
        }

        if (string.IsNullOrWhiteSpace(DocumentCorrectionReason) ||
            DocumentCorrectionReason.Trim().Length < 10)
        {
            StatusMessage = "Explique por que deseja restaurar a competência reconhecida, com pelo menos 10 caracteres.";
            return;
        }

        var documentId = SelectedReviewDocument.Id;
        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.RestoreExtractedPeriodAsync(
                documentId,
                DocumentCorrectionReason,
                CancellationToken.None));
            DocumentCorrectionReason = string.Empty;
            StatusMessage = "A competência reconhecida no PDF foi restaurada e o documento foi analisado novamente.";
        });
    }

    [RelayCommand]
    private void BeginRemoveSelectedDocument()
    {
        if (SelectedReviewDocument is null)
        {
            StatusMessage = "Selecione o documento que deseja retirar da revisão.";
            return;
        }

        IsDocumentRemovalConfirmationVisible = true;
        StatusMessage = "Confirme abaixo. O registro sairá da revisão, mas o PDF permanecerá no acervo para recuperação.";
    }

    [RelayCommand]
    private void PrepareDocumentRetestRemoval()
    {
        if (SelectedReviewDocument is null)
        {
            StatusMessage = "Selecione o documento que deseja retirar antes de repetir o teste.";
            return;
        }

        DocumentRemovalReason = "Retirado para repetir o teste deste período.";
        IsDocumentRemovalConfirmationVisible = true;
        StatusMessage =
            "Confirme a retirada abaixo. Depois você poderá importar o mesmo PDF novamente; a cópia do acervo continuará preservada.";
    }

    [RelayCommand]
    private void CancelRemoveSelectedDocument()
    {
        IsDocumentRemovalConfirmationVisible = false;
        DocumentRemovalReason = string.Empty;
        StatusMessage = "Retirada cancelada; o documento continua na revisão.";
    }

    [RelayCommand]
    private async Task ConfirmRemoveSelectedDocumentAsync()
    {
        if (documentReviewService is null || SelectedReviewDocument is null)
        {
            StatusMessage = "Selecione o documento que deseja retirar da revisão.";
            return;
        }

        if (string.IsNullOrWhiteSpace(DocumentRemovalReason) ||
            DocumentRemovalReason.Trim().Length < 10)
        {
            StatusMessage = "Informe o motivo da retirada com pelo menos 10 caracteres.";
            return;
        }

        var document = SelectedReviewDocument;
        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.RemoveDocumentAsync(
                document.Id,
                DocumentRemovalReason,
                CancellationToken.None));
            IsDocumentRemovalConfirmationVisible = false;
            DocumentRemovalReason = string.Empty;
            StatusMessage = File.Exists(document.LocalPath)
                ? "Documento retirado da revisão. O PDF foi preservado no acervo e a retirada ficou registrada no histórico."
                : "Documento retirado da revisão e registrado no histórico. A cópia indicada no acervo já não estava disponível.";
        });
    }

    [RelayCommand]
    private async Task ApproveSelectedGroupAsync()
    {
        if (documentReviewService is null || SelectedReviewGroup is null || !CanApproveSelectedReviewGroup)
        {
            StatusMessage = "Selecione um conjunto pronto para revisão.";
            return;
        }

        var groupId = SelectedReviewGroup.Id;
        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.ApproveGroupAsync(
                groupId,
                CancellationToken.None));
            StatusMessage = "Conjunto liberado para preparar a mensagem. Nenhum e-mail foi enviado.";
        });
    }

    [RelayCommand]
    private async Task ApproveSelectedClientGroupsAsync()
    {
        var clientId = SelectedReviewDocument?.ClientId ?? SelectedReviewGroup?.ClientId;
        if (documentReviewService is null || clientId is null)
        {
            StatusMessage = "Selecione um documento com cliente identificado.";
            return;
        }

        var groupIds = ReadyGroupsForSelectedClient.Select(group => group.Id).ToArray();
        if (groupIds.Length == 0)
        {
            StatusMessage = "Este cliente não possui conjuntos prontos para liberação.";
            return;
        }

        var documentCount = SelectedClientReadyDocumentCount;
        var periodCount = SelectedClientReadyPeriodCount;

        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.ApproveClientGroupsAsync(
                clientId.Value,
                groupIds,
                CancellationToken.None));
            StatusMessage =
                $"Cliente liberado em {groupIds.Length} conjunto(s), {documentCount} documento(s) e {periodCount} competência(s). " +
                "Cada conjunto continua separado para gerar sua própria mensagem; pendências e repetidos ficaram de fora.";
        });
    }

    [RelayCommand]
    private async Task ApproveAllEligibleAsync()
    {
        if (documentReviewService is null)
        {
            return;
        }

        if (!HasSingleMonthlyPeriodSelected())
        {
            StatusMessage = "Para liberar vários conjuntos, selecione um único mês e ano. Em “Todos os períodos”, libere cada conjunto separadamente.";
            return;
        }

        var groupIds = VisibleReviewGroups
            .Where(group => group.State == ReviewGroupState.ReadyForReview && !group.PreventsApproval)
            .Select(group => group.Id)
            .ToArray();
        if (groupIds.Length == 0)
        {
            StatusMessage = $"Não há conjuntos prontos para liberação em {WorkPeriodLabel}.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.ApproveGroupsAsync(
                groupIds,
                CancellationToken.None));

            StatusMessage = $"{groupIds.Length} conjunto(s) de {WorkPeriodLabel} liberado(s); pendências, repetidos e outros períodos ficaram de fora.";
        });
    }

    [RelayCommand]
    private async Task OverrideSelectedClientAsync()
    {
        if (documentReviewService is null ||
            SelectedReviewDocument is null ||
            SelectedOverrideCandidate is null)
        {
            StatusMessage = "Selecione um documento e uma alternativa autenticada de cliente.";
            return;
        }

        if (string.IsNullOrWhiteSpace(OverrideReason) || OverrideReason.Trim().Length < 10)
        {
            StatusMessage = "Explique a associação do cliente com pelo menos 10 caracteres.";
            return;
        }

        var documentId = SelectedReviewDocument.Id;
        var candidate = SelectedOverrideCandidate;
        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.OverrideClientAsync(
                documentId,
                candidate,
                OverrideReason,
                CancellationToken.None));
            OverrideReason = string.Empty;
            var updated = ReviewDocuments.FirstOrDefault(document => document.Id == documentId);
            StatusMessage = updated?.BlockingFindingCount > 0
                ? $"Cliente associado, mas ainda restam {updated.BlockingFindingCount} pendência(s). Veja a próxima ação recomendada."
                : "Cliente associado, análise refeita e liberação anterior revogada. O documento já pode seguir para o conjunto.";
        });
    }

    [RelayCommand]
    private async Task SplitSelectedDocumentAsync()
    {
        if (documentReviewService is null ||
            SelectedReviewDocument?.GroupId is not { } groupId)
        {
            StatusMessage = "Selecione um documento pertencente a um conjunto.";
            return;
        }

        var documentId = SelectedReviewDocument.Id;
        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.SplitGroupAsync(
                groupId,
                [documentId],
                SplitGroupReason,
                CancellationToken.None));
            SplitGroupReason = string.Empty;
            StatusMessage = "Documento separado. Confira o novo conjunto antes de liberá-lo novamente.";
        });
    }

    [RelayCommand]
    private async Task MergeSelectedGroupsAsync()
    {
        if (documentReviewService is null ||
            SelectedReviewGroup is null ||
            SelectedMergeGroupOption is null ||
            !CanMergeSelectedGroups)
        {
            StatusMessage = "Escolha um conjunto compatível e informe um motivo com pelo menos 10 caracteres.";
            return;
        }

        var targetId = SelectedReviewGroup.Id;
        var sourceId = SelectedMergeGroupOption.GroupId;
        await ExecuteAsync(async () =>
        {
            ApplyWorkspace(await documentReviewService.MergeGroupsAsync(
                targetId,
                sourceId,
                MergeGroupReason,
                CancellationToken.None));
            MergeGroupReason = string.Empty;
            StatusMessage = "Conjuntos unidos. Confira o resultado antes de liberá-lo novamente.";
        });
    }

    [RelayCommand]
    private async Task PrepareSelectedDispatchAsync()
    {
        if (dispatchWorkflowService is null || SelectedReviewGroup is null || !CanPrepareSelectedDispatch)
        {
            StatusMessage = "Selecione um conjunto liberado para preparar a mensagem deste cliente.";
            return;
        }

        var groupId = SelectedReviewGroup.Id;
        var existingItemIds = DispatchItems.Select(item => item.Id).ToHashSet();
        await ExecuteAsync(async () =>
        {
            var workspace = await dispatchWorkflowService.PrepareAsync(
                new PrepareDispatchRequest(
                    [groupId],
                    ProcessingSelectionMode.Individual,
                    SelectedDispatchOperationMode,
                    TestDestination,
                    SelectedFakeDeliveryScenario),
                CancellationToken.None);
            var preparedItemId = workspace.Items
                .Where(item => item.GroupId == groupId &&
                    item.State != DispatchItemState.Cancelled &&
                    !existingItemIds.Contains(item.Id))
                .OrderByDescending(item => item.CreatedAtUtc)
                .Select(item => (Guid?)item.Id)
                .FirstOrDefault();
            DispatchQueueSearchText = string.Empty;
            SelectedDispatchQueueFilter = DispatchQueueFilters.First(option =>
                option.Value == DispatchQueueFilterKind.Pending);
            ApplyDispatchWorkspace(workspace, preparedItemId);
            StatusMessage =
                $"{SelectedDispatchReference} preparada. Confira destinatários, texto e anexos antes de aprovar.";
        });
    }

    [RelayCommand]
    private void PrepareAnotherDispatch()
    {
        SelectedDispatchItem = null;
        SendConfirmationPhrase = string.Empty;
        BatchSendConfirmationPhrase = string.Empty;
        StatusMessage = "Escolha os documentos liberados e o modo da nova mensagem.";
    }

    [RelayCommand]
    private async Task PrepareApprovedBatchAsync()
    {
        if (dispatchWorkflowService is null)
        {
            return;
        }

        if (!HasSingleMonthlyPeriodSelected())
        {
            StatusMessage = "Para preparar vários clientes, selecione um único mês e ano. Isso impede misturar competências.";
            return;
        }

        var groupIds = VisibleApprovedReviewGroups.Select(group => group.Id).ToArray();
        if (groupIds.Length == 0)
        {
            StatusMessage = "Não há conjuntos liberados para preparar. Documentos bloqueados permanecem excluídos.";
            return;
        }

        var existingItemIds = DispatchItems.Select(item => item.Id).ToHashSet();
        await ExecuteAsync(async () =>
        {
            var workspace = await dispatchWorkflowService.PrepareAsync(
                new PrepareDispatchRequest(
                    groupIds,
                    ProcessingSelectionMode.Batch,
                    SelectedDispatchOperationMode,
                    TestDestination,
                    SelectedFakeDeliveryScenario),
                CancellationToken.None);
            var preparedItemId = workspace.Items
                .Where(item => groupIds.Contains(item.GroupId) &&
                    item.State != DispatchItemState.Cancelled &&
                    !existingItemIds.Contains(item.Id))
                .OrderBy(item => item.ClientDisplayName, StringComparer.CurrentCultureIgnoreCase)
                .ThenBy(item => item.PeriodLabel, StringComparer.CurrentCultureIgnoreCase)
                .Select(item => (Guid?)item.Id)
                .FirstOrDefault();
            DispatchQueueSearchText = string.Empty;
            SelectedDispatchQueueFilter = DispatchQueueFilters.First(option =>
                option.Value == DispatchQueueFilterKind.Pending);
            ApplyDispatchWorkspace(workspace, preparedItemId);
            StatusMessage =
                $"{groupIds.Length} conjunto(s) foram preparados em mensagens separadas; documentos bloqueados ficaram de fora. " +
                $"Comece por {SelectedDispatchReference}.";
        });
    }

    [RelayCommand]
    private async Task ApproveSelectedDispatchAsync()
    {
        if (dispatchWorkflowService is null || SelectedDispatchItem is null || !CanApproveSelectedDispatch)
        {
            StatusMessage = "Selecione uma mensagem pronta para aprovação.";
            return;
        }

        var itemId = SelectedDispatchItem.Id;
        await ExecuteAsync(async () =>
        {
            ApplyDispatchWorkspace(await dispatchWorkflowService.ApproveAsync(itemId, CancellationToken.None));
            StatusMessage = "Mensagem aprovada. Alterações posteriores exigirão uma nova conferência.";
        });
    }

    [RelayCommand]
    private async Task ApproveSelectedDispatchBatchAsync()
    {
        if (dispatchWorkflowService is null || SelectedDispatchItem is null)
        {
            StatusMessage = "Selecione uma mensagem desta sequência.";
            return;
        }

        var batchId = SelectedDispatchItem.BatchId;
        if (!CanUseSelectedPeriodBatch(batchId))
        {
            return;
        }

        if (!CanApproveSelectedDispatchBatch)
        {
            StatusMessage = $"Não há mensagens prontas para aprovação nesta sequência de {WorkPeriodLabel}.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            ApplyDispatchWorkspace(await dispatchWorkflowService.ApproveBatchAsync(batchId, CancellationToken.None));
            StatusMessage = "As mensagens prontas deste mês foram aprovadas; as que precisam de correção ficaram de fora.";
        });
    }

    [RelayCommand]
    private async Task ExecuteSelectedDispatchAsync()
    {
        if (dispatchWorkflowService is null || SelectedDispatchItem is null || !CanExecuteSelectedDispatch)
        {
            StatusMessage = "Selecione uma mensagem aprovada ou uma tentativa com falha temporária confirmada.";
            return;
        }

        var itemId = SelectedDispatchItem.Id;
        await ExecuteAsync(async () =>
        {
            var workspace = await dispatchWorkflowService.ExecuteAsync(
                new ExecuteDispatchRequest(itemId, SendConfirmationPhrase),
                CancellationToken.None);
            ApplyDispatchWorkspace(workspace);
            StatusMessage = SelectedDispatchOutcome is { } outcome
                ? $"{outcome.OperationResult}. {outcome.DeliveryStatus}. {outcome.NextAction}"
                : "A operação foi registrada. Confira o resultado antes de qualquer nova tentativa.";
        });
    }

    [RelayCommand]
    private async Task ExecuteSelectedDispatchBatchAsync()
    {
        if (dispatchWorkflowService is null || SelectedDispatchItem is null)
        {
            StatusMessage = "Selecione uma mensagem aprovada desta sequência.";
            return;
        }

        var batchId = SelectedDispatchItem.BatchId;
        if (!CanUseSelectedPeriodBatch(batchId))
        {
            return;
        }

        if (!CanExecuteSelectedDispatchBatch)
        {
            StatusMessage = $"Não há mensagens aprovadas para concluir nesta sequência de {WorkPeriodLabel}.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            var workspace = await dispatchWorkflowService.ExecuteBatchAsync(
                batchId,
                BatchSendConfirmationPhrase,
                CancellationToken.None);
            ApplyDispatchWorkspace(workspace);
            StatusMessage = workspace.Batches.FirstOrDefault(batch => batch.Id == batchId)?.State switch
            {
                ProcessingBatchState.Completed => DescribeCompletedDispatchBatch(batchId),
                ProcessingBatchState.CompletedWithErrors => "Sequência concluída com falhas. Confira as mensagens marcadas antes de qualquer nova tentativa.",
                ProcessingBatchState.RecoveryRequired => "Sequência pausada porque um resultado ficou incerto. Use Conferir situação; não repita o envio.",
                ProcessingBatchState.Cancelled => "Sequência cancelada. Nenhuma nova operação será iniciada.",
                _ => "Sequência ainda em processamento. Aguarde a atualização antes de iniciar outra ação.",
            };
        });
    }

    [RelayCommand]
    private async Task ReconcileSelectedDispatchAsync()
    {
        if (dispatchWorkflowService is null || SelectedDispatchItem is null || !CanReconcileSelectedDispatch)
        {
            StatusMessage = "Selecione um item pendente ou ambíguo.";
            return;
        }

        var itemId = SelectedDispatchItem.Id;
        await ExecuteAsync(async () =>
        {
            ApplyDispatchWorkspace(await dispatchWorkflowService.ReconcileAsync(itemId, CancellationToken.None));
            StatusMessage = "Reconciliação concluída sem repetir a operação de envio.";
        });
    }

    [RelayCommand]
    private async Task ExportDispatchReportsAsync()
    {
        if (dispatchWorkflowService is null)
        {
            return;
        }

        if (!CanExportReport)
        {
            StatusMessage = IsReportClientScope && SelectedReportClient is null
                ? "Escolha o cliente do relatório."
                : SelectedReportScope.Value == DispatchReportScope.Range
                    ? "Escolha um intervalo válido, do mês inicial ao mês final."
                    : SelectedReportScope.Value == DispatchReportScope.Month
                        ? "Escolha um ano e um mês válidos para o relatório."
                        : "Complete o período do relatório.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            var filter = BuildReportFilter();
            var directory = Path.Combine(ReportOutputDirectory, GetReportStorageSegment(filter));
            var result = await dispatchWorkflowService.ExportReportsAsync(
                directory,
                filter,
                CancellationToken.None);
            LastReportPath = result.XlsxPath;
            LastReportPdfPath = result.PdfPath ?? "O PDF não pôde ser gerado nesta execução.";
            ApplyDispatchWorkspace(await dispatchWorkflowService.LoadAsync(CancellationToken.None));
            StatusMessage = $"Planilha, PDF e {result.CsvPaths.Count} arquivos auxiliares de “{ReportScopeSummary}” foram salvos em {directory}.";
        });
    }

    [ObservableProperty]
    public partial string SearchText { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool IncludeInactive { get; set; }

    [ObservableProperty]
    public partial ClientListRow? SelectedClient { get; set; }

    [ObservableProperty]
    public partial bool MaskClientTaxIds { get; set; } = true;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ClientArchiveActionLabel))]
    public partial bool IsClientArchiveConfirmationVisible { get; set; }

    public string ClientArchiveActionLabel => IsClientArchiveConfirmationVisible
        ? "Confirmar exclusão da lista"
        : "Excluir cadastro da lista";

    [ObservableProperty]
    public partial PersonTypeModel SelectedPersonType { get; set; } = PersonTypeModel.LegalEntity;

    [ObservableProperty]
    public partial string LegalName { get; set; } = string.Empty;

    public string ClientEditorTitle => string.IsNullOrWhiteSpace(LegalName) ? "Novo cliente" : LegalName;

    [ObservableProperty]
    public partial string PreferredName { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string InternalCode { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string PrimaryTaxId { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string Notes { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool IsClientActive { get; set; } = true;

    public string ClientStatusActionLabel => IsClientActive ? "Inativar agora" : "Reativar agora";

    [ObservableProperty]
    public partial string ReadinessMessage { get; set; } = "Novo cadastro — preencha os dados essenciais";

    [ObservableProperty]
    public partial string NewPartnerName { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string NewPartnerCpf { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string NewPartnerEmail { get; set; } = string.Empty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasPartnerInputError))]
    public partial string PartnerInputError { get; set; } = string.Empty;

    public bool HasPartnerInputError => !string.IsNullOrWhiteSpace(PartnerInputError);

    [ObservableProperty]
    public partial bool UsePartnerAsDeliveryContact { get; set; }

    [ObservableProperty]
    public partial ClientPartnerRoleModel SelectedPartnerRole { get; set; } =
        ClientPartnerRoleModel.ManagingPartner;

    [ObservableProperty]
    public partial ClientPartnerModel? SelectedPartner { get; set; }

    [ObservableProperty]
    public partial string NewIdentifierValue { get; set; } = string.Empty;

    [ObservableProperty]
    public partial ClientIdentifierTypeModel SelectedIdentifierType { get; set; } =
        ClientIdentifierTypeModel.LegalNameAlias;

    [ObservableProperty]
    public partial ClientIdentifierModel? SelectedIdentifier { get; set; }

    [ObservableProperty]
    public partial string NewEstablishmentCnpj { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string NewEstablishmentName { get; set; } = string.Empty;

    [ObservableProperty]
    public partial EstablishmentModel? SelectedEstablishment { get; set; }

    [ObservableProperty]
    public partial string NewRecipientName { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string NewRecipientEmail { get; set; } = string.Empty;

    [ObservableProperty]
    public partial DeliveryRoleModel SelectedDeliveryRole { get; set; } = DeliveryRoleModel.To;

    [ObservableProperty]
    public partial RecipientModel? SelectedRecipient { get; set; }

    [ObservableProperty]
    public partial string TemplateName { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string TemplateSubject { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string TemplateBody { get; set; } = string.Empty;

    [ObservableProperty]
    public partial TemplatePlaceholderTargetOption SelectedTemplatePlaceholderTarget { get; set; } =
        new("body", "Texto do e-mail");

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasSelectedTemplate))]
    [NotifyPropertyChangedFor(nameof(TemplateStatusActionLabel))]
    public partial MessageTemplateModel? SelectedTemplate { get; set; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TemplateArchiveActionLabel))]
    public partial bool IsTemplateArchiveConfirmationVisible { get; set; }

    public string TemplateArchiveActionLabel => IsTemplateArchiveConfirmationVisible
        ? "Confirmar exclusão"
        : "Excluir mensagem";

    [ObservableProperty]
    public partial string CatalogTransferJson { get; set; } = string.Empty;

    [ObservableProperty]
    public partial string CatalogRestoreSummary { get; set; } =
        "Escolha uma cópia de segurança para conferir o conteúdo antes de restaurar.";

    [ObservableProperty]
    public partial string CatalogBackupPassword { get; set; } = string.Empty;

    [ObservableProperty]
    public partial bool CanApplyCatalogRestore { get; set; }

    partial void OnLegalNameChanged(string value) => OnPropertyChanged(nameof(ClientEditorTitle));

    partial void OnTemplateNameChanged(string value) => OnPropertyChanged(nameof(TemplateNameLength));

    partial void OnTemplateSubjectChanged(string value) => OnPropertyChanged(nameof(TemplateSubjectLength));

    partial void OnTemplateBodyChanged(string value) => OnPropertyChanged(nameof(TemplateBodyLength));

    partial void OnSelectedClientChanged(ClientListRow? value)
    {
        IsClientArchiveConfirmationVisible = false;
        RefreshHistoryPresentation();
    }

    partial void OnSelectedTemplateChanged(MessageTemplateModel? value) =>
        IsTemplateArchiveConfirmationVisible = false;

    partial void OnIsClientActiveChanged(bool value)
    {
        OnPropertyChanged(nameof(ClientStatusActionLabel));
        OnPropertyChanged(nameof(ClientStatusLabel));
        OnPropertyChanged(nameof(ClientStatusBackground));
        OnPropertyChanged(nameof(ClientStatusForeground));
    }

    [RelayCommand]
    private async Task LoadClientsAsync()
    {
        if (catalogService is null)
        {
            return;
        }

        await ExecuteAsync(async () =>
        {
            var total = await RefreshClientListAsync(CancellationToken.None);
            StatusMessage = total == 0
                ? "Nenhum cliente corresponde à busca. Confira o nome, CPF/CNPJ ou marque “Mostrar inativos”."
                : $"{total} cliente(s) encontrado(s). Selecione um cadastro para abrir e editar.";
        });
    }

    [RelayCommand]
    private async Task OpenSelectedClientAsync()
    {
        if (catalogService is null || SelectedClient is null)
        {
            SetClientFeedback("Selecione um cliente na lista antes de abrir o cadastro.", true);
            return;
        }

        await ExecuteAsync(async () =>
        {
            await LoadClientEditorAsync(SelectedClient.Id, CancellationToken.None);
        });
    }

    [RelayCommand]
    private void NewClient()
    {
        CurrentSection = AppSection.Clients;
        FocusClientEditor();
        currentClientId = null;
        defaultSubjectTemplateId = null;
        defaultBodyTemplateId = null;
        currentVersion = 0;
        OnPropertyChanged(nameof(HasExistingClient));
        OnPropertyChanged(nameof(CanChangePersonType));
        OnPropertyChanged(nameof(ClientSaveActionLabel));
        SelectedClient = null;
        SelectedPersonType = PersonTypeModel.LegalEntity;
        LegalName = string.Empty;
        PreferredName = string.Empty;
        InternalCode = string.Empty;
        PrimaryTaxId = string.Empty;
        Notes = string.Empty;
        IsClientActive = true;
        ReadinessMessage = "Novo cadastro — preencha os dados essenciais";
        Identifiers.Clear();
        Establishments.Clear();
        Recipients.Clear();
        Partners.Clear();
        Templates.Clear();
        ResetTemplateEditor();
        AuditEvents.Clear();
        RefreshHistoryPresentation();
        ResetPartnerDraft();
        SetClientFeedback("Novo cadastro iniciado. Apenas razão social/nome completo e CNPJ/CPF são essenciais para salvar.");
        StatusMessage = "Comece pelos dados essenciais e pelo e-mail que receberá os documentos.";
    }

    private void FocusClientEditor()
    {
        IsClientEditorPanelExpanded = true;
        IsClientListPanelExpanded = false;
    }

    [RelayCommand]
    private async Task SaveClientAsync()
    {
        if (catalogService is null)
        {
            SetClientFeedback("O cadastro local não está disponível nesta instalação.", true);
            return;
        }

        if (!TryValidateClientDraft(out var validationMessage))
        {
            SetClientFeedback(validationMessage, true);
            StatusMessage = validationMessage;
            return;
        }

        if (!currentClientId.HasValue)
        {
            try
            {
                var existing = await FindClientByPrimaryTaxIdAsync(CancellationToken.None);
                if (existing is not null)
                {
                    var includeInactive = IncludeInactive || !existing.IsActive;
                    var editorData = await LoadClientEditorDataAsync(existing, CancellationToken.None);
                    var listResult = await SearchClientListAsync(includeInactive, CancellationToken.None);

                    IncludeInactive = includeInactive;
                    ApplyClientEditorData(existing, editorData);
                    await ApplyClientListResultAsync(listResult, CancellationToken.None);
                    SelectedClient = Clients.FirstOrDefault(item => item.Id == existing.Id);
                    var existingMessage = existing.IsActive
                        ? "Este CPF/CNPJ já estava cadastrado. O cadastro existente foi aberto para você atualizar, sem criar uma duplicidade."
                        : "Este CPF/CNPJ pertence a um cadastro inativo. Ele foi recuperado; use “Reativar agora” para voltar a utilizá-lo.";
                    SetClientFeedback(existingMessage);
                    StatusMessage = existingMessage;
                    return;
                }
            }
            catch (Exception exception)
            {
                TechnicalDetails = $"Recuperação cadastral: {exception.Message}";
                const string recoveryMessage =
                    "Não foi possível conferir se este CPF/CNPJ já está cadastrado. Os dados digitados foram mantidos; tente novamente.";
                SetClientFeedback(recoveryMessage, true);
                StatusMessage = recoveryMessage;
                return;
            }
        }

        var wasExistingClient = currentClientId.HasValue;
        IsSavingClient = true;
        SetClientFeedback("Salvando e conferindo o cadastro…");
        try
        {
            ClientDetails? saved = null;
            var persisted = await ExecuteAsync(async () =>
            {
                var request = new ClientMutationRequest(
                    currentVersion,
                    SelectedPersonType,
                    LegalName,
                    EmptyToNull(PreferredName),
                    EmptyToNull(InternalCode),
                    PrimaryTaxId,
                    IsClientActive,
                    defaultSubjectTemplateId,
                    defaultBodyTemplateId,
                    EmptyToNull(Notes),
                    Identifiers.ToArray(),
                    SelectedPersonType == PersonTypeModel.LegalEntity ? Establishments.ToArray() : [],
                    Recipients.ToArray(),
                    SelectedPersonType == PersonTypeModel.LegalEntity ? Partners.ToArray() : []);
                saved = await catalogService.SaveAsync(
                    currentClientId,
                    request,
                    CancellationToken.None);
                Apply(saved);
            }, message => SetClientFeedback(message, true));
            if (!persisted || saved is null)
            {
                return;
            }

            var success = saved.IsActive
                ? wasExistingClient
                    ? $"Cadastro de “{saved.PreferredName ?? saved.LegalNameOrFullName}” atualizado com sucesso."
                    : $"Cliente “{saved.PreferredName ?? saved.LegalNameOrFullName}” salvo com sucesso e disponível na lista."
                : $"Cadastro de “{saved.PreferredName ?? saved.LegalNameOrFullName}” atualizado e mantido como inativo.";
            SetClientFeedback(success);
            StatusMessage = success;
            try
            {
                if (documentReviewService is not null)
                {
                    ApplyWorkspace(await documentReviewService.RevalidateAsync(CancellationToken.None));
                }

                await RefreshDispatchWorkspaceAsync();
                await RefreshClientListAsync(CancellationToken.None);
                SelectedClient = Clients.FirstOrDefault(item => item.Id == saved.Id);
                SetClientFeedback(success);
                StatusMessage = success;
            }
            catch (Exception exception)
            {
                TechnicalDetails = exception.Message;
                var warning =
                    $"{success} A lista ou os documentos não puderam ser atualizados agora; use Buscar ou Atualizar análise para atualizar a tela.";
                SetClientFeedback(warning);
                StatusMessage = warning;
            }
        }
        finally
        {
            IsSavingClient = false;
        }
    }

    [RelayCommand]
    private async Task ToggleClientActiveAsync()
    {
        IsClientArchiveConfirmationVisible = false;
        if (!HasExistingClient)
        {
            SetClientFeedback("Salve o novo cliente antes de alterar o status.", true);
            StatusMessage = "Salve o novo cliente antes de alterar o status.";
            return;
        }

        if (catalogService is ILocalClientCatalogMaintenance maintenance && currentClientId is { } clientId)
        {
            var targetStatus = !IsClientActive;
            IsSavingClient = true;
            SetClientFeedback(targetStatus ? "Reativando o cadastro…" : "Inativando o cadastro…");
            try
            {
                ClientDetails? updated = null;
                var persisted = await ExecuteAsync(async () =>
                {
                    updated = await maintenance.SetClientActiveAsync(
                        clientId,
                        currentVersion,
                        targetStatus,
                        CancellationToken.None);
                }, message => SetClientFeedback(message, true));
                if (!persisted || updated is null)
                {
                    return;
                }

                if (!updated.IsActive)
                {
                    IncludeInactive = true;
                }

                try
                {
                    var editorData = await LoadClientEditorDataAsync(updated, CancellationToken.None);
                    ApplyClientEditorData(updated, editorData);
                    if (documentReviewService is not null)
                    {
                        ApplyWorkspace(await documentReviewService.RevalidateAsync(CancellationToken.None));
                    }

                    await RefreshDispatchWorkspaceAsync();
                    await RefreshClientListAsync(CancellationToken.None);
                    SelectedClient = Clients.FirstOrDefault(item => item.Id == updated.Id);
                    var success = updated.IsActive
                        ? editorData.Readiness?.IsEligible == true
                            ? $"Cliente “{updated.PreferredName ?? updated.LegalNameOrFullName}” reativado e disponível para novos documentos."
                            : $"Cliente “{updated.PreferredName ?? updated.LegalNameOrFullName}” reativado. Corrija as pendências indicadas antes de continuar."
                        : $"Cliente “{updated.PreferredName ?? updated.LegalNameOrFullName}” inativado. O histórico foi preservado.";
                    SetClientFeedback(success);
                    StatusMessage = success;
                }
                catch (Exception exception)
                {
                    // A manutenção local só retorna depois de confirmar status e revisão
                    // na mesma transação. Uma falha posterior é de atualização da tela:
                    // reflita imediatamente o estado já salvo e encerre o indicador de ação.
                    Apply(updated);
                    ReadinessMessage = updated.IsActive
                        ? "Cadastro reativado — atualize a análise para conferir as pendências"
                        : "Cadastro inativo — novos documentos não poderão seguir para envio";
                    TechnicalDetails = exception.Message;
                    var warning = updated.IsActive
                        ? $"Cliente “{updated.PreferredName ?? updated.LegalNameOrFullName}” foi reativado, mas parte da tela não pôde ser atualizada. Use Buscar ou Atualizar análise."
                        : $"Cliente “{updated.PreferredName ?? updated.LegalNameOrFullName}” foi inativado, mas parte da tela não pôde ser atualizada. Use Buscar ou Atualizar análise.";
                    SetClientFeedback(warning);
                    StatusMessage = warning;
                }

                return;
            }
            finally
            {
                IsSavingClient = false;
            }
        }

        var previousStatus = IsClientActive;
        var previousVersion = currentVersion;
        IsClientActive = !IsClientActive;
        if (!IsClientActive)
        {
            IncludeInactive = true;
        }

        ReadinessMessage = IsClientActive
            ? "Reativando o cliente…"
            : "Cliente inativo — novos documentos não poderão seguir para envio";
        await SaveClientAsync();
        if (currentVersion == previousVersion)
        {
            IsClientActive = previousStatus;
            ReadinessMessage = previousStatus
                ? "Cadastro aberto — confira os dados antes de atualizar"
                : "Cadastro inativo — reative para reconhecer e liberar documentos";
        }
        else
        {
            ReadinessMessage = IsClientActive
                ? "Cadastro ativo e pronto para nova conferência"
                : "Cadastro inativo — novos documentos não poderão seguir para envio";
        }
    }

    [RelayCommand]
    private async Task ArchiveClientAsync()
    {
        if (catalogService is not ILocalClientCatalogMaintenance maintenance ||
            currentClientId is not { } clientId)
        {
            SetClientFeedback(
                "A exclusão recuperável está disponível somente para cadastros salvos neste computador.",
                true);
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        if (IsClientActive)
        {
            SetClientFeedback(
                "Inative o cliente antes de excluí-lo da lista. Isso evita interromper documentos ou mensagens em andamento.",
                true);
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        if (!IsClientArchiveConfirmationVisible)
        {
            IsClientArchiveConfirmationVisible = true;
            SetClientFeedback(
                "Clique novamente para confirmar. O cadastro sairá da lista, mas a trilha de auditoria será preservada; clientes com documentos, mensagens ou modelos vinculados não podem ser excluídos.");
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        var displayName = PreferredName.Length > 0 ? PreferredName : LegalName;
        var archived = await ExecuteAsync(async () =>
        {
            await maintenance.ArchiveClientAsync(
                clientId,
                currentVersion,
                CancellationToken.None);
        }, message => SetClientFeedback(message, true));
        if (!archived)
        {
            IsClientArchiveConfirmationVisible = false;
            return;
        }

        clientTaxIds.Remove(clientId);
        NewClient();
        IncludeInactive = true;
        await RefreshClientListAsync(CancellationToken.None);
        var success = $"Cadastro de “{displayName}” excluído da lista local. A auditoria protegida foi mantida.";
        SetClientFeedback(success);
        StatusMessage = success;
    }

    [RelayCommand]
    private void AddPartner()
    {
        PartnerInputError = string.Empty;
        partnerInputErrorTargetsCpf = false;
        if (!IsLegalEntity)
        {
            PartnerInputError = "Sócios e representantes pertencem ao cadastro de empresas.";
            StatusMessage = PartnerInputError;
            return;
        }

        if (string.IsNullOrWhiteSpace(NewPartnerName))
        {
            PartnerInputError = "Informe o nome completo do sócio ou representante.";
            SetClientFeedback(PartnerInputError, true);
            return;
        }

        var cpfValidation = ClientPartnerInputValidator.ValidateOptionalCpf(NewPartnerCpf);
        if (!cpfValidation.IsValid)
        {
            PartnerInputError = cpfValidation.ErrorMessage ?? "Revise o CPF do sócio ou representante.";
            partnerInputErrorTargetsCpf = true;
            SetClientFeedback(PartnerInputError, true);
            StatusMessage = PartnerInputError;
            return;
        }

        var partnerEmail = EmptyToNull(NewPartnerEmail);
        if (partnerEmail is not null && !IsValidEmail(partnerEmail))
        {
            PartnerInputError = "O e-mail do sócio ou representante não é válido. Confira o endereço digitado.";
            SetClientFeedback(PartnerInputError, true);
            return;
        }
        partnerEmail = partnerEmail is null ? null : EmailAddress.Normalize(partnerEmail);

        if (UsePartnerAsDeliveryContact && partnerEmail is null)
        {
            PartnerInputError = "Informe o e-mail do representante para usá-lo também como contato de entrega.";
            SetClientFeedback(PartnerInputError, true);
            return;
        }

        var formattedCpf = cpfValidation.FormattedCpf;
        var linkedToDelivery = UsePartnerAsDeliveryContact;
        Partners.Add(new ClientPartnerModel(
            Guid.Empty,
            NewPartnerName.Trim(),
            formattedCpf,
            SelectedPartnerRole,
            true,
            partnerEmail));
        if (UsePartnerAsDeliveryContact && partnerEmail is not null &&
            Recipients.All(item => !string.Equals(item.Email, partnerEmail, StringComparison.OrdinalIgnoreCase)))
        {
            Recipients.Add(new RecipientModel(
                Guid.Empty,
                null,
                NewPartnerName.Trim(),
                partnerEmail,
                DeliveryRoleModel.To,
                null,
                Recipients.All(item => !item.IsPrimary),
                true,
                null,
                null));
        }

        ResetPartnerDraft();
        var message = linkedToDelivery
            ? "Representante adicionado e vinculado ao contato de entrega. Salve o cliente para confirmar."
            : "Sócio ou representante adicionado ao cadastro. Salve o cliente para confirmar.";
        SetClientFeedback(message);
        StatusMessage = message;
        FocusClientEditor();
    }

    [RelayCommand]
    private void RemovePartner()
    {
        if (SelectedPartner is null)
        {
            return;
        }

        Partners.Remove(SelectedPartner);
        SelectedPartner = null;
    }

    [RelayCommand]
    private void AddIdentifier()
    {
        if (string.IsNullOrWhiteSpace(NewIdentifierValue))
        {
            return;
        }

        Identifiers.Add(new ClientIdentifierModel(
            Guid.Empty,
            SelectedIdentifierType,
            NewIdentifierValue,
            ClientIdentifierSemanticRoleModel.PrimaryTaxpayer,
            Identifiers.Count,
            true,
            true));
        NewIdentifierValue = string.Empty;
    }

    [RelayCommand]
    private void RemoveIdentifier()
    {
        if (SelectedIdentifier is not null)
        {
            Identifiers.Remove(SelectedIdentifier);
            SelectedIdentifier = null;
        }
    }

    [RelayCommand]
    private void AddEstablishment()
    {
        if (SelectedPersonType != PersonTypeModel.LegalEntity ||
            string.IsNullOrWhiteSpace(NewEstablishmentCnpj) ||
            string.IsNullOrWhiteSpace(NewEstablishmentName))
        {
            StatusMessage = "Estabelecimento exige cliente PJ, CNPJ e nome.";
            return;
        }

        Establishments.Add(new EstablishmentModel(
            Guid.Empty,
            NewEstablishmentCnpj,
            NewEstablishmentName,
            NewEstablishmentName,
            null,
            Establishments.Count == 0,
            true));
        NewEstablishmentCnpj = string.Empty;
        NewEstablishmentName = string.Empty;
    }

    [RelayCommand]
    private void RemoveEstablishment()
    {
        if (SelectedEstablishment is not null)
        {
            Establishments.Remove(SelectedEstablishment);
            SelectedEstablishment = null;
        }
    }

    [RelayCommand]
    private void AddRecipient()
    {
        if (string.IsNullOrWhiteSpace(NewRecipientName) || string.IsNullOrWhiteSpace(NewRecipientEmail))
        {
            SetClientFeedback("Informe o nome e o e-mail do contato de entrega.", true);
            return;
        }

        if (!IsValidEmail(NewRecipientEmail))
        {
            SetClientFeedback("O e-mail do contato de entrega não é válido. Confira o endereço digitado.", true);
            return;
        }

        if (Recipients.Any(item => string.Equals(
                item.Email,
                NewRecipientEmail.Trim(),
                StringComparison.OrdinalIgnoreCase)))
        {
            SetClientFeedback("Este e-mail já está na lista de contatos de entrega.", true);
            return;
        }

        Recipients.Add(new RecipientModel(
            Guid.Empty,
            null,
            NewRecipientName.Trim(),
            EmailAddress.Normalize(NewRecipientEmail),
            SelectedDeliveryRole,
            null,
            Recipients.All(item => !item.IsPrimary),
            true,
            null,
            null));
        NewRecipientName = string.Empty;
        NewRecipientEmail = string.Empty;
        SetClientFeedback("Contato de entrega adicionado. Salve o cliente para confirmar.");
    }

    [RelayCommand]
    private void RemoveRecipient()
    {
        if (SelectedRecipient is not null)
        {
            Recipients.Remove(SelectedRecipient);
            SelectedRecipient = null;
        }
    }

    [RelayCommand]
    private void NewTemplate()
    {
        ResetTemplateEditor();
        StatusMessage = HasExistingClient
            ? "Nova mensagem personalizada. Os botões abaixo inserem as informações variáveis para você."
            : "Cadastre o cliente antes de criar uma mensagem personalizada.";
    }

    [RelayCommand]
    private void EditSelectedTemplate()
    {
        if (SelectedTemplate is null)
        {
            StatusMessage = "Selecione uma mensagem na lista para editar.";
            return;
        }

        editingTemplateId = SelectedTemplate.Id;
        editingTemplateVersion = SelectedTemplate.Version;
        editingTemplateDocumentTypeId = SelectedTemplate.DocumentTypeId;
        editingTemplateSignatureMode = SelectedTemplate.SignatureMode;
        editingTemplateIsDefault = SelectedTemplate.IsDefault;
        editingTemplateIsActive = SelectedTemplate.IsActive;
        TemplateName = SelectedTemplate.Name;
        TemplateSubject = SelectedTemplate.SubjectTemplate;
        TemplateBody = SelectedTemplate.BodyTemplate;
        OnPropertyChanged(nameof(IsEditingTemplate));
        OnPropertyChanged(nameof(TemplateEditorTitle));
        OnPropertyChanged(nameof(TemplateSaveActionLabel));
        StatusMessage = $"Editando “{SelectedTemplate.Name}”. As alterações só serão aplicadas ao clicar em Atualizar mensagem.";
    }

    [RelayCommand]
    private async Task SaveTemplateAsync()
    {
        if (catalogService is null)
        {
            return;
        }

        if (!currentClientId.HasValue)
        {
            SetClientFeedback("Cadastre ou abra um cliente antes de criar uma mensagem personalizada. Assim ela não será confundida com o modelo geral do escritório.", true);
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        if (!TryValidateTemplateDraft(out var validationMessage))
        {
            SetClientFeedback(validationMessage, true);
            StatusMessage = validationMessage;
            return;
        }

        await ExecuteAsync(async () =>
        {
            var template = await catalogService.SaveTemplateAsync(
                editingTemplateId,
                new MessageTemplateMutationRequest(
                    editingTemplateVersion,
                    currentClientId,
                    editingTemplateDocumentTypeId,
                    TemplateName,
                    TemplateSubject,
                    TemplateBody,
                    editingTemplateSignatureMode,
                    editingTemplateId.HasValue
                        ? editingTemplateIsDefault
                        : Templates.All(item => !item.IsDefault && item.ClientId == currentClientId),
                    editingTemplateIsActive),
                CancellationToken.None);
            var existingIndex = Templates.ToList().FindIndex(item => item.Id == template.Id);
            if (existingIndex < 0)
            {
                Templates.Add(template);
            }
            else
            {
                Templates[existingIndex] = template;
            }

            SelectedTemplate = template;
            var action = editingTemplateId.HasValue ? "atualizada" : "criada";
            ResetTemplateEditor(clearSelection: false);
            await RefreshDispatchWorkspaceAsync();
            SetClientFeedback($"Mensagem personalizada {action} com sucesso. Nenhum e-mail foi enviado.");
            StatusMessage = ClientFeedbackMessage;
        });
    }

    [RelayCommand]
    private async Task ToggleTemplateActiveAsync()
    {
        IsTemplateArchiveConfirmationVisible = false;
        if (catalogService is null || SelectedTemplate is null)
        {
            return;
        }

        await ExecuteAsync(async () =>
        {
            var existing = SelectedTemplate;
            var updated = catalogService is ILocalClientCatalogMaintenance maintenance
                ? await maintenance.SetTemplateActiveAsync(
                    existing.Id,
                    existing.Version,
                    !existing.IsActive,
                    CancellationToken.None)
                : await catalogService.SaveTemplateAsync(
                    existing.Id,
                    new MessageTemplateMutationRequest(
                        existing.Version,
                        existing.ClientId,
                        existing.DocumentTypeId,
                        existing.Name,
                        existing.SubjectTemplate,
                        existing.BodyTemplate,
                        existing.SignatureMode,
                        existing.IsDefault,
                        !existing.IsActive),
                    CancellationToken.None);
            Templates[Templates.IndexOf(existing)] = updated;
            SelectedTemplate = updated;
            if (editingTemplateId == updated.Id)
            {
                editingTemplateVersion = updated.Version;
                editingTemplateIsActive = updated.IsActive;
            }

            await RefreshDispatchWorkspaceAsync();
            OnPropertyChanged(nameof(TemplateStatusActionLabel));
            SetClientFeedback(updated.IsActive ? "Mensagem reativada." : "Mensagem inativada.");
            StatusMessage = ClientFeedbackMessage;
        });
    }

    [RelayCommand]
    private async Task ArchiveTemplateAsync()
    {
        if (catalogService is not ILocalClientCatalogMaintenance maintenance || SelectedTemplate is null)
        {
            SetClientFeedback("Selecione uma mensagem salva neste computador antes de excluí-la.", true);
            return;
        }

        if (SelectedTemplate.IsActive)
        {
            SetClientFeedback("Inative a mensagem antes de excluí-la da lista.", true);
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        if (!IsTemplateArchiveConfirmationVisible)
        {
            IsTemplateArchiveConfirmationVisible = true;
            SetClientFeedback(
                "Clique novamente para confirmar. A mensagem sairá da lista, mas a auditoria permanecerá protegida.");
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        var template = SelectedTemplate;
        var archived = await ExecuteAsync(async () =>
        {
            await maintenance.ArchiveTemplateAsync(
                template.Id,
                template.Version,
                CancellationToken.None);
        }, message => SetClientFeedback(message, true));
        if (!archived)
        {
            IsTemplateArchiveConfirmationVisible = false;
            return;
        }

        Templates.Remove(template);
        ResetTemplateEditor();
        await RefreshDispatchWorkspaceAsync();
        SetClientFeedback($"Mensagem “{template.Name}” excluída da lista local; o registro de auditoria foi preservado.");
        StatusMessage = ClientFeedbackMessage;
    }

    [RelayCommand]
    private void ApplyLegalEntityStandardTemplate() =>
        ApplyStandardTemplate(PersonTypeModel.LegalEntity);

    [RelayCommand]
    private void ApplyNaturalPersonStandardTemplate() =>
        ApplyStandardTemplate(PersonTypeModel.Individual);

    [RelayCommand]
    private void InsertClientNamePlaceholder() => InsertTemplatePlaceholder(
        MessageTemplatePlaceholderCatalog.ClientPreferredOrLegalNameKey);

    [RelayCommand]
    private void InsertContactNamePlaceholder() => InsertTemplatePlaceholder(
        MessageTemplatePlaceholderCatalog.ContactNameKey);

    [RelayCommand]
    private void InsertPeriodPlaceholder() => InsertTemplatePlaceholder(
        MessageTemplatePlaceholderCatalog.PeriodLabelKey);

    [RelayCommand]
    private void InsertDocumentListPlaceholder() => InsertTemplatePlaceholder(
        MessageTemplatePlaceholderCatalog.DocumentListKey);

    [RelayCommand]
    private void InsertDueDatesPlaceholder() => InsertTemplatePlaceholder(
        MessageTemplatePlaceholderCatalog.DueDateListKey);

    [RelayCommand]
    private void InsertOfficeNamePlaceholder() => InsertTemplatePlaceholder(
        MessageTemplatePlaceholderCatalog.OfficeNameKey);

    [RelayCommand]
    private async Task ExportCatalogAsync()
    {
        _ = await CreateCatalogBackupAsync(CancellationToken.None);
    }

    [RelayCommand]
    private Task ValidateCatalogImportAsync() => ImportCatalogAsync(dryRun: true);

    [RelayCommand]
    private Task ApplyCatalogImportAsync() => ImportCatalogAsync(dryRun: false);

    [RelayCommand]
    private async Task ConfirmCatalogRestoreAsync()
    {
        if (!CanApplyCatalogRestore)
        {
            StatusMessage = "Escolha e confira uma cópia de segurança antes de restaurar.";
            return;
        }

        await ImportCatalogAsync(dryRun: false);
    }

    public async Task<string?> CreateCatalogBackupAsync(CancellationToken cancellationToken)
    {
        if (catalogService is null)
        {
            StatusMessage = "A cópia de segurança não está disponível nesta instalação.";
            return null;
        }

        string? json = null;
        await ExecuteAsync(async () =>
        {
            var document = await catalogService.ExportAsync(cancellationToken);
            json = JsonSerializer.Serialize(document, TransferSerializerOptions);
            CatalogTransferJson = json;
            StatusMessage = "Cópia de segurança preparada. Escolha onde salvar o arquivo.";
        });
        return json;
    }

    public async Task<byte[]?> CreateProtectedCatalogBackupAsync(CancellationToken cancellationToken)
    {
        if (protectedBackupService is null)
        {
            StatusMessage = "A proteção da cópia não está disponível nesta instalação.";
            return null;
        }

        var json = await CreateCatalogBackupAsync(cancellationToken);
        if (string.IsNullOrWhiteSpace(json))
        {
            return null;
        }

        try
        {
            var content = Encoding.UTF8.GetBytes(json);
            try
            {
                var protectedContent = protectedBackupService.Protect(content, CatalogBackupPassword);
                CatalogTransferJson = string.Empty;
                StatusMessage = "Cópia protegida preparada. Guarde a senha separadamente.";
                return protectedContent;
            }
            finally
            {
                CryptographicOperations.ZeroMemory(content);
            }
        }
        catch (ProtectedBackupException exception)
        {
            StatusMessage = exception.Message;
            return null;
        }
    }

    public async Task PreviewProtectedCatalogRestoreAsync(
        byte[] protectedContent,
        CancellationToken cancellationToken)
    {
        if (protectedBackupService is null)
        {
            StatusMessage = "A proteção da cópia não está disponível nesta instalação.";
            return;
        }

        byte[]? content = null;
        try
        {
            content = protectedBackupService.Unprotect(protectedContent, CatalogBackupPassword);
            await PreviewCatalogRestoreAsync(Encoding.UTF8.GetString(content), cancellationToken);
        }
        catch (ProtectedBackupException exception)
        {
            CanApplyCatalogRestore = false;
            CatalogTransferJson = string.Empty;
            CatalogRestoreSummary = exception.Message;
            StatusMessage = exception.Message;
        }
        finally
        {
            if (content is not null)
            {
                CryptographicOperations.ZeroMemory(content);
            }
        }
    }

    public void ClearCatalogBackupPassword() => CatalogBackupPassword = string.Empty;

    public async Task PreviewCatalogRestoreAsync(string json, CancellationToken cancellationToken)
    {
        CatalogTransferJson = json;
        CanApplyCatalogRestore = false;
        CatalogRestoreSummary = "Conferindo a cópia de segurança…";
        if (catalogService is null)
        {
            StatusMessage = "A restauração não está disponível nesta instalação.";
            return;
        }

        await ExecuteAsync(async () =>
        {
            var document = JsonSerializer.Deserialize<ClientCatalogTransferDocument>(
                CatalogTransferJson,
                TransferSerializerOptions)
                ?? throw new InvalidOperationException("A cópia de segurança está vazia ou inválida.");
            var result = await catalogService.ImportAsync(
                new ClientCatalogImportRequest(true, true, document),
                cancellationToken);
            CanApplyCatalogRestore = true;
            CatalogRestoreSummary =
                $"Conferência concluída: {result.ClientsCreated} cliente(s) novo(s) e " +
                $"{result.ClientsUpdated} cadastro(s) a atualizar.";
            StatusMessage = "Cópia conferida. Revise o resumo e confirme somente se estiver correto.";
        });

        if (!CanApplyCatalogRestore && CatalogRestoreSummary == "Conferindo a cópia de segurança…")
        {
            CatalogRestoreSummary = "A cópia escolhida não pôde ser conferida. Selecione outro arquivo válido.";
        }
    }

    private async Task ImportCatalogAsync(bool dryRun)
    {
        if (catalogService is null)
        {
            return;
        }

        await ExecuteAsync(async () =>
        {
            var document = JsonSerializer.Deserialize<ClientCatalogTransferDocument>(
                CatalogTransferJson,
                TransferSerializerOptions)
                ?? throw new InvalidOperationException("O JSON de catálogo está vazio ou inválido.");
            var result = await catalogService.ImportAsync(
                new ClientCatalogImportRequest(dryRun, true, document),
                CancellationToken.None);
            StatusMessage = dryRun
                ? $"Conferência concluída: {result.ClientsCreated} novos e {result.ClientsUpdated} atualizados."
                : $"Restauração concluída: {result.ClientsCreated + result.ClientsUpdated} cliente(s).";
            if (!dryRun)
            {
                CanApplyCatalogRestore = false;
                CatalogTransferJson = string.Empty;
                CatalogRestoreSummary = "Restauração concluída. Gere uma nova cópia de segurança após alterações importantes.";
                await LoadClientsAsync();
            }
        });
    }

    private async Task<bool> ExecuteAsync(Func<Task> operation, Action<string>? onError = null)
    {
        try
        {
            await operation();
            return true;
        }
        catch (HttpRequestException exception)
        {
            StatusMessage = exception.StatusCode == System.Net.HttpStatusCode.Conflict
                ? "Conflito de edição: recarregue o cadastro antes de salvar novamente."
                : "Não foi possível falar com o servidor. Verifique a conexão e tente novamente.";
            TechnicalDetails = exception.Message;
        }
        catch (DomainValidationException exception)
        {
            StatusMessage = TranslateDomainValidationMessage(exception.Message);
            TechnicalDetails = exception.Message;
        }
        catch (DocumentReviewException exception)
        {
            StatusMessage = $"Não foi possível concluir a revisão: {exception.Message}";
            TechnicalDetails = $"Revisão: {exception.Code}";
        }
        catch (DispatchWorkflowException exception)
        {
            StatusMessage = $"Não foi possível concluir esta etapa: {exception.Message}";
            TechnicalDetails = $"Fluxo de mensagens: {exception.Code}";
        }
        catch (EmailAccountSessionException exception)
        {
            StatusMessage = $"A conta de e-mail precisa de atenção: {exception.Message}";
            TechnicalDetails = $"Conta de e-mail: {exception.Code}";
        }
        catch (IncidentException exception)
        {
            StatusMessage = $"Não foi possível atualizar a ocorrência: {exception.Message}";
            TechnicalDetails = $"Ocorrência: {exception.Code}";
        }
        catch (InvalidOperationException exception)
        {
            StatusMessage = exception.Message;
            TechnicalDetails = exception.Message;
        }
        catch (JsonException exception)
        {
            StatusMessage = "A cópia de segurança escolhida não pôde ser lida. Verifique se é um arquivo válido do aplicativo.";
            TechnicalDetails = exception.Message;
        }
        catch (ArgumentException exception)
        {
            StatusMessage = "Confira os dados informados e tente novamente.";
            TechnicalDetails = exception.Message;
        }
        catch (Exception exception)
        {
            StatusMessage = "Não foi possível concluir esta ação. Nenhuma alteração incompleta foi confirmada.";
            TechnicalDetails = exception.Message;
        }

        onError?.Invoke(StatusMessage);
        return false;
    }

    private void ApplyWorkspace(DocumentReviewWorkspace workspace)
    {
        var selectedDocumentId = SelectedReviewDocument?.Id;
        var selectedGroupId = SelectedReviewGroup?.Id;
        var selectedMergeGroupId = SelectedMergeGroupOption?.GroupId;
        Replace(
            ReviewDocuments,
            workspace.Documents.OrderByDescending(document => document.ImportedAtUtc));
        Replace(
            ReviewGroups,
            workspace.Groups.OrderByDescending(group => group.UpdatedAtUtc));
        Replace(
            ReviewAuditEvents,
            workspace.AuditEvents.OrderByDescending(audit => audit.TimestampUtc));
        EnsureOperationalYears(workspace.Documents
            .Select(document => GetEffectiveYear(document.Period))
            .Where(year => year.HasValue)
            .Select(year => year!.Value));
        SelectedReviewDocument = ReviewDocuments.FirstOrDefault(document =>
            document.Id == selectedDocumentId) ?? ReviewDocuments.FirstOrDefault();
        SelectedReviewGroup = SelectedReviewDocument?.GroupId is { } groupId
            ? ReviewGroups.FirstOrDefault(group => group.Id == groupId)
            : SelectedReviewDocument is null
                ? ReviewGroups.FirstOrDefault(group => group.Id == selectedGroupId)
                : null;
        SelectedMergeGroupOption = CompatibleMergeGroupOptions.FirstOrDefault(option =>
            option.GroupId == selectedMergeGroupId) ?? CompatibleMergeGroupOptions.FirstOrDefault();
        if (workspace.Documents.Count > 0)
        {
            IsDocumentImportPanelExpanded = false;
        }
        ReviewWorkspaceSummary =
            $"Documentos: {workspace.Documents.Count} • Prontos: {EligibleDocumentCount} • " +
            $"Para corrigir: {BlockedDocumentCount} • Conjuntos liberados: {ApprovedGroupCount}";
        OnPropertyChanged(nameof(EligibleDocumentCount));
        OnPropertyChanged(nameof(BlockedDocumentCount));
        OnPropertyChanged(nameof(HiddenReviewDocumentCount));
        OnPropertyChanged(nameof(HasHiddenReviewDocuments));
        OnPropertyChanged(nameof(HasVisibleReviewDocuments));
        OnPropertyChanged(nameof(HasAnyReviewDocuments));
        OnPropertyChanged(nameof(ShowReviewEmptyState));
        OnPropertyChanged(nameof(VisibleDocumentCount));
        OnPropertyChanged(nameof(ApprovedGroupCount));
        OnPropertyChanged(nameof(HasVisibleReviewGroups));
        OnPropertyChanged(nameof(ReadyReviewGroupCount));
        OnPropertyChanged(nameof(ReadyReviewDocumentCount));
        OnPropertyChanged(nameof(ShowBulkDocumentApproval));
        OnPropertyChanged(nameof(SelectedClientReadyGroupCount));
        OnPropertyChanged(nameof(SelectedClientReadyDocumentCount));
        OnPropertyChanged(nameof(SelectedClientReadyPeriodCount));
        OnPropertyChanged(nameof(ShowSelectedClientApproval));
        OnPropertyChanged(nameof(CanApproveSelectedClientGroups));
        OnPropertyChanged(nameof(SelectedClientApprovalLabel));
        OnPropertyChanged(nameof(SelectedClientApprovalHelp));
        OnPropertyChanged(nameof(AllReadyApprovalLabel));
        OnPropertyChanged(nameof(CanApproveSelectedReviewGroup));
        OnPropertyChanged(nameof(CanSplitSelectedDocument));
        OnPropertyChanged(nameof(CompatibleMergeGroupOptions));
        OnPropertyChanged(nameof(HasCompatibleMergeGroups));
        OnPropertyChanged(nameof(CanMergeSelectedGroups));
        OnPropertyChanged(nameof(CanRestoreSelectedDocumentPeriod));
        OnPropertyChanged(nameof(HasSelectedDocumentFindings));
        OnPropertyChanged(nameof(SelectedDocumentHasInactiveClientIssue));
        OnPropertyChanged(nameof(HasClientCorrectionAlternatives));
        OnPropertyChanged(nameof(SelectedDocumentGuidance));
        OnPropertyChanged(nameof(SelectedDocumentClientSummary));
        OnPropertyChanged(nameof(SelectedDocumentRecognitionMethodSummary));
        OnPropertyChanged(nameof(SelectedReviewGroupStatusSummary));
        OnPropertyChanged(nameof(SelectedDocumentGroupingSummary));
        OnPropertyChanged(nameof(SelectedReviewGroupDocuments));
        OnPropertyChanged(nameof(HasSelectedReviewGroupDocuments));
        OnPropertyChanged(nameof(SelectedGroupViewerHeader));
        OnPropertyChanged(nameof(HasApprovedGroupsForDispatch));
        OnPropertyChanged(nameof(CanPrepareSelectedDispatch));
        OnPropertyChanged(nameof(CanPrepareDispatchBatch));
        RefreshPeriodPresentation();
        RefreshReportPresentation();
        RefreshPilotMetricsPresentation();
    }

    private void ApplyDispatchWorkspace(DispatchWorkspace workspace, Guid? preferredItemId = null)
    {
        var selectedItemId = preferredItemId ?? SelectedDispatchItem?.Id;
        Replace(
            ProcessingBatches,
            workspace.Batches.OrderByDescending(batch => batch.UpdatedAtUtc));
        Replace(
            DispatchItems,
            workspace.Items.OrderByDescending(item => item.UpdatedAtUtc));
        Replace(
            DeliveryAttempts,
            workspace.Attempts.OrderByDescending(attempt => attempt.StartedAtUtc));
        Replace(
            DispatchAuditEvents,
            workspace.AuditEvents.OrderByDescending(item => item.TimestampUtc));
        var currentItems = CurrentDispatchItems.ToArray();
        var nextSelectedItem = currentItems.FirstOrDefault(item =>
                item.Id == selectedItemId && MatchesSelectedPeriod(item.PeriodLabel)) ??
            currentItems
                .Where(item => MatchesSelectedPeriod(item.PeriodLabel) &&
                    DispatchItemMatchesQueueFilter(item) &&
                    DispatchItemMatchesSearch(item))
                .OrderByDescending(item => item.UpdatedAtUtc)
                .FirstOrDefault() ??
            currentItems
                .Where(item => MatchesSelectedPeriod(item.PeriodLabel))
                .OrderByDescending(item => item.UpdatedAtUtc)
                .FirstOrDefault();
        if (nextSelectedItem is not null)
        {
            if (!DispatchItemMatchesSearch(nextSelectedItem))
            {
                DispatchQueueSearchText = string.Empty;
            }

            if (!DispatchItemMatchesQueueFilter(nextSelectedItem))
            {
                var targetFilter = IsCompletedDispatchState(nextSelectedItem.State)
                    ? DispatchQueueFilterKind.Completed
                    : DispatchQueueFilterKind.Pending;
                SelectedDispatchQueueFilter = DispatchQueueFilters.First(option => option.Value == targetFilter);
            }
        }

        SelectedDispatchItem = nextSelectedItem;
        DispatchWorkspaceSummary =
            $"Mensagens atuais: {currentItems.Length} • " +
            $"Operações registradas: {workspace.Attempts.Count} • " +
            $"Para conferir: {workspace.RecoveryRequiredCount}";
        OnPropertyChanged(nameof(HasDispatchMessages));
        OnPropertyChanged(nameof(HasSelectedDispatchItem));
        OnPropertyChanged(nameof(HasSelectedDispatchBlocks));
        OnPropertyChanged(nameof(CanApproveSelectedDispatch));
        OnPropertyChanged(nameof(CanApproveSelectedDispatchBatch));
        OnPropertyChanged(nameof(CanExecuteSelectedDispatch));
        OnPropertyChanged(nameof(CanExecuteSelectedDispatchBatch));
        OnPropertyChanged(nameof(ShowDispatchBatchConfirmation));
        OnPropertyChanged(nameof(DispatchBatchConfirmationGuidance));
        OnPropertyChanged(nameof(CanReconcileSelectedDispatch));
        OnPropertyChanged(nameof(HasDispatchOutcome));
        OnPropertyChanged(nameof(DispatchOutcomeTitle));
        OnPropertyChanged(nameof(DispatchOutcomeMessage));
        OnPropertyChanged(nameof(SelectedDispatchReference));
        OnPropertyChanged(nameof(SelectedDispatchContextSummary));
        OnPropertyChanged(nameof(ShowDispatchApprovalPanel));
        OnPropertyChanged(nameof(ShowDispatchCompletionPanel));
        RefreshDispatchQueuePresentation();
        RefreshDispatchSafetyPresentation();
        RefreshPeriodPresentation();
        RefreshReportPresentation();
        RefreshPilotMetricsPresentation();
    }

    private void ApplyIncidentWorkspace(IncidentWorkspace workspace)
    {
        var selectedIncidentId = SelectedIncident?.Id;
        Replace(Incidents, workspace.Incidents.OrderByDescending(item => item.UpdatedAtUtc).Take(500));
        Replace(IncidentAuditEvents, workspace.AuditEvents.OrderByDescending(item => item.TimestampUtc).Take(500));
        SelectedIncident = Incidents.FirstOrDefault(item => item.Id == selectedIncidentId) ??
            Incidents.FirstOrDefault();
        RefreshPilotMetricsPresentation();
    }

    private async Task RefreshDispatchWorkspaceAsync()
    {
        if (dispatchWorkflowService is not null)
        {
            ApplyDispatchWorkspace(await dispatchWorkflowService.LoadAsync(CancellationToken.None));
        }
    }

    private async Task RefreshEmailConnectionStatusAsync(CancellationToken cancellationToken)
    {
        if (emailAccountConnectionService is null)
        {
            return;
        }

        ApplyEmailConnectionStatus(await emailAccountConnectionService.GetStatusAsync(cancellationToken));
    }

    private void ApplyEmailConnectionStatus(EmailAccountConnectionStatus status)
    {
        ActiveEmailProviderKey = status.ProviderKey;
        IsExternalEmailProviderConfigured = status.ProviderKey != DispatchWorkflowOptions.FakeProviderKey &&
            status.IsConfigured;
        IsEmailAccountConnected = status.IsConnected;
        OnPropertyChanged(nameof(ShowEmailConnectionWarning));
        OnPropertyChanged(nameof(IsGmailActiveProvider));
        OnPropertyChanged(nameof(IsMicrosoftActiveProvider));
        OnPropertyChanged(nameof(IsLocalEmailSimulation));
        OnPropertyChanged(nameof(DispatchConfirmationGuidance));
        OnPropertyChanged(nameof(DispatchBatchConfirmationGuidance));
        OnPropertyChanged(nameof(CanShowGmailConnectionAction));
        OnPropertyChanged(nameof(CanShowMicrosoftConnectionAction));
        EmailProviderDisplayName = status.ProviderKey switch
        {
            DispatchWorkflowOptions.GmailProviderKey => "Google Gmail",
            DispatchWorkflowOptions.MicrosoftGraphProviderKey => "Microsoft 365",
            _ => "Modo seguro local",
        };
        var providerName = status.ProviderKey switch
        {
            DispatchWorkflowOptions.GmailProviderKey => "Google",
            DispatchWorkflowOptions.MicrosoftGraphProviderKey => "Microsoft 365",
            _ => "local",
        };
        CanConnectEmailAccount = status.ProviderKey != DispatchWorkflowOptions.FakeProviderKey &&
            status.IsConfigured &&
            !status.IsConnected;
        CanDisconnectEmailAccount = status.ProviderKey != DispatchWorkflowOptions.FakeProviderKey &&
            status.IsConnected;
        EmailProviderConnectionStatus = status.IsConnected
            ? status.ProviderKey == DispatchWorkflowOptions.FakeProviderKey
                ? "Simulação local ativa; nenhuma mensagem sai desta máquina."
                : $"Conta {providerName} conectada como {status.DisplayName}."
            : status.IsConfigured
                ? $"Conta {providerName} configurada, mas desconectada."
                : $"Integração {providerName} ainda não configurada nesta instalação.";
        EmailProviderConnectionHelp = status.ProviderKey == DispatchWorkflowOptions.FakeProviderKey
            ? "Use Teste seguro para conferir todo o fluxo sem acessar uma caixa postal. Gmail e Outlook só ficam disponíveis após o registro oficial do aplicativo no provedor escolhido."
            : $"A conexão abre a página segura do {providerName} no navegador. O aplicativo nunca solicita nem armazena sua senha.";
        TechnicalDetails = status.ProviderKey switch
        {
            DispatchWorkflowOptions.GmailProviderKey when status.IsConfigured =>
                "Conta Google configurada para conexão segura pelo aplicativo.",
            DispatchWorkflowOptions.MicrosoftGraphProviderKey when status.IsConfigured =>
                "Microsoft Graph configurado com autorização delegada.",
            _ => "Modo seguro local ativo; nenhuma integração externa está habilitada.",
        };
        OnPropertyChanged(nameof(ShowEmailSetupNotice));
        OnPropertyChanged(nameof(EmailSetupNoticeTitle));
        OnPropertyChanged(nameof(EmailSetupNoticeText));
        OnPropertyChanged(nameof(CanConnectGmail));
        OnPropertyChanged(nameof(CanConnectMicrosoft));
        RefreshDispatchSafetyPresentation();
    }

    private void RefreshDispatchSafetyPresentation()
    {
        OnPropertyChanged(nameof(DispatchSafetyTitle));
        OnPropertyChanged(nameof(DispatchSafetyMessage));
        OnPropertyChanged(nameof(DispatchSafetyBackground));
        OnPropertyChanged(nameof(DispatchSafetyForeground));
        OnPropertyChanged(nameof(DispatchOutcomeTitle));
        OnPropertyChanged(nameof(DispatchOutcomeMessage));
    }

    public async Task PrepareForShutdownAsync(CancellationToken cancellationToken)
    {
        if (KeepEmailSession || emailAccountConnectionService is null || !IsEmailAccountConnected)
        {
            return;
        }

        await emailAccountConnectionService.DisconnectAsync(cancellationToken);
        IsEmailAccountConnected = false;
        OnPropertyChanged(nameof(ShowEmailConnectionWarning));
    }

    private async Task<int> RefreshClientListAsync(CancellationToken cancellationToken)
    {
        if (catalogService is null)
        {
            return 0;
        }

        var result = await SearchClientListAsync(IncludeInactive, cancellationToken);
        await ApplyClientListResultAsync(result, cancellationToken);
        return result.Total;
    }

    private Task<ClientSearchResponse> SearchClientListAsync(
        bool includeInactive,
        CancellationToken cancellationToken) =>
        catalogService!.SearchAsync(
            SearchText,
            includeInactive ? null : true,
            null,
            cancellationToken);

    private async Task ApplyClientListResultAsync(
        ClientSearchResponse result,
        CancellationToken cancellationToken)
    {
        if (catalogService is ILocalClientCatalogMaintenance)
        {
            foreach (var item in result.Items.Where(item => !clientTaxIds.ContainsKey(item.Id)))
            {
                var details = await catalogService.GetAsync(item.Id, cancellationToken);
                if (details is not null)
                {
                    clientTaxIds[item.Id] = details.PrimaryTaxId;
                }
            }
        }

        Replace(
            Clients,
            result.Items.Select(item => new ClientListRow(
                item,
                clientTaxIds.GetValueOrDefault(item.Id),
                MaskClientTaxIds)));
        RefreshReportPresentation();
        RefreshPilotMetricsPresentation();
    }

    private async Task LoadReportClientsAsync(CancellationToken cancellationToken)
    {
        var selectedId = SelectedReportClient?.Id;
        var candidates = new Dictionary<Guid, ClientListItem>();

        foreach (var document in ReviewDocuments.Where(item => item.ClientId is not null))
        {
            var clientId = document.ClientId!.Value;
            candidates.TryAdd(
                clientId,
                CreateHistoricalReportClient(
                    clientId,
                    document.ClientDisplayName,
                    document.ValidatedAtUtc));
        }

        foreach (var item in DispatchItems)
        {
            candidates.TryAdd(
                item.ClientId,
                CreateHistoricalReportClient(item.ClientId, item.ClientDisplayName, item.UpdatedAtUtc));
        }

        ApplyReportClientCandidates(candidates, selectedId);

        if (catalogService is not null)
        {
            var catalog = await catalogService.SearchAsync(null, null, null, cancellationToken);
            foreach (var item in catalog.Items)
            {
                candidates[item.Id] = item;
            }

            ApplyReportClientCandidates(candidates, selectedId);
        }
    }

    private void ApplyReportClientCandidates(
        IReadOnlyDictionary<Guid, ClientListItem> candidates,
        Guid? selectedId)
    {
        Replace(
            ReportClients,
            candidates.Values
                .OrderByDescending(item => item.IsActive)
                .ThenBy(item => item.DisplayName, StringComparer.OrdinalIgnoreCase)
                .Select(item => new ClientListRow(
                    item,
                    clientTaxIds.GetValueOrDefault(item.Id),
                    MaskClientTaxIds)));
        SelectedReportClient = selectedId is null
            ? null
            : ReportClients.FirstOrDefault(item => item.Id == selectedId);
        RefreshReportPresentation();
    }

    private async Task LoadHistoryFilterOptionsAsync(CancellationToken cancellationToken)
    {
        var selectedClientId = SelectedHistoryClientFilter.ClientId ?? activeHistoryFilter.ClientId;
        var selectedDocumentId = SelectedHistoryDocumentFilter.DocumentId ?? activeHistoryFilter.DocumentId;

        await LoadReportClientsAsync(cancellationToken);
        var currentClientSelection = SelectedHistoryClientFilter;
        var clientOptions = new[] { new HistoryClientFilterOption(null, "Todos os clientes") }
            .Concat(ReportClients.Select(item => new HistoryClientFilterOption(item.Id, item.DisplayName)))
            .Select(option => option == currentClientSelection ? currentClientSelection : option)
            .ToArray();
        Replace(
            HistoryClientFilters,
            clientOptions);
        var clientSelection = HistoryClientFilters.FirstOrDefault(item =>
                item.ClientId == selectedClientId) ??
            HistoryClientFilters[0];
        SelectedHistoryClientFilter = new HistoryClientFilterOption(null, string.Empty);
        SelectedHistoryClientFilter = clientSelection;

        var reviewDocumentOptions = ReviewDocuments
            .GroupBy(item => item.Id)
            .Select(group => group.OrderByDescending(item => item.Revision).First())
            .Select(item => new HistoryDocumentFilterOption(
                item.Id,
                $"{DocumentPresentation.ToPortugueseLabel(item.DocumentType)} · {item.Period.DisplayLabel} · {item.ClientDisplayName} · {item.FileName}"));
        var messageSnapshotOptions = DispatchItems
            .Where(item => item.Message is not null)
            .SelectMany(item => item.Message!.Attachments.Select(attachment =>
                new HistoryDocumentFilterOption(
                    attachment.DocumentId,
                    $"{DocumentPresentation.ToPortugueseLabel(attachment.DocumentType)} · {item.PeriodLabel} · {item.ClientDisplayName} · {attachment.FileName}")));
        var documentOptions = reviewDocumentOptions
            .Concat(messageSnapshotOptions)
            .GroupBy(item => item.DocumentId)
            .Select(group => group.First())
            .OrderBy(item => item.Label, StringComparer.CurrentCultureIgnoreCase);
        var currentDocumentSelection = SelectedHistoryDocumentFilter;
        var selectableDocuments = new[] { new HistoryDocumentFilterOption(null, "Todos os documentos") }
            .Concat(documentOptions)
            .Select(option => option == currentDocumentSelection ? currentDocumentSelection : option)
            .ToArray();
        Replace(
            HistoryDocumentFilters,
            selectableDocuments);
        var documentSelection = HistoryDocumentFilters.FirstOrDefault(item =>
                item.DocumentId == selectedDocumentId) ??
            HistoryDocumentFilters[0];
        SelectedHistoryDocumentFilter = new HistoryDocumentFilterOption(null, string.Empty);
        SelectedHistoryDocumentFilter = documentSelection;

        var synchronizedFilter = activeHistoryFilter with
        {
            ClientId = SelectedHistoryClientFilter.ClientId,
            DocumentId = SelectedHistoryDocumentFilter.DocumentId,
        };
        if (synchronizedFilter != activeHistoryFilter)
        {
            activeHistoryFilter = synchronizedFilter;
            if (loadedHistoryCatalogClientId != synchronizedFilter.ClientId)
            {
                loadedHistoryCatalogClientId = null;
                HistoryCatalogAuditEvents.Clear();
            }
        }

        HistoryAppliedFilterSummary = BuildHistoryAppliedFilterSummary();
        OnPropertyChanged(nameof(HistoryAppliedFilterSummary));
        OnPropertyChanged(nameof(HistoryCalendarYears));
        OnPropertyChanged(nameof(HasActiveHistoryFilters));
    }

    private bool TryBuildHistoryTimeWindow(
        out HistoryTimeWindow timeWindow,
        out string error)
    {
        try
        {
            timeWindow = SelectedHistoryTimeScope.Value switch
            {
                HistoryTimeScope.All => HistoryTimeWindow.All,
                HistoryTimeScope.CalendarDay when HistoryFilterStartDate is { } day =>
                    HistoryTimeWindow.ForDay(DateOnly.FromDateTime(day.Date), TimeZoneInfo.Local),
                HistoryTimeScope.CalendarMonth when SelectedHistoryCalendarMonth.Month is { } month =>
                    HistoryTimeWindow.ForMonth(SelectedHistoryCalendarYear, month, TimeZoneInfo.Local),
                HistoryTimeScope.CalendarYear =>
                    HistoryTimeWindow.ForYear(SelectedHistoryCalendarYear, TimeZoneInfo.Local),
                HistoryTimeScope.CustomInterval when
                    HistoryFilterStartDate is { } startDate && HistoryFilterStartTime is { } startTime &&
                    HistoryFilterEndDate is { } endDate && HistoryFilterEndTime is { } endTime =>
                    HistoryTimeWindow.ForLocalInterval(
                        DateOnly.FromDateTime(startDate.Date),
                        TimeOnly.FromTimeSpan(startTime),
                        DateOnly.FromDateTime(endDate.Date),
                        TimeOnly.FromTimeSpan(endTime),
                        TimeZoneInfo.Local),
                _ => throw new ArgumentException("Preencha todos os campos de data e hora do filtro."),
            };
            error = string.Empty;
            return true;
        }
        catch (ArgumentException exception)
        {
            timeWindow = HistoryTimeWindow.All;
            error = exception.Message;
            return false;
        }
    }

    private string BuildHistoryAppliedFilterSummary()
    {
        var time = SelectedHistoryTimeScope.Value switch
        {
            HistoryTimeScope.All => "todos os dias",
            HistoryTimeScope.CalendarDay when HistoryFilterStartDate is { } day =>
                day.ToString("dd/MM/yyyy", System.Globalization.CultureInfo.InvariantCulture),
            HistoryTimeScope.CalendarMonth when SelectedHistoryCalendarMonth.Month is { } =>
                $"{SelectedHistoryCalendarMonth.Label} {SelectedHistoryCalendarYear}",
            HistoryTimeScope.CalendarYear => $"ano de {SelectedHistoryCalendarYear}",
            HistoryTimeScope.CustomInterval when
                HistoryFilterStartDate is { } startDate && HistoryFilterStartTime is { } startTime &&
                HistoryFilterEndDate is { } endDate && HistoryFilterEndTime is { } endTime =>
                $"{startDate:dd/MM/yyyy} {startTime:hh\\:mm} a {endDate:dd/MM/yyyy} {endTime:hh\\:mm}",
            _ => "período escolhido",
        };
        var client = SelectedHistoryClientFilter.ClientId is null
            ? "todos os clientes"
            : SelectedHistoryClientFilter.Label;
        var document = SelectedHistoryDocumentFilter.DocumentId is null
            ? "todos os documentos"
            : SelectedHistoryDocumentFilter.Label;
        var search = string.IsNullOrWhiteSpace(HistorySearchText)
            ? string.Empty
            : $" · busca “{HistorySearchText.Trim()}”";
        return $"Exibindo {time} · {client} · {document} · competência {WorkPeriodLabel}{search}.";
    }

    private HistoryProjectionIndex CreateHistoryProjectionIndex()
    {
        var clientNames = HistoryClientFilters
            .Where(item => item.ClientId.HasValue)
            .Select(item => KeyValuePair.Create(item.ClientId!.Value, item.Label));
        return HistoryProjectionIndex.Create(
            ReviewDocuments,
            ReviewGroups,
            DispatchItems,
            clientNames);
    }

    private static ClientListItem CreateHistoricalReportClient(
        Guid clientId,
        string? displayName,
        DateTimeOffset updatedAtUtc) => new(
        clientId,
        PersonTypeModel.LegalEntity,
        string.IsNullOrWhiteSpace(displayName) ? "Cliente histórico" : displayName,
        "Documento protegido",
        null,
        false,
        0,
        0,
        0,
        updatedAtUtc);

    private async Task<ClientDetails?> FindClientByPrimaryTaxIdAsync(CancellationToken cancellationToken)
    {
        if (catalogService is null)
        {
            return null;
        }

        var normalized = new string(PrimaryTaxId.Where(char.IsAsciiDigit).ToArray());
        var result = await catalogService.SearchAsync(
            normalized,
            null,
            SelectedPersonType,
            cancellationToken);
        foreach (var item in result.Items)
        {
            var details = await catalogService.GetAsync(item.Id, cancellationToken);
            if (details is not null &&
                string.Equals(details.PrimaryTaxId, normalized, StringComparison.Ordinal))
            {
                return details;
            }
        }

        return null;
    }

    private async Task LoadClientEditorAsync(Guid clientId, CancellationToken cancellationToken)
    {
        if (catalogService is null)
        {
            return;
        }

        var details = await catalogService.GetAsync(clientId, cancellationToken);
        if (details is null)
        {
            SetClientFeedback("O cliente selecionado não foi encontrado. Atualize a lista e tente novamente.", true);
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        var editorData = await LoadClientEditorDataAsync(details, cancellationToken);
        ApplyClientEditorData(details, editorData);
    }

    private async Task<(
        ClientReadinessResponse? Readiness,
        IReadOnlyList<MessageTemplateModel> Templates,
        IReadOnlyList<AuditEventModel> AuditEvents)> LoadClientEditorDataAsync(
            ClientDetails details,
            CancellationToken cancellationToken)
    {
        var readiness = await catalogService!.GetReadinessAsync(details.Id, cancellationToken);
        var templates = await catalogService.GetTemplatesAsync(details.Id, true, cancellationToken);
        var auditEvents = await catalogService.GetAuditAsync(details.Id, cancellationToken);
        return (readiness, templates, auditEvents);
    }

    private void ApplyClientEditorData(
        ClientDetails details,
        (ClientReadinessResponse? Readiness,
            IReadOnlyList<MessageTemplateModel> Templates,
            IReadOnlyList<AuditEventModel> AuditEvents) editorData)
    {
        Apply(details);
        var readiness = editorData.Readiness;
        var blockCodes = readiness?.BlockCodes ?? [];
        ReadinessMessage = !details.IsActive
            ? "Cadastro inativo — reative para reconhecer e liberar documentos"
            : readiness is null
                ? "Situação operacional ainda não conferida"
                : readiness.IsEligible
                    ? "Cadastro pronto para receber documentos"
                    : blockCodes.Contains(ClientBlockCodes.RecipientEmailInvalid, StringComparer.Ordinal)
                        ? "Corrija o e-mail de entrega incompleto antes de continuar"
                        : blockCodes.Contains(ClientBlockCodes.PartnerEmailInvalid, StringComparer.Ordinal)
                            ? "Corrija o e-mail do sócio ou representante antes de continuar"
                            : blockCodes.Contains(ClientBlockCodes.NoActiveToRecipient, StringComparer.Ordinal)
                                ? "Adicione um e-mail principal para a entrega dos documentos"
                                : "Cadastro precisa de atenção antes de receber documentos";
        TechnicalDetails = readiness is { IsEligible: false }
            ? $"Pendências do cadastro: {string.Join("; ", readiness.BlockCodes.Select(DescribeClientReadinessBlock))}"
            : TechnicalDetails;
        Replace(Templates, editorData.Templates);
        Replace(AuditEvents, editorData.AuditEvents);
        RefreshHistoryPresentation();
        ResetTemplateEditor();
        var message = details.IsActive
            ? $"Cadastro de “{details.PreferredName ?? details.LegalNameOrFullName}” aberto para edição."
            : $"Cadastro inativo de “{details.PreferredName ?? details.LegalNameOrFullName}” recuperado. Você pode reativá-lo sem recadastrar o CPF/CNPJ.";
        SetClientFeedback(message);
        StatusMessage = message;
    }

    private static string DescribeClientReadinessBlock(string blockCode) => blockCode switch
    {
        ClientBlockCodes.ClientInactive => "cadastro inativo",
        ClientBlockCodes.NoActiveToRecipient => "falta um e-mail principal de entrega",
        ClientBlockCodes.PartnerEmailInvalid => "e-mail de sócio ou representante incompleto",
        ClientBlockCodes.RecipientEmailInvalid => "e-mail de entrega incompleto",
        _ => "cadastro precisa de conferência",
    };

    private void Apply(ClientDetails details)
    {
        clientTaxIds[details.Id] = details.PrimaryTaxId;
        currentClientId = details.Id;
        defaultSubjectTemplateId = details.DefaultSubjectTemplateId;
        defaultBodyTemplateId = details.DefaultBodyTemplateId;
        currentVersion = details.Version;
        OnPropertyChanged(nameof(HasExistingClient));
        OnPropertyChanged(nameof(CanChangePersonType));
        OnPropertyChanged(nameof(ClientSaveActionLabel));
        SelectedPersonType = details.PersonType;
        LegalName = details.LegalNameOrFullName;
        PreferredName = details.PreferredName ?? string.Empty;
        InternalCode = details.InternalCode ?? string.Empty;
        PrimaryTaxId = details.PrimaryTaxId;
        Notes = details.Notes ?? string.Empty;
        IsClientActive = details.IsActive;
        Replace(Identifiers, details.Identifiers);
        Replace(Establishments, details.Establishments);
        Replace(Recipients, details.Recipients);
        Replace(Partners, details.Partners ?? []);
        ResetPartnerDraft();
    }

    private void ResetPartnerDraft()
    {
        NewPartnerName = string.Empty;
        NewPartnerCpf = string.Empty;
        NewPartnerEmail = string.Empty;
        PartnerInputError = string.Empty;
        partnerInputErrorTargetsCpf = false;
        UsePartnerAsDeliveryContact = false;
        SelectedPartnerRole = ClientPartnerRoleModel.Partner;
        SelectedPartner = null;
    }

    private static void Replace<T>(ObservableCollection<T> target, IEnumerable<T> values)
    {
        target.Clear();
        foreach (var value in values)
        {
            target.Add(value);
        }
    }

    private void InitializePeriodOptions(int year, int month)
    {
        EnsureOperationalYears(Enumerable.Range(year - 4, 7));
        SelectedOperationalYear = OperationalYears.First(item => item.Year == year);
        SelectedOperationalMonth = OperationalMonths.First(item => item.Month == month);
        SelectedReportYear ??= OperationalYears.First(item => item.Year == year);
        SelectedReportMonth ??= OperationalMonths.First(item => item.Month == month);
        SelectedReportStartYear ??= OperationalYears.First(item => item.Year == year);
        SelectedReportStartMonth ??= OperationalMonths.First(item => item.Month == month);
        SelectedReportEndYear ??= OperationalYears.First(item => item.Year == year);
        SelectedReportEndMonth ??= OperationalMonths.First(item => item.Month == month);
    }

    private void EnsureOperationalYears(IEnumerable<int> years)
    {
        ArgumentNullException.ThrowIfNull(years);

        var selectedYear = SelectedOperationalYear?.Year;
        var selectedReportYear = SelectedReportYear?.Year;
        var selectedReportStartYear = SelectedReportStartYear?.Year;
        var selectedReportEndYear = SelectedReportEndYear?.Year;
        var values = OperationalYears
            .Where(option => option.Year.HasValue)
            .Select(option => option.Year!.Value)
            .Concat(years)
            .Append(DateTimeOffset.Now.Year)
            .Where(year => year is >= 1900 and <= 9999)
            .Distinct()
            .OrderByDescending(year => year)
            .ToArray();

        OperationalYearOption[] desiredOptions =
        [
            OperationalYearOption.All,
            .. values.Select(year => new OperationalYearOption(
                year,
                year.ToString(System.Globalization.CultureInfo.InvariantCulture))),
        ];

        if (!OperationalYears.SequenceEqual(desiredOptions))
        {
            isRebuildingOperationalYears = true;
            try
            {
                OperationalYears.Clear();
                foreach (var option in desiredOptions)
                {
                    OperationalYears.Add(option);
                }

                SelectedOperationalYear = null;
                SelectedOperationalYear = OperationalYears.FirstOrDefault(option => option.Year == selectedYear) ??
                    OperationalYears[0];

                var reboundReportYear = selectedReportYear.HasValue
                    ? OperationalYears.FirstOrDefault(option => option.Year == selectedReportYear)
                    : null;
                var reboundReportStartYear = selectedReportStartYear.HasValue
                    ? OperationalYears.FirstOrDefault(option => option.Year == selectedReportStartYear)
                    : null;
                var reboundReportEndYear = selectedReportEndYear.HasValue
                    ? OperationalYears.FirstOrDefault(option => option.Year == selectedReportEndYear)
                    : null;
                SelectedReportYear = null;
                SelectedReportYear = reboundReportYear;
                SelectedReportStartYear = null;
                SelectedReportStartYear = reboundReportStartYear;
                SelectedReportEndYear = null;
                SelectedReportEndYear = reboundReportEndYear;
            }
            finally
            {
                isRebuildingOperationalYears = false;
            }
        }
        else if (SelectedOperationalYear is null)
        {
            isRebuildingOperationalYears = true;
            try
            {
                SelectedOperationalYear = OperationalYears[0];
            }
            finally
            {
                isRebuildingOperationalYears = false;
            }
        }

        OnPropertyChanged(nameof(CorrectionYearOptions));
        OnPropertyChanged(nameof(ReportYearOptions));
        OnPropertyChanged(nameof(HistoryCalendarYears));
        RefreshPeriodPresentation();
        RefreshReportPresentation();
    }

    private async Task LoadPreferencesAsync(CancellationToken cancellationToken)
    {
        if (preferencesLoaded || isLoadingPreferences)
        {
            return;
        }

        isLoadingPreferences = true;
        isApplyingPreferences = true;
        try
        {
            var preferences = workspacePreferencesStore is null
                ? null
                : await workspacePreferencesStore.LoadAsync(cancellationToken);
            if (preferences is null)
            {
                preferencesLoaded = true;
                return;
            }

            InputFolderPath = preferences.InputFolderPath ?? string.Empty;
            DocumentArchiveDirectory = string.IsNullOrWhiteSpace(preferences.DocumentArchiveDirectory)
                ? DocumentArchiveDirectory
                : preferences.DocumentArchiveDirectory;
            ReportOutputDirectory = string.IsNullOrWhiteSpace(preferences.ReportOutputDirectory)
                ? ReportOutputDirectory
                : preferences.ReportOutputDirectory;
            IncludeSubfolders = preferences.IncludeSubfolders;
            KeepEmailSession = preferences.KeepEmailSession;
            historyVisibility = preferences.HistoryVisibility ?? HistoryVisibilityState.Empty;
            SelectedRetentionOption = RetentionOptions.FirstOrDefault(item =>
                item.Months == preferences.RetentionReviewMonths) ?? RetentionOptions[0];
            SelectedReleaseChannel = ReleaseChannels.First(item => item.Value == AppUpdateChannel.Stable);
            if (preferences.SelectedYear is { } year)
            {
                EnsureOperationalYears([year]);
                SelectedOperationalYear = OperationalYears.First(item => item.Year == year);
            }
            else
            {
                SelectedOperationalYear = OperationalYears.First(item => item.Year is null);
            }

            SelectedOperationalMonth = OperationalMonths.FirstOrDefault(item =>
                item.Month == preferences.SelectedMonth) ?? OperationalMonths[0];
            preferencesLoaded = true;
        }
        finally
        {
            isApplyingPreferences = false;
            isLoadingPreferences = false;
        }

        RefreshPeriodPresentation();
        RefreshHistoryPresentation();
    }

    private void QueuePreferencesSave()
    {
        if (!isApplyingPreferences && preferencesLoaded && workspacePreferencesStore is not null)
        {
            _ = SavePreferencesSafelyAsync();
        }
    }

    private string GetOperationalMonthLabel(int? month) =>
        OperationalMonths.First(item => item.Month == month).Label;

    private async Task SavePreferencesSafelyAsync()
    {
        try
        {
            await SavePreferencesAsync(CancellationToken.None);
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException)
        {
            StatusMessage = "A preferência local não pôde ser salva agora; o trabalho continua sem perder documentos.";
        }
    }

    private async Task SavePreferencesAsync(CancellationToken cancellationToken)
    {
        if (workspacePreferencesStore is null)
        {
            return;
        }

        var preferences = new WorkspacePreferences(
            EmptyToNull(InputFolderPath),
            EmptyToNull(DocumentArchiveDirectory),
            EmptyToNull(ReportOutputDirectory),
            IncludeSubfolders,
            SelectedOperationalYear?.Year,
            SelectedOperationalMonth?.Month,
            KeepEmailSession,
            SelectedRetentionOption.Months,
            SelectedReleaseChannel.Value.ToString().ToLowerInvariant(),
            historyVisibility);
        Task currentSave;
        lock (preferencesSaveSync)
        {
            currentSave = SavePreferencesAfterAsync(
                pendingPreferencesSave,
                workspacePreferencesStore,
                preferences,
                cancellationToken);
            pendingPreferencesSave = currentSave;
        }

        await currentSave;
    }

    private static async Task SavePreferencesAfterAsync(
        Task previousSave,
        IWorkspacePreferencesStore store,
        WorkspacePreferences preferences,
        CancellationToken cancellationToken)
    {
        try
        {
            await previousSave;
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException or OperationCanceledException)
        {
            // A tentativa atual ainda precisa prosseguir, mesmo que uma preferência anterior não tenha sido salva.
        }

        await store.SaveAsync(preferences, cancellationToken);
    }

    private void ApplyUpdateSnapshot(AppUpdateSnapshot snapshot)
    {
        AppUpdateProgress = snapshot.Progress;
        AppUpdateSummary = snapshot.Message;
        CanDownloadApplicationUpdate = snapshot.CanDownload;
        CanApplyApplicationUpdate = snapshot.CanRestart;
        StatusMessage = snapshot.State switch
        {
            AppUpdateState.Available =>
                $"Versão {snapshot.LatestVersion} disponível no canal {SelectedReleaseChannel.Label}.",
            AppUpdateState.ReadyToRestart =>
                $"Versão {snapshot.LatestVersion} pronta para ser aplicada ao reiniciar.",
            AppUpdateState.UpToDate => "O aplicativo está atualizado.",
            AppUpdateState.Failed => "Não foi possível verificar atualizações agora.",
            _ => snapshot.Message,
        };
    }

    private async Task RefreshPilotReadinessAsync(CancellationToken cancellationToken)
    {
        if (pilotReadinessService is null || !IsPilotMode)
        {
            return;
        }

        ApplyPilotSnapshot(await pilotReadinessService.LoadAsync(
            BuildPilotMetrics(),
            cancellationToken));
    }

    private PilotOperationalMetrics BuildPilotMetrics()
    {
        var activeHighRiskIncidents = Incidents.Count(incident =>
            incident.Severity is IncidentSeverity.High or IncidentSeverity.Critical &&
            incident.Status != IncidentStatus.Closed);
        return new PilotOperationalMetrics(
            Clients.Select(client => (Guid?)client.Id)
                .Concat(ReviewDocuments.Select(document => document.ClientId))
                .Where(id => id.HasValue)
                .Distinct()
                .Count(),
            ReviewDocuments.Count,
            ReviewDocuments.Count(document => document.State is
                ReviewDocumentState.Ready or
                ReviewDocumentState.Grouped or
                ReviewDocumentState.Approved),
            ReviewDocuments.Count(document => document.State == ReviewDocumentState.Blocked),
            ReviewDocuments.Count(document => document.State == ReviewDocumentState.Duplicate),
            DeliveryAttempts.Count(attempt => attempt.Mode == DispatchOperationMode.Test),
            DeliveryAttempts.Count(attempt => attempt.Mode == DispatchOperationMode.Draft),
            DeliveryAttempts.Count(attempt => attempt.Mode == DispatchOperationMode.Send),
            DeliveryAttempts.Count(attempt => attempt.State is
                DeliveryAttemptState.FailedTransient or
                DeliveryAttemptState.FailedPermanent or
                DeliveryAttemptState.Ambiguous or
                DeliveryAttemptState.Pending),
            activeHighRiskIncidents);
    }

    private void ApplyPilotSnapshot(PilotReadinessSnapshot snapshot)
    {
        pilotReadinessSnapshot = snapshot;
        PilotNonProductionDataConfirmed = GetChecklistValue(
            snapshot,
            PilotChecklistKey.NonProductionDataConfirmed);
        PilotControlledAccountConfirmed = GetChecklistValue(
            snapshot,
            PilotChecklistKey.ControlledAccountConfirmed);
        PilotMacOsStationValidated = GetChecklistValue(snapshot, PilotChecklistKey.MacOsStationValidated);
        PilotWindowsStationValidated = GetChecklistValue(snapshot, PilotChecklistKey.WindowsStationValidated);
        PilotBackupRestoreValidated = GetChecklistValue(snapshot, PilotChecklistKey.BackupRestoreValidated);
        PilotRollbackValidated = GetChecklistValue(snapshot, PilotChecklistKey.RollbackValidated);
        IsPilotReady = snapshot.IsReady;
        PilotChecklistProgress = snapshot.RequiredChecklistCount == 0
            ? 0
            : snapshot.ConfirmedChecklistCount * 100 / snapshot.RequiredChecklistCount;
        PilotStatusTitle = snapshot.IsReady ? "Pronto para supervisão" : "Em preparação";
        PilotStatusSummary = snapshot.IsReady
            ? "Todos os controles locais foram confirmados. Isso não autoriza produção nem envio aos clientes."
            : snapshot.Blockers.Count > 0
                ? snapshot.Blockers[0]
                : "Conclua os controles operacionais pendentes.";
        ApplyPilotMetricsPresentation(snapshot.Metrics);
    }

    private void RefreshPilotMetricsPresentation()
    {
        if (!IsPilotMode)
        {
            return;
        }

        ApplyPilotMetricsPresentation(BuildPilotMetrics());
    }

    private void ApplyPilotMetricsPresentation(PilotOperationalMetrics metrics)
    {
        PilotMetricsSummary =
            $"{metrics.DocumentCount} documento(s) • " +
            $"{metrics.TestAttemptCount} teste(s) • " +
            $"{metrics.DraftAttemptCount} rascunho(s) • " +
            $"{metrics.FailedOrAmbiguousAttemptCount} pendência(s) técnica(s)";
        var stopReason = metrics.SendAttemptCount > 0
            ? "Uma tentativa de envio foi registrada. Interrompa o piloto e investigue."
            : metrics.OpenHighOrCriticalIncidentCount > 0
                ? "Há ocorrência alta ou crítica em aberto. O piloto está interrompido."
                : metrics.FailedOrAmbiguousAttemptCount > 0
                    ? "Há falha ou resultado incerto aguardando tratamento."
                    : metrics.ClientCount > pilotModeOptions.MaximumClients
                        ? $"O limite de {pilotModeOptions.MaximumClients} clientes do piloto foi excedido."
                        : null;
        if (stopReason is not null)
        {
            IsPilotReady = false;
            PilotStatusTitle = "Piloto interrompido";
            PilotStatusSummary = stopReason;
        }
        else if (pilotReadinessSnapshot is not null)
        {
            IsPilotReady = pilotReadinessSnapshot.IsReady;
            PilotStatusTitle = pilotReadinessSnapshot.IsReady
                ? "Pronto para supervisão"
                : "Em preparação";
            PilotStatusSummary = pilotReadinessSnapshot.IsReady
                ? "Todos os controles locais foram confirmados. Isso não autoriza produção nem envio aos clientes."
                : pilotReadinessSnapshot.Blockers.Count > 0
                    ? pilotReadinessSnapshot.Blockers[0]
                    : "Conclua os controles operacionais pendentes.";
        }
    }

    private bool IsPilotModeAllowed(DispatchOperationMode mode) => mode switch
    {
        DispatchOperationMode.Test => pilotModeOptions.AllowTest,
        DispatchOperationMode.Draft => pilotModeOptions.AllowDraft,
        DispatchOperationMode.Send => pilotModeOptions.AllowSend,
        _ => false,
    };

    private void RefreshAllowedDispatchModes()
    {
        dispatchOperationModes = pilotModeOptions.Enabled
            ? [.. Enum.GetValues<DispatchOperationMode>().Where(IsPilotModeAllowed)]
            : productionRolloutOptions.EnforceForExternalSend && !productionReadinessSnapshot.IsReadyForSend
                ? [DispatchOperationMode.Test, DispatchOperationMode.Draft]
                : Enum.GetValues<DispatchOperationMode>();
        OnPropertyChanged(nameof(DispatchOperationModes));
    }

    private static string GateLabel(bool completed, string label) =>
        completed ? $"✓ {label}" : $"○ {label}";

    private string DescribeGroupDocumentTypes(DocumentDispatchGroup group)
    {
        var types = group.DocumentIds
            .Select(documentId => ReviewDocuments.FirstOrDefault(document => document.Id == documentId)?.DocumentType)
            .Where(type => type.HasValue)
            .Select(type => DocumentPresentation.ToPortugueseLabel(type!.Value))
            .Distinct(StringComparer.CurrentCultureIgnoreCase)
            .OrderBy(type => type, StringComparer.CurrentCultureIgnoreCase)
            .ToArray();
        return types.Length == 0 ? "tipos não disponíveis" : string.Join(", ", types);
    }

    private bool DispatchItemMatchesQueueFilter(DispatchItem item) => SelectedDispatchQueueFilter.Value switch
    {
        DispatchQueueFilterKind.Pending => !IsCompletedDispatchState(item.State),
        DispatchQueueFilterKind.Completed => IsCompletedDispatchState(item.State),
        DispatchQueueFilterKind.All => true,
        _ => true,
    };

    private bool DispatchItemMatchesSearch(DispatchItem item)
    {
        var search = DispatchQueueSearchText.Trim();
        if (search.Length == 0)
        {
            return true;
        }

        var normalizedSearch = search.TrimStart('#').Trim();
        var itemIdentifier = item.Id.ToString("N");
        var groupIdentifier = item.GroupId.ToString("N");
        var messageReference = $"Mensagem #{itemIdentifier[..6].ToUpperInvariant()}";
        var groupReference = $"Conjunto #{groupIdentifier[..6].ToUpperInvariant()}";
        var searchable = string.Join(
            ' ',
            item.ClientDisplayName,
            item.PeriodLabel,
            itemIdentifier,
            groupIdentifier,
            messageReference,
            groupReference,
            item.Message?.Subject ?? string.Empty,
            string.Join(' ', item.Message?.Attachments.Select(attachment =>
                $"{attachment.FileName} {DocumentPresentation.ToPortugueseLabel(attachment.DocumentType)}") ?? []));
        return searchable.Contains(search, StringComparison.CurrentCultureIgnoreCase) ||
            searchable.Contains(normalizedSearch, StringComparison.CurrentCultureIgnoreCase);
    }

    private static bool IsCompletedDispatchState(DispatchItemState state) => state is
        DispatchItemState.DraftCreated or
        DispatchItemState.AcceptedByProvider or
        DispatchItemState.Reconciled or
        DispatchItemState.Completed;

    private HistoryTimelineRow BuildReviewHistoryRow(ReviewAuditEvent audit)
    {
        var document = audit.DocumentId is { } documentId
            ? ReviewDocuments.FirstOrDefault(item => item.Id == documentId)
            : null;
        var auditedClientId = Guid.TryParseExact(
            GetReviewAuditValue(audit, "client"),
            "N",
            out var parsedClientId)
                ? parsedClientId
                : (Guid?)null;
        var group = audit.GroupId is { } groupId
            ? ReviewGroups.FirstOrDefault(item => item.Id == groupId)
            : document?.GroupId is { } documentGroupId
                ? ReviewGroups.FirstOrDefault(item => item.Id == documentGroupId)
                : auditedClientId is { } clientId
                    ? ReviewGroups.FirstOrDefault(item => item.ClientId == clientId)
                    : null;
        var auditedClientName = GetReviewAuditValue(audit, "client-name");
        var clientName = document?.ClientDisplayName ??
            group?.ClientDisplayName ??
            auditedClientName ??
            (auditedClientId is { } catalogClientId
                ? Clients.FirstOrDefault(item => item.Id == catalogClientId)?.DisplayName
                : null) ??
            "Cliente não disponível";
        var auditedPeriods = GetReviewAuditValue(audit, "periods")?
            .Split('|', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(FormatAuditPeriod)
            .ToArray();
        var auditedPeriod = GetReviewAuditValue(audit, "period");
        var period = document?.Period.DisplayLabel ??
            (auditedPeriods?.Length > 0 ? string.Join(", ", auditedPeriods) : null) ??
            (auditedPeriod is null ? null : FormatAuditPeriod(auditedPeriod)) ??
            group?.PeriodLabel ??
            "Competência não disponível";
        var auditedDocumentCount = GetReviewAuditValue(audit, "documents");
        var auditedGroupCount = GetReviewAuditValue(audit, "groups");
        var auditedDocumentType = GetReviewAuditValue(audit, "type");
        var documentContext = document is not null
            ? DocumentPresentation.ToPortugueseLabel(document.DocumentType)
            : auditedDocumentCount is not null
                ? $"{auditedDocumentCount} documento(s) em {auditedGroupCount ?? "1"} conjunto(s)"
            : Enum.TryParse<RecognizedDocumentType>(auditedDocumentType, out var parsedDocumentType)
                ? DocumentPresentation.ToPortugueseLabel(parsedDocumentType)
            : group is not null
                ? $"{group.DocumentIds.Count} documento(s): {DescribeGroupDocumentTypes(group)}"
                : "Detalhes documentais não disponíveis nesta versão";
        var result = string.IsNullOrWhiteSpace(audit.Reason)
            ? "Ação registrada com auditoria preservada."
            : FriendlyTextConverter.ToFriendlyText(audit.Reason);
        var action = FriendlyTextConverter.ToFriendlyText(audit.Action);
        return new HistoryTimelineRow(
            audit.Id,
            audit.TimestampUtc,
            action,
            clientName,
            $"{period} • {documentContext}",
            result,
            action.Contains("revogada", StringComparison.CurrentCultureIgnoreCase) ||
            action.Contains("retirado", StringComparison.CurrentCultureIgnoreCase));
    }

    private static string? GetAuditValue(string? auditValue, string key) => auditValue?
        .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .Select(part => part.Split(':', 2, StringSplitOptions.TrimEntries))
        .FirstOrDefault(parts => parts.Length == 2 && string.Equals(parts[0], key, StringComparison.OrdinalIgnoreCase))?
        .ElementAtOrDefault(1);

    private static string? GetReviewAuditValue(ReviewAuditEvent audit, string key) =>
        GetAuditValue(audit.NewValue, key) ?? GetAuditValue(audit.PreviousValue, key);

    private static string FormatAuditPeriod(string value) =>
        TryGetYearMonth(value, out var year, out var month) && month is >= 1 and <= 12
            ? $"{month:00}/{year:0000}"
            : value;

    private HistoryTimelineRow BuildDispatchHistoryRow(DispatchAuditEvent audit)
    {
        var item = audit.DispatchItemId is { } itemId
            ? DispatchItems.FirstOrDefault(current => current.Id == itemId)
            : null;
        var group = audit.GroupId is { } groupId
            ? ReviewGroups.FirstOrDefault(current => current.Id == groupId)
            : item is not null
                ? ReviewGroups.FirstOrDefault(current => current.Id == item.GroupId)
                : null;
        var clientName = item?.ClientDisplayName ?? group?.ClientDisplayName ?? "Cliente não disponível";
        var period = item?.PeriodLabel ?? group?.PeriodLabel ?? "Competência não disponível";
        var attachmentSummary = item?.Message?.Attachments.Count > 0
            ? $"{item.Message.Attachments.Count} anexo(s): {string.Join(", ", item.Message.Attachments.Select(attachment => DocumentPresentation.ToPortugueseLabel(attachment.DocumentType)).Distinct(StringComparer.CurrentCultureIgnoreCase))}"
            : group is not null
                ? $"{group.DocumentIds.Count} documento(s): {DescribeGroupDocumentTypes(group)}"
                : "Detalhes documentais não disponíveis nesta versão";
        var result = FriendlyTextConverter.ToFriendlyText(audit.Outcome);
        return new HistoryTimelineRow(
            audit.Id,
            audit.TimestampUtc,
            FriendlyTextConverter.ToFriendlyText(audit.Action),
            clientName,
            $"{period} • {attachmentSummary}",
            result,
            DispatchAuditOutcomeNeedsAttention(audit.Outcome) || !string.IsNullOrWhiteSpace(audit.ErrorCode));
    }

    private static bool DispatchAuditOutcomeNeedsAttention(string outcome) =>
        outcome.Contains("failed", StringComparison.OrdinalIgnoreCase) ||
        outcome.Contains("failure", StringComparison.OrdinalIgnoreCase) ||
        outcome.Contains("error", StringComparison.OrdinalIgnoreCase) ||
        outcome.Contains("ambiguous", StringComparison.OrdinalIgnoreCase);

    private static bool GetChecklistValue(
        PilotReadinessSnapshot snapshot,
        PilotChecklistKey key) => snapshot.Checklist.Items.Single(item => item.Key == key).IsConfirmed;

    private void RefreshPeriodPresentation()
    {
        var visibleDocuments = VisibleReviewDocuments.ToArray();
        var visibleItems = PeriodDispatchItems.ToArray();
        var visibleItemIds = visibleItems.Select(item => item.Id).ToHashSet();
        ReviewWorkspaceSummary =
            $"Documentos: {visibleDocuments.Length} • Prontos: {EligibleDocumentCount} • " +
            $"Para corrigir: {BlockedDocumentCount} • Conjuntos liberados: {ApprovedGroupCount}";
        DispatchWorkspaceSummary =
            $"Mensagens atuais: {visibleItems.Length} • " +
            $"Operações registradas: {DeliveryAttempts.Count(attempt => visibleItemIds.Contains(attempt.DispatchItemId))} • " +
            $"Período: {WorkPeriodLabel}";
        OnPropertyChanged(nameof(WorkPeriodLabel));
        OnPropertyChanged(nameof(VisibleReviewDocuments));
        OnPropertyChanged(nameof(VisibleReviewGroups));
        OnPropertyChanged(nameof(VisibleApprovedReviewGroups));
        OnPropertyChanged(nameof(VisibleDispatchItems));
        OnPropertyChanged(nameof(VisibleReviewAuditEvents));
        OnPropertyChanged(nameof(VisibleDispatchAuditEvents));
        OnPropertyChanged(nameof(EligibleDocumentCount));
        OnPropertyChanged(nameof(BlockedDocumentCount));
        OnPropertyChanged(nameof(ApprovedGroupCount));
        OnPropertyChanged(nameof(HasVisibleReviewGroups));
        OnPropertyChanged(nameof(ReadyReviewGroupCount));
        OnPropertyChanged(nameof(ReadyReviewDocumentCount));
        OnPropertyChanged(nameof(ShowBulkDocumentApproval));
        OnPropertyChanged(nameof(SelectedClientReadyGroupCount));
        OnPropertyChanged(nameof(SelectedClientReadyDocumentCount));
        OnPropertyChanged(nameof(SelectedClientReadyPeriodCount));
        OnPropertyChanged(nameof(ShowSelectedClientApproval));
        OnPropertyChanged(nameof(CanApproveSelectedClientGroups));
        OnPropertyChanged(nameof(SelectedClientApprovalLabel));
        OnPropertyChanged(nameof(SelectedClientApprovalHelp));
        OnPropertyChanged(nameof(AllReadyApprovalLabel));
        OnPropertyChanged(nameof(HiddenReviewDocumentCount));
        OnPropertyChanged(nameof(HasHiddenReviewDocuments));
        OnPropertyChanged(nameof(HasVisibleReviewDocuments));
        OnPropertyChanged(nameof(HasAnyReviewDocuments));
        OnPropertyChanged(nameof(ShowReviewEmptyState));
        OnPropertyChanged(nameof(VisibleDocumentCount));
        OnPropertyChanged(nameof(CanContinueToDispatch));
        OnPropertyChanged(nameof(CanOpenReports));
        OnPropertyChanged(nameof(HasApprovedGroupsForDispatch));
        OnPropertyChanged(nameof(HasDispatchMessages));
        OnPropertyChanged(nameof(VisibleDispatchItemCount));
        OnPropertyChanged(nameof(DispatchQueueSummary));
        OnPropertyChanged(nameof(ShowDispatchQueueEmptyState));
        OnPropertyChanged(nameof(DispatchQueueEmptyMessage));
        OnPropertyChanged(nameof(CanPrepareSelectedDispatch));
        OnPropertyChanged(nameof(CanPrepareDispatchBatch));
        OnPropertyChanged(nameof(CanExportSelectedPeriodReport));
        OnPropertyChanged(nameof(CanApproveSelectedDispatchBatch));
        OnPropertyChanged(nameof(CanExecuteSelectedDispatchBatch));
        OnPropertyChanged(nameof(SelectedDocumentGroupingSummary));
        OnPropertyChanged(nameof(HistoryCleanupSelectionSummary));
        HistoryAppliedFilterSummary = BuildHistoryAppliedFilterSummary();
        OnPropertyChanged(nameof(HistoryAppliedFilterSummary));
        RefreshFlowStepPresentation();
        RefreshHistoryPresentation();

        if (SelectedReviewDocument is not null && !MatchesSelectedPeriod(SelectedReviewDocument.Period))
        {
            SelectedReviewDocument = VisibleReviewDocuments.FirstOrDefault();
        }
        if (SelectedReviewGroup is not null && !MatchesSelectedPeriod(SelectedReviewGroup))
        {
            SelectedReviewGroup = VisibleReviewGroups.FirstOrDefault();
        }
        if (SelectedDispatchItem is not null && !MatchesSelectedPeriod(SelectedDispatchItem.PeriodLabel))
        {
            SelectedDispatchItem = VisibleDispatchItems.FirstOrDefault();
        }
    }

    private void RefreshReportPresentation()
    {
        OnPropertyChanged(nameof(IsReportMonthScope));
        OnPropertyChanged(nameof(IsReportYearScope));
        OnPropertyChanged(nameof(IsReportRangeScope));
        OnPropertyChanged(nameof(IsReportClientScope));
        OnPropertyChanged(nameof(ReportScopeHelp));
        OnPropertyChanged(nameof(ReportScopeSummary));
        OnPropertyChanged(nameof(CanExportReport));
        OnPropertyChanged(nameof(CanExportSelectedPeriodReport));
        OnPropertyChanged(nameof(ReportCommunicationRows));
        OnPropertyChanged(nameof(HasReportCommunicationRows));
    }

    private void RefreshHistoryPresentation()
    {
        OnPropertyChanged(nameof(VisibleReviewAuditEvents));
        OnPropertyChanged(nameof(VisibleDispatchAuditEvents));
        OnPropertyChanged(nameof(VisibleCatalogAuditEvents));
        OnPropertyChanged(nameof(VisibleReviewHistoryRows));
        OnPropertyChanged(nameof(VisibleDispatchHistoryRows));
        OnPropertyChanged(nameof(VisibleReviewHistoryCount));
        OnPropertyChanged(nameof(VisibleDispatchHistoryCount));
        OnPropertyChanged(nameof(ReviewHistorySectionHeader));
        OnPropertyChanged(nameof(DispatchHistorySectionHeader));
        OnPropertyChanged(nameof(HasVisibleCatalogAuditEvents));
        OnPropertyChanged(nameof(CatalogHistoryHeader));
        OnPropertyChanged(nameof(CatalogHistoryEmptyMessage));
        OnPropertyChanged(nameof(TruncatedHistoryEventCount));
        OnPropertyChanged(nameof(HasTruncatedHistoryEvents));
        OnPropertyChanged(nameof(HistoryTruncationMessage));
        OnPropertyChanged(nameof(HiddenHistoryEventCount));
        OnPropertyChanged(nameof(HasHiddenHistoryEvents));
        OnPropertyChanged(nameof(HasActiveHistoryFilters));
    }

    private void RefreshDispatchQueuePresentation()
    {
        var visibleItems = VisibleDispatchItems.ToArray();
        if (SelectedDispatchItem is not null && visibleItems.All(item => item.Id != SelectedDispatchItem.Id))
        {
            SelectedDispatchItem = visibleItems.FirstOrDefault();
        }

        OnPropertyChanged(nameof(VisibleDispatchItems));
        OnPropertyChanged(nameof(VisibleDispatchItemCount));
        OnPropertyChanged(nameof(DispatchQueueSummary));
        OnPropertyChanged(nameof(ShowDispatchQueueEmptyState));
        OnPropertyChanged(nameof(DispatchQueueEmptyMessage));
        OnPropertyChanged(nameof(HasDispatchMessages));
        OnPropertyChanged(nameof(CanOpenReports));
    }

    partial void OnSelectedDispatchItemChanged(DispatchItem? value)
    {
        SendConfirmationPhrase = string.Empty;
        BatchSendConfirmationPhrase = string.Empty;
        if (value is not null && SelectedDispatchOperationMode != value.Mode)
        {
            SelectedDispatchOperationMode = value.Mode;
        }

        RefreshFlowStepPresentation();
        OnPropertyChanged(nameof(HasSelectedDispatchItem));
        OnPropertyChanged(nameof(HasSelectedDispatchBlocks));
        OnPropertyChanged(nameof(SelectedDispatchReference));
        OnPropertyChanged(nameof(SelectedDispatchContextSummary));
        OnPropertyChanged(nameof(CanSelectDispatchOperationMode));
        OnPropertyChanged(nameof(IsSendMode));
        OnPropertyChanged(nameof(DispatchActionLabel));
        OnPropertyChanged(nameof(DispatchModeHelp));
        OnPropertyChanged(nameof(DispatchConfirmationGuidance));
        OnPropertyChanged(nameof(ShowDispatchBatchConfirmation));
        OnPropertyChanged(nameof(DispatchBatchConfirmationGuidance));
        OnPropertyChanged(nameof(CanApproveSelectedDispatch));
        OnPropertyChanged(nameof(CanApproveSelectedDispatchBatch));
        OnPropertyChanged(nameof(CanExecuteSelectedDispatch));
        OnPropertyChanged(nameof(CanExecuteSelectedDispatchBatch));
        OnPropertyChanged(nameof(CanReconcileSelectedDispatch));
        OnPropertyChanged(nameof(HasDispatchOutcome));
        OnPropertyChanged(nameof(DispatchOutcomeTitle));
        OnPropertyChanged(nameof(DispatchOutcomeMessage));
        OnPropertyChanged(nameof(ShowDispatchApprovalPanel));
        OnPropertyChanged(nameof(ShowDispatchCompletionPanel));
        RefreshDispatchQueuePresentation();
        RefreshDispatchSafetyPresentation();
    }

    private void RefreshFlowStepPresentation()
    {
        OnPropertyChanged(nameof(IsDocumentImportStepCurrent));
        OnPropertyChanged(nameof(IsDocumentReviewStepCurrent));
        OnPropertyChanged(nameof(IsDocumentApprovalStepCurrent));
        OnPropertyChanged(nameof(IsDispatchPrepareStepCurrent));
        OnPropertyChanged(nameof(IsDispatchReviewStepCurrent));
        OnPropertyChanged(nameof(IsDispatchApprovalStepCurrent));
        OnPropertyChanged(nameof(IsDispatchCompletionStepCurrent));
        OnPropertyChanged(nameof(ShowDispatchApprovalPanel));
        OnPropertyChanged(nameof(ShowDispatchCompletionPanel));
    }

    private void SelectOperationalPeriod(int year, int month)
    {
        EnsureOperationalYears([year]);
        SelectedOperationalYear = OperationalYears.First(item => item.Year == year);
        SelectedOperationalMonth = OperationalMonths.First(item => item.Month == month);
    }

    private bool HasSingleMonthlyPeriodSelected() =>
        SelectedOperationalYear?.Year.HasValue == true &&
        SelectedOperationalMonth?.Month is > 0;

    private bool CanUseSelectedPeriodBatch(Guid batchId)
    {
        if (!HasSingleMonthlyPeriodSelected())
        {
            StatusMessage = "Para operar várias mensagens, selecione um único mês e ano. Isso impede misturar competências.";
            return false;
        }

        var batchItems = DispatchItems.Where(item => item.BatchId == batchId).ToArray();
        if (batchItems.Length == 0)
        {
            StatusMessage = "A sequência selecionada não contém mensagens disponíveis.";
            return false;
        }

        if (batchItems.Any(item => !MatchesSelectedPeriod(item.PeriodLabel)))
        {
            StatusMessage =
                "Esta sequência antiga reúne mais de uma competência. Processe as mensagens individualmente ou prepare uma nova sequência no mês selecionado.";
            return false;
        }

        return true;
    }

    private bool IsSelectedPeriodBatchEligible(
        Guid batchId,
        Func<DispatchItem, bool> isEligible)
    {
        if (!HasSingleMonthlyPeriodSelected())
        {
            return false;
        }

        var batch = ProcessingBatches.FirstOrDefault(current => current.Id == batchId);
        if (batch?.SelectionMode != ProcessingSelectionMode.Batch)
        {
            return false;
        }

        var batchItems = DispatchItems
            .Where(item => batch.DispatchItemIds.Contains(item.Id))
            .ToArray();
        return batchItems.Length > 0 &&
            batchItems.All(item => MatchesSelectedPeriod(item.PeriodLabel)) &&
            batchItems.Any(isEligible);
    }

    private static string BuildUnsupportedFolderFilesSummary(string[] filePaths)
    {
        var formats = filePaths
            .GroupBy(
                path => string.IsNullOrWhiteSpace(Path.GetExtension(path))
                    ? "SEM EXTENSÃO"
                    : Path.GetExtension(path).TrimStart('.').ToUpperInvariant(),
                StringComparer.OrdinalIgnoreCase)
            .OrderBy(group => group.Key, StringComparer.OrdinalIgnoreCase)
            .Select(group => $"{group.Key}: {group.Count()}");
        var result = filePaths.Length == 1
            ? "1 arquivo não compatível foi recusado e preservado na pasta"
            : $"{filePaths.Length} arquivos não compatíveis foram recusados e preservados na pasta";
        return $"{result} ({string.Join("; ", formats)}).";
    }

    private bool MatchesSelectedPeriod(DocumentPeriod period)
    {
        var year = GetEffectiveYear(period);
        var month = GetEffectiveMonth(period);
        if (SelectedOperationalYear?.Year is { } selectedYear && year != selectedYear)
        {
            return false;
        }

        return SelectedOperationalMonth?.Month switch
        {
            null => true,
            0 => month is null,
            var selectedMonth => month == selectedMonth,
        };
    }

    private bool MatchesSelectedPeriod(DocumentDispatchGroup group) =>
        group.DocumentIds.Any(documentId => ReviewDocuments.Any(document =>
            document.Id == documentId && MatchesSelectedPeriod(document.Period))) ||
        MatchesSelectedPeriod(group.PeriodKey);

    private bool MatchesSelectedPeriod(string label)
    {
        var hasPeriod = TryGetYearMonth(label, out var year, out var month);
        if (SelectedOperationalYear?.Year is { } selectedYear && (!hasPeriod || year != selectedYear))
        {
            return false;
        }

        return SelectedOperationalMonth?.Month switch
        {
            null => true,
            0 => !hasPeriod || month == 0,
            var selectedMonth => hasPeriod && month == selectedMonth,
        };
    }

    private bool ReportItemMatchesScope(DispatchItem item)
    {
        if (SelectedReportClientFilter.Value == ReportClientFilterKind.SelectedClient &&
            (SelectedReportClient is null || item.ClientId != SelectedReportClient.Id))
        {
            return false;
        }

        if (!TryGetYearMonth(item.PeriodLabel, out var year, out var month))
        {
            return SelectedReportScope.Value is DispatchReportScope.AllPeriods or DispatchReportScope.Client;
        }

        return SelectedReportScope.Value switch
        {
            DispatchReportScope.AllPeriods or DispatchReportScope.Client => true,
            DispatchReportScope.Month =>
                SelectedReportYear?.Year == year && SelectedReportMonth?.Month == month,
            DispatchReportScope.Year => SelectedReportYear?.Year == year,
            DispatchReportScope.Range when TryGetReportRange(
                out var startYear,
                out var startMonth,
                out var endYear,
                out var endMonth) =>
                ReportPeriodKey(year, month) >= ReportPeriodKey(startYear, startMonth) &&
                ReportPeriodKey(year, month) <= ReportPeriodKey(endYear, endMonth),
            _ => false,
        };
    }

    private DeliveryAttempt? LatestAttemptFor(Guid dispatchItemId) => DeliveryAttempts
        .Where(attempt => attempt.DispatchItemId == dispatchItemId)
        .OrderByDescending(attempt => attempt.AttemptNumber)
        .FirstOrDefault();

    private string DescribeCompletedDispatchBatch(Guid batchId)
    {
        var outcomes = DispatchItems
            .Where(item => item.BatchId == batchId && item.State is
                DispatchItemState.DraftCreated or
                DispatchItemState.AcceptedByProvider or
                DispatchItemState.Reconciled or
                DispatchItemState.Completed)
            .Select(item => DispatchOutcomePresenter.Present(item, LatestAttemptFor(item.Id)))
            .ToArray();
        if (outcomes.Length == 0)
        {
            return "Sequência concluída. Confira cada resultado antes de qualquer nova tentativa.";
        }

        if (outcomes.All(outcome => outcome.IsSimulation))
        {
            return $"{outcomes.Length} simulação(ões) concluída(s) somente neste aplicativo. Nenhum e-mail real foi enviado.";
        }

        if (outcomes.All(outcome => !outcome.IsSimulation))
        {
            return $"{outcomes.Length} operação(ões) registrada(s) no serviço de e-mail. Isso não comprova entrega ou leitura; confira os resultados sem repetir o envio.";
        }

        return "Sequência concluída com registros locais e externos. Confira o resultado de cada mensagem; nenhum aceite técnico comprova entrega ou leitura.";
    }

    private static bool PeriodLabelMatchesReport(string label, int year, int month) =>
        TryGetYearMonth(label, out var parsedYear, out var parsedMonth) &&
        parsedYear == year && parsedMonth == month;

    private DispatchReportFilter BuildReportFilter()
    {
        var filter = SelectedReportScope.Value switch
        {
            DispatchReportScope.AllPeriods or DispatchReportScope.Client => DispatchReportFilter.AllPeriods,
            DispatchReportScope.Month when SelectedReportYear?.Year is { } year &&
                SelectedReportMonth?.Month is { } month => DispatchReportFilter.ForMonth(year, month),
            DispatchReportScope.Year when SelectedReportYear?.Year is { } year =>
                DispatchReportFilter.ForYear(year),
            DispatchReportScope.Range when TryGetReportRange(
                out var startYear,
                out var startMonth,
                out var endYear,
                out var endMonth) =>
                DispatchReportFilter.ForRange(startYear, startMonth, endYear, endMonth),
            _ => throw new InvalidOperationException("Complete o período do relatório."),
        };

        if (SelectedReportClientFilter.Value == ReportClientFilterKind.SelectedClient)
        {
            if (SelectedReportClient is null)
            {
                throw new InvalidOperationException("Escolha o cliente do relatório.");
            }

            filter = filter.WithClient(SelectedReportClient.Id, SelectedReportClient.DisplayName);
        }

        return filter;
    }

    private bool IsHistoryEventVisible(
        Guid eventId,
        DateTimeOffset timestampUtc,
        bool ignoreShowHidden = false)
    {
        if (ShowHiddenHistory && !ignoreShowHidden)
        {
            return true;
        }

        return !historyVisibility.Rules.Any(rule =>
            rule.EventIds.Contains(eventId) ||
            (rule.StartUtc is not null || rule.EndExclusiveUtc is not null) &&
            (rule.StartUtc is null || timestampUtc >= rule.StartUtc) &&
            (rule.EndExclusiveUtc is null || timestampUtc < rule.EndExclusiveUtc));
    }

    private bool AuditMatchesSelectedPeriod(ReviewAuditEvent audit) =>
        HasNoOperationalPeriodFilter ||
        audit.DocumentId is { } documentId && ReviewDocuments.Any(document =>
            document.Id == documentId && MatchesSelectedPeriod(document.Period)) ||
        audit.GroupId is { } groupId && ReviewGroups.Any(group =>
            group.Id == groupId && MatchesSelectedPeriod(group)) ||
        ReviewAuditMetadataMatchesSelectedPeriod(audit);

    private bool ReviewAuditMetadataMatchesSelectedPeriod(ReviewAuditEvent audit)
    {
        var periods = GetReviewAuditValue(audit, "periods") ?? GetReviewAuditValue(audit, "period");
        return periods is not null && periods
            .Split('|', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Any(MatchesSelectedPeriod);
    }

    private bool AuditMatchesSelectedPeriod(DispatchAuditEvent audit) =>
        HasNoOperationalPeriodFilter ||
        audit.DispatchItemId is { } itemId && DispatchItems.Any(item =>
            item.Id == itemId && MatchesSelectedPeriod(item.PeriodLabel)) ||
        audit.GroupId is { } groupId && ReviewGroups.Any(group =>
            group.Id == groupId && MatchesSelectedPeriod(group));

    private bool HasNoOperationalPeriodFilter =>
        SelectedOperationalYear?.Year is null && SelectedOperationalMonth?.Month is null;

    private string GetPeriodStorageSegment()
    {
        var year = SelectedOperationalYear?.Year?.ToString(System.Globalization.CultureInfo.InvariantCulture) ??
            "todos-os-anos";
        return SelectedOperationalMonth?.Month switch
        {
            null => Path.Combine(year, "todos-os-meses"),
            0 => Path.Combine(year, "sem-mes-definido"),
            var month => Path.Combine(year, $"{month:00}-{OperationalMonths.First(item => item.Month == month).Label.ToLowerInvariant()}"),
        };
    }

    private static string GetReportStorageSegment(DispatchReportFilter filter)
    {
        var periodSegment = filter.Scope switch
        {
            DispatchReportScope.AllPeriods or DispatchReportScope.Client => "todos-os-periodos",
            DispatchReportScope.Month when filter.Year is { } year && filter.Month is { } month =>
                Path.Combine(
                    year.ToString(System.Globalization.CultureInfo.InvariantCulture),
                    month.ToString("00", System.Globalization.CultureInfo.InvariantCulture)),
            DispatchReportScope.Year when filter.Year is { } year =>
                Path.Combine(year.ToString(System.Globalization.CultureInfo.InvariantCulture), "ano-completo"),
            DispatchReportScope.Range when
                filter.StartYear is { } startYear && filter.StartMonth is { } startMonth &&
                filter.EndYear is { } endYear && filter.EndMonth is { } endMonth =>
                Path.Combine(
                    "intervalos",
                    $"{startYear:0000}-{startMonth:00}_a_{endYear:0000}-{endMonth:00}"),
            _ => "relatorio",
        };
        return filter.ClientId is { } clientId
            ? Path.Combine(
                "por-cliente",
                clientId.ToString("N")[..12],
                periodSegment)
            : periodSegment;
    }

    private bool TryGetReportRange(
        out int startYear,
        out int startMonth,
        out int endYear,
        out int endMonth)
    {
        startYear = SelectedReportStartYear?.Year ?? 0;
        startMonth = SelectedReportStartMonth?.Month ?? 0;
        endYear = SelectedReportEndYear?.Year ?? 0;
        endMonth = SelectedReportEndMonth?.Month ?? 0;
        return startYear is >= 1900 and <= 9999 &&
            startMonth is >= 1 and <= 12 &&
            endYear is >= 1900 and <= 9999 &&
            endMonth is >= 1 and <= 12;
    }

    private static int ReportPeriodKey(int year, int month) => (year * 12) + month;

    private static int? GetEffectiveYear(DocumentPeriod period) =>
        period.Year ?? period.StartDate?.Year;

    private static int? GetEffectiveMonth(DocumentPeriod period) =>
        period.Month ?? period.StartDate?.Month;

    private static DateTimeOffset LocalDateTimeToUtc(DateTimeOffset date, TimeSpan time)
    {
        var localDateTime = DateTime.SpecifyKind(date.Date + time, DateTimeKind.Unspecified);
        var offset = TimeZoneInfo.Local.GetUtcOffset(localDateTime);
        return new DateTimeOffset(localDateTime, offset).ToUniversalTime();
    }

    private static bool TryGetYearMonth(DocumentPeriod period, out int year, out int month)
    {
        year = GetEffectiveYear(period) ?? 0;
        month = GetEffectiveMonth(period) ?? 0;
        return year > 0 && month > 0;
    }

    private static bool TryGetYearMonth(string value, out int year, out int month)
    {
        year = 0;
        month = 0;
        var numbers = value.Split(['/', '-', ':', '_', ' '], StringSplitOptions.RemoveEmptyEntries)
            .Select(part => int.TryParse(part, out var number) ? number : -1)
            .Where(number => number >= 0)
            .ToArray();
        var yearIndex = Array.FindIndex(numbers, number => number is >= 1900 and <= 9999);
        if (yearIndex < 0)
        {
            return false;
        }

        year = numbers[yearIndex];
        if (yearIndex >= 2 && numbers[yearIndex - 1] is >= 1 and <= 12)
        {
            month = numbers[yearIndex - 1];
        }
        else if (yearIndex >= 1 && numbers[yearIndex - 1] is >= 1 and <= 12)
        {
            month = numbers[yearIndex - 1];
        }
        else if (yearIndex + 1 < numbers.Length && numbers[yearIndex + 1] is >= 1 and <= 12)
        {
            month = numbers[yearIndex + 1];
        }

        return true;
    }

    private static string? NormalizePath(string path)
    {
        try
        {
            return string.IsNullOrWhiteSpace(path) ? null : Path.GetFullPath(path);
        }
        catch (Exception exception) when (exception is ArgumentException or NotSupportedException)
        {
            return null;
        }
    }

    private bool TryValidateClientDraft(out string message)
    {
        if (string.IsNullOrWhiteSpace(LegalName))
        {
            message = IsLegalEntity
                ? "Informe a razão social da empresa."
                : "Informe o nome completo da pessoa.";
            return false;
        }

        var taxDigits = new string(PrimaryTaxId.Where(char.IsAsciiDigit).ToArray());
        var requiredDigits = IsLegalEntity ? 14 : 11;
        if (taxDigits.Length != requiredDigits)
        {
            message = IsLegalEntity
                ? "Informe o CNPJ completo com 14 dígitos. Pode digitar com ou sem pontuação."
                : "Informe o CPF completo com 11 dígitos. Pode digitar com ou sem pontuação.";
            return false;
        }

        try
        {
            _ = IsLegalEntity
                ? BrazilianRegistration.NormalizeCnpj(PrimaryTaxId)
                : BrazilianRegistration.NormalizeCpf(PrimaryTaxId);
        }
        catch (DomainValidationException)
        {
            message = IsLegalEntity
                ? "O CNPJ tem 14 dígitos, mas os dois dígitos verificadores não conferem. Use um CNPJ válido; números aleatórios são rejeitados para evitar associação ao cliente errado."
                : "O CPF tem 11 dígitos, mas os dois dígitos verificadores não conferem. Use um CPF válido; números aleatórios são rejeitados para evitar associação à pessoa errada.";
            return false;
        }

        var invalidRecipient = Recipients.FirstOrDefault(recipient =>
            string.IsNullOrWhiteSpace(recipient.DisplayName) || !IsValidEmail(recipient.Email));
        if (invalidRecipient is not null)
        {
            message = "Confira o nome e o e-mail dos contatos de entrega antes de salvar.";
            return false;
        }

        var invalidPartner = Partners.FirstOrDefault(partner =>
            !string.IsNullOrWhiteSpace(partner.Email) && !IsValidEmail(partner.Email));
        if (invalidPartner is not null)
        {
            message = "Confira o e-mail do sócio ou representante antes de salvar.";
            return false;
        }

        message = string.Empty;
        return true;
    }

    private void SetClientFeedback(string message, bool isError = false)
    {
        ClientFeedbackMessage = message;
        ClientFeedbackIsError = isError;
    }

    private void ResetTemplateEditor(bool clearSelection = true)
    {
        editingTemplateId = null;
        editingTemplateVersion = 0;
        editingTemplateDocumentTypeId = null;
        editingTemplateSignatureMode = SignatureModeModel.Organization;
        editingTemplateIsDefault = false;
        editingTemplateIsActive = true;
        if (clearSelection)
        {
            SelectedTemplate = null;
        }

        TemplateName = string.Empty;
        TemplateSubject = string.Empty;
        TemplateBody = string.Empty;
        OnPropertyChanged(nameof(IsEditingTemplate));
        OnPropertyChanged(nameof(TemplateEditorTitle));
        OnPropertyChanged(nameof(TemplateSaveActionLabel));
    }

    private void ApplyStandardTemplate(PersonTypeModel personType)
    {
        if (!HasExistingClient)
        {
            SetClientFeedback("Cadastre ou abra um cliente antes de preparar uma mensagem padrão.", true);
            StatusMessage = ClientFeedbackMessage;
            return;
        }

        var preset = StandardMessageTemplatePresetCatalog.For(personType);
        editingTemplateId = null;
        editingTemplateVersion = 0;
        editingTemplateDocumentTypeId = null;
        editingTemplateSignatureMode = SignatureModeModel.Organization;
        editingTemplateIsDefault = false;
        editingTemplateIsActive = true;
        SelectedTemplate = null;
        TemplateName = preset.Name;
        TemplateSubject = preset.SubjectTemplate;
        TemplateBody = preset.BodyTemplate;
        OnPropertyChanged(nameof(IsEditingTemplate));
        OnPropertyChanged(nameof(TemplateEditorTitle));
        OnPropertyChanged(nameof(TemplateSaveActionLabel));
        var label = personType == PersonTypeModel.LegalEntity ? "empresa" : "pessoa física";
        SetClientFeedback(
            $"Mensagem padrão para {label} preenchida. Você pode salvar como está ou ajustar antes de criar.");
        StatusMessage = ClientFeedbackMessage;
    }

    private void InsertTemplatePlaceholder(string placeholderKey)
    {
        if (!HasExistingClient)
        {
            SetClientFeedback("Cadastre ou abra um cliente antes de personalizar a mensagem.", true);
            return;
        }

        var placeholder = MessageTemplatePlaceholderCatalog.ToToken(placeholderKey);
        if (string.Equals(SelectedTemplatePlaceholderTarget.Key, "subject", StringComparison.Ordinal))
        {
            TemplateSubject = string.IsNullOrWhiteSpace(TemplateSubject)
                ? placeholder
                : $"{TemplateSubject} {placeholder}";
            StatusMessage = "Informação variável adicionada ao assunto da mensagem.";
        }
        else
        {
            TemplateBody = string.IsNullOrWhiteSpace(TemplateBody)
                ? placeholder
                : $"{TemplateBody}{(TemplateBody.EndsWith('\n') ? string.Empty : " ")}{placeholder}";
            StatusMessage = "Informação variável adicionada ao texto da mensagem.";
        }
    }

    private bool TryValidateTemplateDraft(out string message)
    {
        if (string.IsNullOrWhiteSpace(TemplateName))
        {
            message = "Informe um nome para identificar esta mensagem. Esse nome não é enviado ao cliente.";
            return false;
        }

        if (TemplateName.Trim().Length > 160)
        {
            message = "O nome da mensagem pode ter no máximo 160 caracteres. O texto do e-mail continua aceitando até 20.000.";
            return false;
        }

        if (string.IsNullOrWhiteSpace(TemplateSubject) || TemplateSubject.Length > 500)
        {
            message = "Informe um assunto com até 500 caracteres.";
            return false;
        }

        if (string.IsNullOrWhiteSpace(TemplateBody) || TemplateBody.Length > 20_000)
        {
            message = "Informe o texto da mensagem com até 20.000 caracteres.";
            return false;
        }

        var subjectPlaceholders = MessageTemplatePlaceholderCatalog.Validate(TemplateSubject);
        var bodyPlaceholders = MessageTemplatePlaceholderCatalog.Validate(TemplateBody);
        var unknownPlaceholders = subjectPlaceholders.UnknownKeys
            .Concat(bodyPlaceholders.UnknownKeys)
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        if (unknownPlaceholders.Length > 0)
        {
            message =
                $"Há informações variáveis desconhecidas: {string.Join(", ", unknownPlaceholders)}. " +
                "Remova-as ou use os botões disponíveis no editor.";
            return false;
        }

        message = string.Empty;
        return true;
    }

    private static bool IsValidEmail(string value)
    {
        return EmailAddress.TryNormalize(value, out _);
    }

    private static string TranslateDomainValidationMessage(string message)
    {
        if (message.Contains("CPF", StringComparison.OrdinalIgnoreCase))
        {
            if (message.Contains("dígitos verificadores", StringComparison.OrdinalIgnoreCase) ||
                message.Contains("mathematically invalid", StringComparison.OrdinalIgnoreCase))
            {
                return "O CPF informado não é válido. Confira os 11 números e os dígitos verificadores.";
            }

            if (message.Contains("11", StringComparison.Ordinal))
            {
                return "O CPF precisa ter 11 números. Você pode digitá-lo com ou sem pontos e hífen.";
            }

            return "Confira o CPF informado e tente novamente.";
        }

        if (message.Contains("CNPJ", StringComparison.OrdinalIgnoreCase))
        {
            return message.Contains("mathematically invalid", StringComparison.OrdinalIgnoreCase)
                ? "O CNPJ informado não é válido. Confira os dígitos."
                : "Confira o CNPJ informado e tente novamente.";
        }

        if (message.Contains("e-mail", StringComparison.OrdinalIgnoreCase) ||
            message.Contains("email", StringComparison.OrdinalIgnoreCase))
        {
            return "Confira o endereço de e-mail informado e tente novamente.";
        }

        if (message.Contains("Legal/full name", StringComparison.OrdinalIgnoreCase))
        {
            return "Informe a razão social ou o nome completo do cliente.";
        }

        return "Confira os dados do cadastro e tente novamente.";
    }

    private async Task<StagedDocument> StageDocumentAsync(
        string sourcePath,
        DocumentPeriod recognizedPeriod,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(DocumentArchiveDirectory))
        {
            return new StagedDocument(sourcePath, false);
        }

        var year = GetEffectiveYear(recognizedPeriod) ??
            SelectedOperationalYear?.Year ??
            DateTimeOffset.Now.Year;
        var month = GetEffectiveMonth(recognizedPeriod) ??
            (SelectedOperationalMonth?.Month is > 0 and <= 12
                ? SelectedOperationalMonth.Month.Value
                : DateTimeOffset.Now.Month);
        var directory = Path.Combine(
            DocumentArchiveDirectory,
            year.ToString(System.Globalization.CultureInfo.InvariantCulture),
            month.ToString("00", System.Globalization.CultureInfo.InvariantCulture));
        Directory.CreateDirectory(directory);

        await using var stream = File.OpenRead(sourcePath);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken))
            .ToLowerInvariant();
        var invalidCharacters = Path.GetInvalidFileNameChars().ToHashSet();
        var safeBaseName = new string(Path.GetFileNameWithoutExtension(sourcePath)
            .Where(character => !invalidCharacters.Contains(character))
            .ToArray())
            .Trim();
        if (string.IsNullOrWhiteSpace(safeBaseName))
        {
            safeBaseName = "documento";
        }

        var targetPath = Path.Combine(directory, $"{safeBaseName}-{hash[..12]}.pdf");
        var wasCreated = !File.Exists(targetPath);
        if (wasCreated)
        {
            File.Copy(sourcePath, targetPath, overwrite: false);
        }

        return new StagedDocument(targetPath, wasCreated);
    }

    private static void RollbackStagedDocument(
        StagedDocument stagedDocument,
        Exception persistenceException)
    {
        if (!stagedDocument.WasCreated)
        {
            return;
        }

        try
        {
            File.Delete(stagedDocument.Path);
        }
        catch (Exception cleanupException) when (cleanupException is IOException or UnauthorizedAccessException)
        {
            throw new DocumentImportException(
                "document.archive_rollback_failed",
                "A importação falhou e a cópia criada no acervo não pôde ser removida. Não tente reenviar; peça ao suporte para conferir a pasta.",
                new AggregateException(persistenceException, cleanupException));
        }
    }

    private sealed record StagedDocument(string Path, bool WasCreated);

    private static string FormatTaxId(string value, PersonTypeModel personType)
    {
        var maximumLength = personType == PersonTypeModel.LegalEntity ? 14 : 11;
        var digits = new string((value ?? string.Empty).Where(char.IsAsciiDigit).ToArray());
        if (digits.Length > maximumLength)
        {
            return digits;
        }
        if (personType == PersonTypeModel.Individual)
        {
            return digits.Length switch
            {
                <= 3 => digits,
                <= 6 => $"{digits[..3]}.{digits[3..]}",
                <= 9 => $"{digits[..3]}.{digits[3..6]}.{digits[6..]}",
                _ => $"{digits[..3]}.{digits[3..6]}.{digits[6..9]}-{digits[9..]}",
            };
        }

        return digits.Length switch
        {
            <= 2 => digits,
            <= 5 => $"{digits[..2]}.{digits[2..]}",
            <= 8 => $"{digits[..2]}.{digits[2..5]}.{digits[5..]}",
            <= 12 => $"{digits[..2]}.{digits[2..5]}.{digits[5..8]}/{digits[8..]}",
            _ => $"{digits[..2]}.{digits[2..5]}.{digits[5..8]}/{digits[8..12]}-{digits[12..]}",
        };
    }

    private static string? EmptyToNull(string value) =>
        string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    private static string FormatBytes(long bytes) => bytes switch
    {
        >= 1_073_741_824 => $"{bytes / 1_073_741_824d:0.0} GB",
        >= 1_048_576 => $"{bytes / 1_048_576d:0.0} MB",
        >= 1_024 => $"{bytes / 1_024d:0.0} KB",
        _ => $"{bytes} bytes",
    };
}
