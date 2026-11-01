/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.config;

import io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteCoreProperties;
import java.net.URI;
import java.util.Set;
import org.fireflyframework.security.rs.AccessTokenValidators;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/** Protects only the new governed owner-proxy paths; it never installs a permit-all chain. */
@Configuration(proxyBeanMethods=false)
public class GovernedQuoteSecurityConfiguration {
    @Bean("governedQuoteCallerJwtDecoder")
    ReactiveJwtDecoder governedQuoteCallerJwtDecoder(GovernedQuoteCoreProperties properties) {
        if(!valid(properties.issuer(),properties.allowLoopback()) || !valid(properties.jwkSetUri(),properties.allowLoopback())
                || properties.domainAudience()==null || properties.domainAudience().isBlank())
            return token->Mono.error(new JwtException("Governed quote caller trust is not configured"));
        var decoder=NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(AccessTokenValidators.forIssuerAndAudience(properties.issuer(),properties.domainAudience()));return decoder;
    }
    @Bean
    @Order(-100)
    SecurityWebFilterChain governedQuoteCallerSecurity(ServerHttpSecurity http,
            @Qualifier("governedQuoteCallerJwtDecoder") ReactiveJwtDecoder decoder) {
        String base="/api/v1/tenants/*/submissions/*/parties/*/products/*/quote-offers";
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(base,base+"/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache->cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(exchanges->exchanges.anyExchange().authenticated())
                .oauth2ResourceServer(resource->resource.jwt(jwt->jwt.jwtDecoder(decoder))).build();
    }
    private static boolean valid(String value,boolean loopback) {
        try {URI uri=URI.create(value);return uri.getHost()!=null && uri.getRawUserInfo()==null && uri.getRawQuery()==null
                && uri.getRawFragment()==null && uri.normalize().equals(uri) && ("https".equals(uri.getScheme())
                || loopback && "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost()));}
        catch(RuntimeException invalid){return false;}
    }
}
