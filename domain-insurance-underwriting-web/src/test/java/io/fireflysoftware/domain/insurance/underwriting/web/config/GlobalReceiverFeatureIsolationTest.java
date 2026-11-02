/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.web.config;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.fireflyframework.security.api.domain.Decision;
import org.fireflyframework.security.core.policy.PolicyRule;
import org.fireflyframework.security.rs.ResourceServerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

/** Genuine global auto-configuration and native signatures against actual application YAML; no financial Main acceptance. */
class GlobalReceiverFeatureIsolationTest {
    static final String ISSUER="https://receiver.invalid",SUBJECT="opaque:receiver-human",TENANT="receiver-tenant",PATH="/unrelated-receiver-probe";
    static final KeyPair KEYS=keys();
    private ReactiveWebApplicationContextRunner receiver(String profile,boolean staff){return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ResourceServerAutoConfiguration.class))
        .withUserConfiguration(ReceiverFixtureImpl.class,GovernedQuoteSecurityConfiguration.class,StaffUnderwritingSecurityConfiguration.class)
        .withBean(io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteCoreProperties.class,()->new io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteCoreProperties("https://quotes.invalid","quote","https://quote-issuer.invalid","https://quote-issuer.invalid/jwks","quote-domain","quote-core","tenant_id","client_id","quote-domain",false))
        .withBean(io.fireflysoftware.domain.insurance.underwriting.infra.staff.StaffUnderwritingSettings.class,()->new io.fireflysoftware.domain.insurance.underwriting.infra.staff.StaffUnderwritingSettings(staff,java.net.URI.create("https://owner.invalid"),"https://staff-issuer.invalid",java.net.URI.create("https://staff-issuer.invalid/jwks"),"staff-domain","staff-core","exp-terminal","domain-underwriting","staff-exchange","staff-tenant",false))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active="+profile,"insurance.underwriting.staff-client.enabled="+staff,
            "firefly.security.resource-server.issuer="+ISSUER,"firefly.security.resource-server.audiences[0]=receiver",
            "firefly.security.resource-server.tenant-required=true","firefly.security.resource-server.tenant-claim-paths[0]=tenant_id",
            "firefly.security.resource-server.scope-claim-paths[0]=scope","firefly.security.resource-server.strict-policy=true");}
    @Test void disabledStaffBridgePreservesActualGlobalChainAndNativeSignatures(){
        for(String profile:List.of("default","dev","testing","prod"))receiver(profile,false).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(SecurityWebFilterChain.class).hasSingleBean(ReactiveJwtDecoder.class)
                .hasSingleBean(org.fireflyframework.security.core.context.PrincipalFactory.class);
            assertThat(context).hasBean("fireflySecurityWebFilterChain").hasBean("staffUnderwritingSecurity").hasBean("governedQuoteCallerSecurity");
            var client=WebTestClient.bindToApplicationContext(context).build();
            client.get().uri(PATH).exchange().expectStatus().isUnauthorized();
            client.get().uri(PATH).headers(headers->headers.setBearerAuth(token())).exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("signed-receiver");
        });
    }
    @Test void enabledScopedReceiversCannotSuppressActualGlobalAuthentication(){
        receiver("prod",true).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(SecurityWebFilterChain.class).hasSingleBean(ReactiveJwtDecoder.class)
                .hasSingleBean(org.fireflyframework.security.core.context.PrincipalFactory.class);
            assertThat(context).hasBean("fireflySecurityWebFilterChain").hasBean("staffUnderwritingSecurity").hasBean("governedQuoteCallerSecurity");
            var client=WebTestClient.bindToApplicationContext(context).build();
            client.get().uri(PATH).exchange().expectStatus().isUnauthorized();
            String signed=token();String[] parts=signed.split("\\.",-1);parts[2]=(parts[2].charAt(0)=='A'?"B":"A")+parts[2].substring(1);
            client.get().uri(PATH).headers(headers->headers.setBearerAuth(String.join(".",parts))).exchange().expectStatus().isUnauthorized();
        });
    }
    @Test void onlyTheExplicitReceiverPropertyCanDisableItsAutoConfiguration(){
        receiver("prod",true).withPropertyValues("firefly.security.resource-server.enabled=false").run(context->
            assertThat(context).hasNotFailed().doesNotHaveBean(SecurityWebFilterChain.class));
    }
    static KeyPair keys(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(GeneralSecurityException error){throw new IllegalStateException(error);}}
    static String token(){var jwk=new RSAKey.Builder((RSAPublicKey)KEYS.getPublic()).privateKey((RSAPrivateKey)KEYS.getPrivate()).keyID("receiver-regression").build();
        var encoder=new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));var now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer(ISSUER).subject(SUBJECT).audience(List.of("receiver")).issuedAt(now).notBefore(now.minusSeconds(1)).expiresAt(now.plusSeconds(60))
            .claim("principal_kind","HUMAN").claim("tenant_id",TENANT).claim("scope","receiver:read").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("receiver-regression").build(),claims)).getTokenValue();
    }
    @TestConfiguration(proxyBeanMethods=false) @EnableWebFlux static class ReceiverFixtureImpl {
        @Bean ReactiveJwtDecoder nativeReceiverDecoder(){var decoder=NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey)KEYS.getPublic()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            OAuth2TokenValidator<Jwt> audience=jwt->List.of("receiver").equals(jwt.getAudience())?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER),audience));return decoder;}
        @Bean PolicyRule nativeReceiverRule(){return (actor,action,resource,facts)->Mono.just(SUBJECT.equals(actor.subject())&&ISSUER.equals(actor.issuer())&&TENANT.equals(actor.tenantId())&&actor.hasScope("receiver:read")&&action.equals("GET")&&resource.equals(PATH)?Decision.permit():Decision.deny("receiver regression denied"));}
        @Bean ReceiverControllerImpl receiverController(){return new ReceiverControllerImpl();}
    }
    @RestController static class ReceiverControllerImpl {@GetMapping(value=PATH,produces=MediaType.TEXT_PLAIN_VALUE) Mono<String> unrelated(){return Mono.just("signed-receiver");}}
}
