/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.config.impl;

import io.fireflysoftware.domain.insurance.underwriting.infra.staff.StaffUnderwritingSettings;
import java.time.Clock;
import java.util.*;
import org.fireflyframework.security.core.context.PrincipalFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

public final class StaffUnderwritingJwtConverterImpl implements Converter<Jwt,Mono<AbstractAuthenticationToken>> {
    private final StaffUnderwritingSettings settings;
    private final PrincipalFactory principals;
    private final Clock clock;
    public StaffUnderwritingJwtConverterImpl(StaffUnderwritingSettings settings,PrincipalFactory principals,Clock clock){
        this.settings=Objects.requireNonNull(settings);this.principals=Objects.requireNonNull(principals);this.clock=Objects.requireNonNull(clock);
    }
    @Override public Mono<AbstractAuthenticationToken> convert(Jwt jwt){return Mono.fromCallable(()->{
        if(!settings.enabled()||jwt==null||jwt.getIssuer()==null||!settings.issuer().equals(jwt.getIssuer().toString())
            ||jwt.getSubject()==null||jwt.getSubject().isBlank()||jwt.getSubject().length()>255
            ||!List.of(settings.domainAudience()).equals(jwt.getAudience())||!settings.inboundClientId().equals(jwt.getClaims().get("client_id"))
            ||!settings.platformTenant().equals(jwt.getClaims().get("tenant_id"))||!"STAFF".equals(jwt.getClaims().get("principal_kind"))
            ||jwt.getExpiresAt()==null||jwt.getNotBefore()==null||!clock.instant().isBefore(jwt.getExpiresAt())||clock.instant().isBefore(jwt.getNotBefore())
            ||!jwt.getNotBefore().isBefore(jwt.getExpiresAt()))throw invalid();
        Object value=jwt.getClaims().get("staff_actor_id");
        if(!(value instanceof String actor)||!actor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||UUID.fromString(actor).equals(new UUID(0,0)))throw invalid();
        var principal=principals.fromClaims(jwt.getSubject(),jwt.getIssuer().toString(),jwt.getClaims()).toBuilder()
            .tenantId(settings.platformTenant()).attributes(Map.of("staffActorId",actor)).build();
        return (AbstractAuthenticationToken)new JwtAuthenticationToken(jwt,principal,principal.authorities().stream().map(SimpleGrantedAuthority::new).toList());
    }).onErrorMap(error->invalid());}
    private static OAuth2AuthenticationException invalid(){return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),"Current original STAFF authentication required");}
}
