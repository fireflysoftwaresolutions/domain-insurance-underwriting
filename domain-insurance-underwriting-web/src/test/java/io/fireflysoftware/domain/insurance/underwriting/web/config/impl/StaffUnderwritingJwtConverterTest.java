/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.config.impl;

import io.fireflysoftware.domain.insurance.underwriting.infra.staff.StaffUnderwritingSettings;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.core.context.PrincipalFactory;
import org.fireflyframework.security.spi.AuthorityMappingPort;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/** Projection tests use already-decoded controlled JWTs; native signature coverage is separate. */
class StaffUnderwritingJwtConverterTest {
    private static final Instant NOW=Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID STAFF=UUID.randomUUID();
    private static final String SCOPE="insurance:underwriting:referrals:create";
    private final StaffUnderwritingSettings settings=new StaffUnderwritingSettings(true,URI.create("https://owner.invalid"),"https://staff.invalid",URI.create("https://staff.invalid/jwks"),"domain","core","experience-client","domain-client","exchange","opaque-tenant",false);
    private StaffUnderwritingJwtConverterImpl converter(){var mapper=mock(AuthorityMappingPort.class);when(mapper.mapAuthorities(anyMap())).thenReturn(Set.of("ROLE_STAFF"));when(mapper.mapScopes(anyMap())).thenReturn(Set.of(SCOPE));return new StaffUnderwritingJwtConverterImpl(settings,new PrincipalFactory(mapper),Clock.fixed(NOW,ZoneOffset.UTC));}
    private Jwt token(Map<String,Object> overrides){var builder=Jwt.withTokenValue("controlled-decoded-token").header("alg","RS256").issuer(settings.issuer()).subject("opaque-staff-subject").audience(List.of("domain")).notBefore(NOW.minusSeconds(1)).expiresAt(NOW.plusSeconds(60))
        .claim("principal_kind","STAFF").claim("client_id","experience-client").claim("tenant_id","opaque-tenant").claim("staff_actor_id",STAFF.toString()).claim("scope",SCOPE);
        builder.claims(claims->claims.putAll(overrides));return builder.build();}
    @Test void preservesSignedSubjectOpaqueTenantAndStaffProjection(){var jwt=token(Map.of());var authentication=converter().convert(jwt).block();var principal=(SecurityPrincipal)authentication.getPrincipal();assertEquals(jwt.getClaims(),principal.claims());assertEquals("opaque-staff-subject",principal.subject());assertEquals("opaque-tenant",principal.tenantId());assertEquals(STAFF.toString(),principal.attributes().get("staffActorId"));}
    @Test void humanCannotAcquireStaffAuthority(){assertThrows(RuntimeException.class,()->converter().convert(token(Map.of("principal_kind","HUMAN"))).block());}
    @Test void anotherRegisteredClientCannotUseTheStaffReceiver(){assertThrows(RuntimeException.class,()->converter().convert(token(Map.of("client_id","another-client"))).block());}
    @Test void opaqueTenantAndCanonicalStaffIdentityAreRequired(){for(var bad:List.of(Map.<String,Object>of("tenant_id","another-tenant"),Map.<String,Object>of("staff_actor_id",new UUID(0,0).toString()),Map.<String,Object>of("staff_actor_id","not-a-staff-id")))assertThrows(RuntimeException.class,()->converter().convert(token(bad)).block());}
    @Test void expiredOrNotYetValidTokenCannotCreateANativeStaffProjection(){for(var bad:List.of(Map.<String,Object>of("exp",NOW),Map.<String,Object>of("nbf",NOW.plusSeconds(1))))assertThrows(RuntimeException.class,()->converter().convert(token(bad)).block());}
}
