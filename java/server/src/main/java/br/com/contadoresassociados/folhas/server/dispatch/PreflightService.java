package br.com.contadoresassociados.folhas.server.dispatch;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.production.ProductionRollout;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightRequest;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightResponse;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.db.Sql;
import br.com.contadoresassociados.folhas.server.policy.RolloutPolicies;
import br.com.contadoresassociados.folhas.server.policy.Versions;
import br.com.contadoresassociados.folhas.server.security.Principal;
import br.com.contadoresassociados.folhas.server.sync.AuditLog;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Autorização central do envio (porta do {@code EmailDispatchController.Preflight}), com a
 * mesma ordem de avaliação e os mesmos códigos de erro. Cada decisão gera uma linha de
 * auditoria; envios reais autorizados geram uma autorização append-only idempotente por
 * {@code OperationId}.
 *
 * <p>Correções: 3.7 — a cota diária usa o dia de Brasília; 4.7 — preflight em lote numa única
 * transação (a cota é consumida item a item, na ordem recebida); 3.2 — retry de serialização.
 */
public final class PreflightService {

    public static final int MAX_BATCH_ITEMS = 200;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /** Requisição inválida (400 no endpoint unitário, item negado no lote). */
    public static final class InvalidRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidRequest() {
            super("Pedido de autorização de envio inválido.");
        }
    }

    private final Db db;
    private final Clock clock;
    private final RolloutPolicies policies;

    public PreflightService(Db db, Clock clock, RolloutPolicies policies) {
        this.db = db;
        this.clock = clock;
        this.policies = policies;
    }

    public EmailSendPreflightResponse authorize(Principal user, EmailSendPreflightRequest request) {
        if (!valid(request)) {
            throw new InvalidRequest();
        }
        return db.tenant(user.organizationId(), Db.Isolation.SERIALIZABLE, c -> evaluate(c, user, request));
    }

    public List<EmailSendPreflightResponse> authorizeBatch(Principal user, List<EmailSendPreflightRequest> requests) {
        return db.tenant(user.organizationId(), Db.Isolation.SERIALIZABLE, c -> {
            var results = new ArrayList<EmailSendPreflightResponse>(requests.size());
            for (var request : requests) {
                if (!valid(request)) {
                    results.add(new EmailSendPreflightResponse(request == null ? null : request.operationId(), false,
                            false, policies.minimumSupportedVersion(), UUID.randomUUID(), "PREFLIGHT_INVALID_REQUEST"));
                    continue;
                }
                results.add(evaluate(c, user, request));
            }
            return results;
        });
    }

    static boolean valid(EmailSendPreflightRequest r) {
        return r != null && r.operationId() != null && !new UUID(0, 0).equals(r.operationId())
                && (RolloutPolicies.GRAPH.equals(r.providerKey()) || RolloutPolicies.GMAIL.equals(r.providerKey()))
                && r.attachmentCount() >= 0 && r.attachmentCount() <= 1000 && r.batchSize() >= 1 && r.batchSize() <= 1000
                && r.operationMode() != null && r.dispatchFingerprint() != null
                && r.dispatchFingerprint().length() == 64 && r.dispatchFingerprint().chars().allMatch(ch ->
                        Character.digit(ch, 16) >= 0)
                && r.applicationVersion() != null && Versions.parse(r.applicationVersion()).isPresent();
    }

    private EmailSendPreflightResponse evaluate(Connection c, Principal user, EmailSendPreflightRequest request)
            throws SQLException {
        var current = Versions.parse(request.applicationVersion()).orElseThrow();
        var provider = request.providerKey();
        var providerEnabled = policies.providerSendEnabled(provider);
        var globallyEnabled = policies.globalSendEnabled();
        var pilot = policies.pilot();
        var pilotOperationEnabled = switch (request.operationMode()) {
            case TEST -> pilot.allowTest();
            case DRAFT -> pilot.allowDraft();
            case SEND -> pilot.allowSend();
        };
        var productionRequired = request.operationMode() == DispatchOperationMode.SEND;
        var production = policies.production();
        ProductionRollout.Readiness readiness = policies.productionReadiness();
        var roleAllowed = !productionRequired || readiness.allowedRoles().stream().anyMatch(user.roles()::contains);
        var batchAllowed = !productionRequired || request.batchSize() <= production.maximumBatchSize();
        var productionReady = !productionRequired || readiness.readyForSend();
        var remotelyEnabled = providerEnabled && globallyEnabled && (!pilot.enabled() || pilotOperationEnabled)
                && productionReady && roleAllowed && batchAllowed;

        var providerMinimum = Versions.parse(policies.providerMinimumVersion(provider));
        var globalMinimum = Versions.parse(policies.minimumSupportedVersion());
        var productionMinimum = productionRequired ? Versions.parse(production.minimumApplicationVersion())
                : java.util.Optional.<Versions>empty();
        var productionVersionValid = !productionRequired || productionMinimum.isPresent();
        var candidates = new ArrayList<Versions>();
        providerMinimum.ifPresent(candidates::add);
        globalMinimum.ifPresent(candidates::add);
        productionMinimum.ifPresent(candidates::add);
        var effectiveMinimum = candidates.stream().max(Versions::compareTo).map(Versions::text)
                .orElse(policies.currentVersion());
        var versionAllowed = providerMinimum.isPresent() && globalMinimum.isPresent()
                && current.isAtLeast(providerMinimum.get()) && current.isAtLeast(globalMinimum.get())
                && productionVersionValid
                && (!productionRequired || current.isAtLeast(productionMinimum.get()));
        var pilotBlocked = pilot.enabled() && !pilotOperationEnabled;

        var now = clock.now();
        // 3.7: o dia da cota é o dia de Brasília (antes 21h–24h contavam para o dia seguinte)
        var authorizationDate = DAY.format(clock.accountingDate());
        Existing existing = null;
        var authorizedToday = 0;
        if (productionRequired) {
            existing = existing(c, user.organizationId(), request.operationId());
            authorizedToday = countToday(c, user.organizationId(), authorizationDate);
        }
        var dailyLimitAllowed = !productionRequired || existing != null
                || authorizedToday < production.maximumDailySends();
        var idempotencyConsistent = !productionRequired || existing == null
                || existing.providerKey.equals(provider)
                        && existing.fingerprint.equalsIgnoreCase(request.dispatchFingerprint())
                        && existing.batchSize == request.batchSize()
                        && existing.attachmentCount == request.attachmentCount();
        var authorized = remotelyEnabled && versionAllowed && dailyLimitAllowed && idempotencyConsistent;
        if (authorized && productionRequired && existing == null) {
            try (var st = c.prepareStatement("INSERT INTO production_dispatch_authorizations (\"OperationId\", "
                    + "\"OrganizationId\", \"UserId\", \"DeviceId\", \"ProviderKey\", \"DispatchFingerprint\", \"BatchSize\", "
                    + "\"AttachmentCount\", \"ApplicationVersion\", \"AuthorizationDateUtc\", \"AuthorizedAtUtc\") "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                st.setObject(1, request.operationId());
                st.setObject(2, user.organizationId());
                st.setObject(3, user.userId());
                st.setObject(4, user.deviceSessionId());
                st.setString(5, provider);
                st.setString(6, request.dispatchFingerprint().toLowerCase(Locale.ROOT));
                st.setInt(7, request.batchSize());
                st.setInt(8, request.attachmentCount());
                st.setString(9, current.text());
                st.setString(10, authorizationDate);
                Sql.instant(st, 11, now);
                st.executeUpdate();
            }
        }
        String errorCode = null;
        if (!authorized) {
            if (pilotBlocked) {
                errorCode = request.operationMode() == DispatchOperationMode.SEND ? "PILOT_SEND_DISABLED"
                        : "PILOT_OPERATION_DISABLED";
            } else if (!providerEnabled || !globallyEnabled) {
                errorCode = "SEND_DISABLED_REMOTELY";
            } else if (productionRequired && !productionReady) {
                errorCode = "PRODUCTION_ROLLOUT_CLOSED";
            } else if (productionRequired && !roleAllowed) {
                errorCode = "PRODUCTION_ROLE_FORBIDDEN";
            } else if (productionRequired && !batchAllowed) {
                errorCode = "PRODUCTION_BATCH_LIMIT_EXCEEDED";
            } else if (productionRequired && !dailyLimitAllowed) {
                errorCode = "PRODUCTION_DAILY_LIMIT_REACHED";
            } else if (productionRequired && !idempotencyConsistent) {
                errorCode = "PRODUCTION_IDEMPOTENCY_CONFLICT";
            } else if (!versionAllowed) {
                errorCode = "APP_VERSION_BELOW_MINIMUM";
            } else {
                errorCode = "REMOTE_SEND_NOT_AUTHORIZED";
            }
        }
        var correlationId = UUID.randomUUID();
        var data = new LinkedHashMap<String, Object>();
        data.put("ProviderKey", provider);
        data.put("OperationMode", request.operationMode().ordinal());
        data.put("AttachmentCount", request.attachmentCount());
        data.put("BatchSize", request.batchSize());
        data.put("ProductionStage", productionRequired ? capitalize(production.stage().name()) : null);
        data.put("ApplicationVersion", current.text());
        data.put("Authorized", authorized);
        data.put("ErrorCode", errorCode);
        AuditLog.append(c, user.organizationId(), user.userId(), user.deviceSessionId(), "email_dispatch_preflight",
                request.operationId().toString(), authorized ? "authorized" : "denied", "email",
                authorized ? "information" : "warning", data, now, correlationId);
        return new EmailSendPreflightResponse(request.operationId(), authorized, authorized, effectiveMinimum,
                correlationId, errorCode);
    }

    public int authorizedToday(UUID organizationId) {
        return db.tenantRead(organizationId, c -> countToday(c, organizationId, DAY.format(clock.accountingDate())));
    }

    private record Existing(String providerKey, String fingerprint, int batchSize, int attachmentCount) {
    }

    private static Existing existing(Connection c, UUID org, UUID operationId) throws SQLException {
        try (var st = c.prepareStatement("SELECT \"ProviderKey\", \"DispatchFingerprint\", \"BatchSize\", \"AttachmentCount\" "
                + "FROM production_dispatch_authorizations WHERE \"OrganizationId\" = ? AND \"OperationId\" = ?")) {
            st.setObject(1, org);
            st.setObject(2, operationId);
            try (var rs = st.executeQuery()) {
                return rs.next() ? new Existing(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)) : null;
            }
        }
    }

    private static int countToday(Connection c, UUID org, String day) throws SQLException {
        try (var st = c.prepareStatement("SELECT count(*) FROM production_dispatch_authorizations WHERE "
                + "\"OrganizationId\" = ? AND \"AuthorizationDateUtc\" = ?")) {
            st.setObject(1, org);
            st.setString(2, day);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String capitalize(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase(Locale.ROOT);
    }
}
