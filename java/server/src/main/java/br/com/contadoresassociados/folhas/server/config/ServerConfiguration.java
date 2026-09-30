package br.com.contadoresassociados.folhas.server.config;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.server.catalog.CatalogRepository;
import br.com.contadoresassociados.folhas.server.db.DatabaseMigrator;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.dispatch.PreflightService;
import br.com.contadoresassociados.folhas.server.identity.RefreshTokenStore;
import br.com.contadoresassociados.folhas.server.policy.RolloutPolicies;
import br.com.contadoresassociados.folhas.server.realtime.SyncNotifier;
import br.com.contadoresassociados.folhas.server.security.ApiAuthenticator;
import br.com.contadoresassociados.folhas.server.security.SigningKeys;
import br.com.contadoresassociados.folhas.server.security.TokenService;
import br.com.contadoresassociados.folhas.server.sync.SyncRepository;
import br.com.contadoresassociados.folhas.server.web.ApiInterceptor;
import br.com.contadoresassociados.folhas.server.web.EdgeFilter;
import br.com.contadoresassociados.folhas.server.web.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Composição do servidor (equivalente ao {@code AddPhase2Services} do .NET). */
@Configuration
public class ServerConfiguration implements WebMvcConfigurer {

    private final ApiAuthenticator authenticator;
    private final RateLimiter limiter;

    public ServerConfiguration(ApiAuthenticator authenticator, RateLimiter limiter) {
        this.authenticator = authenticator;
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiInterceptor(authenticator, limiter)).addPathPatterns("/**").excludePathPatterns("/error");
    }

    /** JSON idêntico ao {@code JsonSerializerDefaults.Web} do .NET (mesmo mapper do Desktop). */
    @Bean
    @Primary
    public static ObjectMapper objectMapper() {
        return Json.mapper();
    }

    @Bean
    public static ServerSettings serverSettings(Environment environment) {
        return new ServerSettings(environment);
    }

    @Bean
    public static Clock clock() {
        return Clock.system();
    }

    @Bean
    public static Db db(DataSource dataSource, ServerSettings settings) {
        if (settings.flag("database.migrate-on-startup")) {
            DatabaseMigrator.migrate(dataSource);
        }
        return new Db(dataSource);
    }

    @Bean
    public static SigningKeys signingKeys(ServerSettings settings) {
        if (settings.isDevelopmentOrTesting()) {
            return SigningKeys.ephemeral();
        }
        var path = settings.text("oidc.signing-certificate-path", null);
        return SigningKeys.fromPkcs12(path == null ? null : Path.of(path),
                settings.text("oidc.signing-certificate-password", null), Instant.now());
    }

    @Bean
    public static TokenService tokenService(SigningKeys keys, ServerSettings settings) {
        return new TokenService(keys, settings.issuer());
    }

    @Bean
    public static ApiAuthenticator apiAuthenticator(TokenService tokens, ServerSettings settings, Db db, Clock clock) {
        return new ApiAuthenticator(tokens, settings, db, clock);
    }

    @Bean
    public static RateLimiter rateLimiter(Clock clock) {
        return new RateLimiter(clock);
    }

    @Bean
    public static RefreshTokenStore refreshTokenStore() {
        return new RefreshTokenStore();
    }

    @Bean
    public static CatalogRepository catalogRepository(Db db, Clock clock) {
        return new CatalogRepository(db, clock);
    }

    @Bean
    public static SyncRepository syncRepository(Db db, Clock clock) {
        return new SyncRepository(db, clock);
    }

    @Bean
    public static RolloutPolicies rolloutPolicies(ServerSettings settings) {
        return new RolloutPolicies(settings);
    }

    @Bean
    public static PreflightService preflightService(Db db, Clock clock, RolloutPolicies policies) {
        return new PreflightService(db, clock, policies);
    }

    @Bean
    public static SyncNotifier syncNotifier(ApiAuthenticator authenticator) {
        return new SyncNotifier(authenticator);
    }

    @Bean
    public static FilterRegistrationBean<EdgeFilter> edgeFilter(ServerSettings settings) {
        var registration = new FilterRegistrationBean<>(new EdgeFilter(settings));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
