/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.config;

import io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteCoreProperties;
import io.fireflysoftware.domain.insurance.underwriting.web.config.impl.ScopedUnderwritingBearerWebFilterImpl;
import java.net.URI;
import java.util.Set;
import org.fireflyframework.security.rs.AccessTokenValidators;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/** The quote receiver supplements global security without suppressing its auto-configuration. */
@Configuration(proxyBeanMethods=false)
public class GovernedQuoteSecurityConfiguration {
    @Bean @Order(-50)
    WebFilter governedQuoteCallerSecurity(GovernedQuoteCoreProperties properties){
        String base="/api/v1/tenants/*/submissions/*/parties/*/products/*/quote-offers";
        return new ScopedUnderwritingBearerWebFilterImpl(ServerWebExchangeMatchers.pathMatchers(base,base+"/**"),decoder(properties),new ReactiveJwtAuthenticationConverter());
    }
    private static ReactiveJwtDecoder decoder(GovernedQuoteCoreProperties properties){
        if(!valid(properties.issuer(),properties.allowLoopback())||!valid(properties.jwkSetUri(),properties.allowLoopback())
            ||properties.domainAudience()==null||properties.domainAudience().isBlank())return token->Mono.error(new JwtException("Governed quote caller trust is not configured"));
        var decoder=NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(AccessTokenValidators.forIssuerAndAudience(properties.issuer(),properties.domainAudience()));return decoder;
    }
    private static boolean valid(String value,boolean loopback){
        try{URI uri=URI.create(value);return uri.getHost()!=null&&uri.getRawUserInfo()==null&&uri.getRawQuery()==null
            &&uri.getRawFragment()==null&&uri.normalize().equals(uri)&&("https".equals(uri.getScheme())
                ||loopback&&"http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost()));}
        catch(RuntimeException invalid){return false;}
    }
}
