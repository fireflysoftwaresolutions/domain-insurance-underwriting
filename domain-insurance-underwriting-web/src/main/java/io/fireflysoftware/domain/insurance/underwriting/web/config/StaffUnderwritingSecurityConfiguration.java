/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.config;

import io.fireflysoftware.domain.insurance.underwriting.infra.staff.StaffUnderwritingSettings;
import io.fireflysoftware.domain.insurance.underwriting.infra.staff.impl.ExchangedStaffUnderwritingClientFactoryImpl;
import io.fireflysoftware.domain.insurance.underwriting.web.config.impl.*;
import java.time.Clock;
import org.fireflyframework.security.core.context.PrincipalFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods=false)
public class StaffUnderwritingSecurityConfiguration {
    @Bean @Order(-50)
    WebFilter staffUnderwritingSecurity(StaffUnderwritingSettings settings,ObjectProvider<PrincipalFactory> principals){
        ReactiveJwtDecoder decoder=settings.enabled()?ExchangedStaffUnderwritingClientFactoryImpl.decoder(settings,settings.domainAudience()):
            token->Mono.error(new JwtException("STAFF underwriting trust is not configured"));
        return new ScopedUnderwritingBearerWebFilterImpl(new org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/staff/underwriting","/api/v1/staff/underwriting/**"),ServerWebExchangeMatchers.pathMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/submissions/*")),decoder,token->{
            var factory=principals.getIfAvailable();if(factory==null)return Mono.error(new JwtException("Native STAFF principal projection is not configured"));
            return new StaffUnderwritingJwtConverterImpl(settings,factory,Clock.systemUTC()).convert(token);
        });
    }
}
