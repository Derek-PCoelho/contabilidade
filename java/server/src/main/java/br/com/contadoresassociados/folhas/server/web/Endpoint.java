package br.com.contadoresassociados.folhas.server.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Política de acesso de um endpoint de API: permissões exigidas (todas), MFA e limite de taxa.
 * Endpoints de API sem esta anotação são recusados (negação por padrão).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Endpoint {

    /** Permissões exigidas; vazio = qualquer usuária autenticada. */
    String[] permissions() default {};

    boolean mfa() default false;

    RateLimiter.Policy rate() default RateLimiter.Policy.READ;

    /** Endpoint sem autenticação (saúde, descoberta OIDC). */
    boolean anonymous() default false;
}
