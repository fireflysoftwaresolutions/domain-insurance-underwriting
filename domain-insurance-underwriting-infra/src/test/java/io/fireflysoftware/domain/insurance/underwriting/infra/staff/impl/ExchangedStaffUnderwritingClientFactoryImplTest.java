/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra.staff.impl;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.domain.insurance.underwriting.infra.staff.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.fireflyframework.security.core.context.PrincipalFactory;
import org.fireflyframework.security.spi.AuthorityMappingPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/** Real loopback token HTTP and native RSA decoding; the authorization issuer is controlled. */
class ExchangedStaffUnderwritingClientFactoryImplTest {
    private static final String ISSUER="https://controlled-staff.invalid", SCOPE="insurance:underwriting:referrals:create";
    private static final String STAFF="019a3497-6099-789a-9234-c34d4549e128";
    private static final Instant NOW=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final Duration LIMIT=Duration.ofSeconds(10);
    private static final KeyPair KEY=key();
    private static KeyPair key(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    private static String signed(boolean original,Map<String,Object> changes,KeyPair key) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).subject("original-staff-subject").audience(original?"domain":"core")
            .notBeforeTime(Date.from(NOW.minusSeconds(1))).expirationTime(Date.from(NOW.plusSeconds(original?120:60)))
            .claim("principal_kind","STAFF").claim("staff_actor_id",STAFF).claim("tenant_id","opaque-tenant")
            .claim("client_id",original?"experience-client":"domain-client").claim("scope",SCOPE);
        changes.forEach(claims::claim);
        var token=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims.build());token.sign(new RSASSASigner(key.getPrivate()));return token.serialize();
    }
    private static ReactiveJwtDecoder decoder(String audience){
        var decoder=NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey)KEY.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER),new JwtTimestampValidator(Duration.ZERO),jwt->List.of(audience).equals(jwt.getAudience())?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));return decoder;
    }
    private static JwtAuthenticationToken original() throws Exception {
        var jwt=decoder("domain").decode(signed(true,Map.of(),KEY)).block(LIMIT);assertNotNull(jwt);
        var mapper=mock(AuthorityMappingPort.class);when(mapper.mapAuthorities(anyMap())).thenReturn(Set.of("ROLE_STAFF"));when(mapper.mapScopes(anyMap())).thenReturn(Set.of(SCOPE));
        var principal=new PrincipalFactory(mapper).fromClaims(jwt.getSubject(),ISSUER,jwt.getClaims()).toBuilder()
            .tenantId("opaque-tenant").attributes(Map.of("staffActorId",STAFF)).build();
        return new JwtAuthenticationToken(jwt,principal,List.of(new SimpleGrantedAuthority("ROLE_STAFF")));
    }
    private static Map<String,String> form(String body){var result=new HashMap<String,String>();for(String entry:body.split("&")){var parts=entry.split("=",2);assertEquals(2,parts.length);assertNull(result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8)));}return result;}
    private static String response(String token){return "{\"access_token\":\""+token+"\",\"token_type\":\"Bearer\",\"expires_in\":60,\"scope\":\""+SCOPE+"\",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}";}
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;final AtomicInteger calls=new AtomicInteger();final AtomicReference<String> body=new AtomicReference<>(),authorization=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicLong nanos=new AtomicLong();final String clientCredential=UUID.randomUUID().toString();
        final StaffUnderwritingSettings settings;String reply;Runnable during=()->{};
        Fixture(String reply) throws Exception {
            this.reply=reply;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/token",request->{try{
                calls.incrementAndGet();assertEquals("POST",request.getRequestMethod());authorization.set(request.getRequestHeaders().getFirst("Authorization"));
                byte[] input=request.getRequestBody().readNBytes(32769);assertTrue(input.length<=32768);body.set(new String(input,StandardCharsets.UTF_8));during.run();
                byte[] output=this.reply.getBytes(StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,output.length);request.getResponseBody().write(output);
            }catch(Throwable error){failure.set(error);}finally{request.close();}});server.start();
            settings=new StaffUnderwritingSettings(true,URI.create("https://owner.invalid"),ISSUER,URI.create(ISSUER+"/jwks"),"domain","core","experience-client","domain-client","exchange","opaque-tenant",true);
        }
        ExchangedStaffUnderwritingClientFactoryImpl owner(){var registration=ClientRegistration.withRegistrationId("exchange").clientId("domain-client").clientSecret(clientCredential)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).issuerUri(ISSUER)
            .tokenUri("http://127.0.0.1:"+server.getAddress().getPort()+"/token").scope(SCOPE).build();
            return new ExchangedStaffUnderwritingClientFactoryImpl(settings,new InMemoryReactiveClientRegistrationRepository(registration),decoder("core"),new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()),Clock.systemUTC(),nanos::get);
        }
        StaffUnderwritingClientFactory.Client acquire(JwtAuthenticationToken original){return owner().current("Bearer "+original.getToken().getTokenValue(),Set.of(SCOPE)).contextWrite(ReactiveSecurityContextHolder.withAuthentication(original)).block(LIMIT);}
        @Override public void close(){server.stop(0);assertNull(failure.get(),()->"Controlled token endpoint failed: "+failure.get());}
    }
    @Test void nativeGrantUsesOriginalStaffTokenExactAudienceAndNarrowScope() throws Exception {
        var original=original();try(var fixture=new Fixture(response(signed(false,Map.of(),KEY)))){
            var lease=fixture.acquire(original);assertNotNull(lease);assertNotNull(lease.api());assertEquals(1,fixture.calls.get());
            var fields=form(fixture.body.get());assertEquals("urn:ietf:params:oauth:grant-type:token-exchange",fields.get("grant_type"));
            assertEquals(original.getToken().getTokenValue(),fields.get("subject_token"));assertEquals("urn:ietf:params:oauth:token-type:jwt",fields.get("subject_token_type"));
            assertEquals("core",fields.get("audience"));assertEquals(SCOPE,fields.get("scope"));assertFalse(fields.containsKey("actor_token"));
            assertEquals("Basic "+Base64.getEncoder().encodeToString(("domain-client:"+fixture.clientCredential).getBytes(StandardCharsets.UTF_8)),fixture.authorization.get());
            assertFalse(lease.remaining().isNegative());assertTrue(lease.remaining().compareTo(Duration.ofSeconds(5))<=0);
        }
    }
    @Test void changedSignedStaffTenantSubjectClientAudienceOrValidityIsRefused() throws Exception {
        var original=original();for(var changes:List.of(Map.<String,Object>of("staff_actor_id",UUID.randomUUID().toString()),Map.<String,Object>of("tenant_id","other-tenant"),Map.<String,Object>of("sub","other-subject"),Map.<String,Object>of("client_id","other-client"),Map.<String,Object>of("aud",List.of("core","other")),Map.<String,Object>of("exp",Date.from(NOW.plusSeconds(240))),Map.<String,Object>of("nbf",Date.from(NOW.minusSeconds(2))),Map.<String,Object>of("scope",SCOPE+" other:scope"),Map.<String,Object>of("principal_kind","HUMAN"))){
            try(var fixture=new Fixture(response(signed(false,changes,KEY)))){assertThrows(ResponseStatusException.class,()->fixture.acquire(original));assertEquals(1,fixture.calls.get());}
        }
    }
    @Test void invalidTargetSignatureNeverCreatesOwnerClient() throws Exception {var original=original();try(var fixture=new Fixture(response(signed(false,Map.of(),key())))){assertThrows(ResponseStatusException.class,()->fixture.acquire(original));assertEquals(1,fixture.calls.get());}}
    @Test void originalMonotonicBudgetCannotRenewAfterExchange() throws Exception {var original=original();try(var fixture=new Fixture(response(signed(false,Map.of(),KEY)))){fixture.during=()->fixture.nanos.set(Duration.ofSeconds(5).toNanos());assertThrows(ResponseStatusException.class,()->fixture.acquire(original));assertEquals(1,fixture.calls.get());}}
    @Test void replacedCurrentAuthenticationRefusesTheClientAfterExchange() throws Exception {
        var original=original();var context=new SecurityContextImpl(original);try(var fixture=new Fixture(response(signed(false,Map.of(),KEY)))){
            fixture.during=()->context.setAuthentication(null);assertThrows(ResponseStatusException.class,()->fixture.owner().current("Bearer "+original.getToken().getTokenValue(),Set.of(SCOPE))
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context))).block(LIMIT));assertEquals(1,fixture.calls.get());
        }
    }
    @Test void leaseRefusesAuthenticationReplacementAndOriginalDeadlineExpiry() throws Exception {
        var original=original();try(var fixture=new Fixture(response(signed(false,Map.of(),KEY)))){var lease=fixture.acquire(original);assertNotNull(lease);
            var replacement=original();assertThrows(ResponseStatusException.class,()->lease.revalidate().contextWrite(ReactiveSecurityContextHolder.withAuthentication(replacement)).block(LIMIT));
            fixture.nanos.set(Duration.ofSeconds(5).toNanos());assertThrows(ResponseStatusException.class,()->lease.revalidate().contextWrite(ReactiveSecurityContextHolder.withAuthentication(original)).block(LIMIT));assertEquals(1,fixture.calls.get());
        }
    }
    @Test void unsignedMissingOrMismatchedCallerCannotReachTokenEndpoint() throws Exception {
        var original=original();try(var fixture=new Fixture(response(signed(false,Map.of(),KEY)))){
            assertThrows(ResponseStatusException.class,()->fixture.owner().current("Bearer "+original.getToken().getTokenValue(),Set.of(SCOPE)).block(LIMIT));
            assertThrows(ResponseStatusException.class,()->fixture.owner().current("Bearer different",Set.of(SCOPE)).contextWrite(ReactiveSecurityContextHolder.withAuthentication(original)).block(LIMIT));assertEquals(0,fixture.calls.get());
        }
    }
    @Test void missingIssuedAccessTokenTypeAndOAuthErrorCannotAuthorizeOwner() throws Exception {
        var original=original();String reply=response(signed(false,Map.of(),KEY));for(String invalid:List.of(reply.replace(",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"",""),reply.replace("access_token\"}","jwt\"}"),reply.substring(0,reply.length()-1)+",\"error\":\"invalid_grant\"}")){
            try(var fixture=new Fixture(invalid)){assertThrows(ResponseStatusException.class,()->fixture.acquire(original));assertEquals(1,fixture.calls.get());}
        }
    }
}
