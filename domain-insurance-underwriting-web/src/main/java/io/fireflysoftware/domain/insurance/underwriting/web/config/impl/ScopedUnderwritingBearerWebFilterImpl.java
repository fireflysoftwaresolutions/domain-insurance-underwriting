/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.config.impl;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.*;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.*;
import org.springframework.security.web.server.context.*;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Scoped native receiver authentication never replaces the global security chain or its decoder. */
public final class ScopedUnderwritingBearerWebFilterImpl implements WebFilter {
    private final ServerWebExchangeMatcher routes;
    private final ReactiveJwtDecoder decoder;
    private final Converter<Jwt,Mono<AbstractAuthenticationToken>> projection;
    public ScopedUnderwritingBearerWebFilterImpl(ServerWebExchangeMatcher routes,ReactiveJwtDecoder decoder,
            Converter<Jwt,Mono<AbstractAuthenticationToken>> projection){this.routes=Objects.requireNonNull(routes);this.decoder=Objects.requireNonNull(decoder);this.projection=Objects.requireNonNull(projection);}
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){return routes.matches(exchange).flatMap(match->{
        if(!match.isMatch())return chain.filter(exchange);
        var headers=exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        if(headers==null||headers.size()!=1)return new BearerTokenServerAuthenticationEntryPoint().commence(exchange,invalid());
        return Mono.using(()->new AtomicReference<AbstractAuthenticationToken>(),owned->{
            var manager=new JwtReactiveAuthenticationManager(decoder);
            manager.setJwtAuthenticationConverter(jwt->Mono.defer(()->projection.convert(jwt)).switchIfEmpty(Mono.error(invalid())).map(token->{
                if(!token.isAuthenticated()||!owned.compareAndSet(null,token))throw invalid();return token;
            }));
            var nativeFilter=new AuthenticationWebFilter(manager);
            nativeFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
            nativeFilter.setServerAuthenticationConverter(request->new ServerBearerTokenAuthenticationConverter().convert(request).switchIfEmpty(Mono.error(invalid())));
            nativeFilter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(new BearerTokenServerAuthenticationEntryPoint()));
            return nativeFilter.filter(exchange,authenticated->new SecurityContextServerWebExchangeWebFilter().filter(authenticated,chain));
        },owned->{var token=owned.get();if(token!=null)token.setAuthenticated(false);},true);
    });}
    private static OAuth2AuthenticationException invalid(){return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),"One verified original underwriting caller required");}
}
