/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra.impl;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteCoreProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.fireflyframework.security.rs.AccessTokenValidators;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.security.core.context.*;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveTokenExchangeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import static org.junit.jupiter.api.Assertions.*;

class GovernedQuoteClientFactoryImplTest {
    private static final String ISSUER="https://synthetic-issuer.invalid",CLIENT="synthetic-domain-exchange";
    private static final String SCOPE="insurance:quotes:history:read";
    private static final Instant NOW=Instant.now();
    private static final Duration LIMIT=Duration.ofSeconds(5);
    private static final KeyPair KEY=key();
    private static KeyPair key(){try {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception error){throw new ExceptionInInitializerError(error);}}
    private static GovernedQuoteCoreProperties properties(){return new GovernedQuoteCoreProperties("https://synthetic-core.invalid","quote-exchange",ISSUER,
            ISSUER+"/jwks","synthetic-domain","synthetic-core","tenant_id","azp",CLIENT,false);}
    private static ClientRegistration registration(){return ClientRegistration.withRegistrationId("quote-exchange").clientId(CLIENT).clientSecret("synthetic-only-secret")
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .issuerUri(ISSUER).tokenUri(ISSUER+"/token").scope(SCOPE).build();}
    private static NimbusReactiveJwtDecoder decoder(String audience){var result=NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey)KEY.getPublic()).build();
        result.setJwtValidator(AccessTokenValidators.forIssuerAndAudience(ISSUER,audience));return result;}
    private static String signed(String subject,String audience,String tenant,String party,Instant expiry,KeyPair key) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(audience.equals("synthetic-domain")?List.of("synthetic-domain","synthetic-exp"):List.of(audience)).issueTime(Date.from(NOW.minusSeconds(1)))
                .expirationTime(Date.from(expiry)).claim("principal_kind","HUMAN").claim("tenant_id",tenant).claim("party_id",party)
                .claim("scope",SCOPE).claim("azp",CLIENT).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),claims);jwt.sign(new RSASSASigner(key.getPrivate()));return jwt.serialize();
    }
    private static String changed(String token,String claim,Object value) throws Exception {
        var claims=new JWTClaimsSet.Builder(SignedJWT.parse(token).getJWTClaimsSet()).claim(claim,value).build();
        var result=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),claims);
        result.sign(new RSASSASigner(KEY.getPrivate()));return result.serialize();
    }
    private static Jwt caller(String subject) throws Exception {return decoder("synthetic-domain").decode(signed(subject,"synthetic-domain","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(60),KEY)).block(LIMIT);}
    private static JwtAuthenticationToken authentication(Jwt jwt){return new JwtAuthenticationToken(jwt,List.of(),jwt.getSubject());}
    private static String response(String token){return "{\"access_token\":\""+token+"\",\"token_type\":\"Bearer\",\"expires_in\":30,\"scope\":\""+SCOPE+"\",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}";}
    private static WebClientReactiveTokenExchangeTokenResponseClient nativeClient(ExchangeFunction exchange){return GovernedQuoteClientFactoryImpl.tokenClient(WebClient.builder().exchangeFunction(exchange).build());}
    private static GovernedQuoteClientFactoryImpl factory(WebClientReactiveTokenExchangeTokenResponseClient exchange,WebClient transport,java.util.function.LongSupplier nanos){
        return new GovernedQuoteClientFactoryImpl(properties(),new InMemoryReactiveClientRegistrationRepository(registration()),exchange,decoder("synthetic-core"),transport,Clock.systemUTC(),nanos);
    }
    private static Map<String,String> form(String value){Map<String,String> result=new HashMap<>();for(String pair:value.split("&")){
        String[] pieces=pair.split("=",2);result.put(URLDecoder.decode(pieces[0],StandardCharsets.UTF_8),URLDecoder.decode(pieces[1],StandardCharsets.UTF_8));}return result;}


    @Test void unselectedFactoryNeedsNoClientAndInvalidSelectionCannotChooseALegacyEndpoint() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(GovernedQuoteClientFactoryImpl.class);context.refresh();
            assertEquals(0,context.getBeanNamesForType(GovernedQuoteClientFactoryImpl.class).length);
        }
        var configured=properties();
        var missing=new GovernedQuoteCoreProperties(null,configured.registrationId(),configured.issuer(),configured.jwkSetUri(),
                configured.domainAudience(),configured.coreAudience(),configured.tenantClaim(),configured.delegatingClientClaim(),configured.delegatingClientId(),false);
        assertThrows(IllegalArgumentException.class,()->new GovernedQuoteClientFactoryImpl(missing,new InMemoryReactiveClientRegistrationRepository(registration())));
    }

    @Test void nativeGrantUsesTheVerifiedSubjectAndRequestedAudienceRatherThanMachineCredentialsAsSubject() throws Exception {
        Jwt original=caller("synthetic-subject");assertEquals(2,original.getAudience().size());String exchanged=signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY);
        var grants=new AtomicInteger();var sdkCalls=new AtomicInteger();
        var exchange=nativeClient(request->{
            grants.incrementAndGet();assertEquals(ISSUER+"/token",request.url().toString());assertEquals(HttpMethod.POST,request.method());
            assertEquals("Basic "+Base64.getEncoder().encodeToString((CLIENT+":synthetic-only-secret").getBytes(StandardCharsets.UTF_8)),request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            var target=new MockClientHttpRequest(request.method(),request.url());
            return request.writeTo(target,ExchangeStrategies.withDefaults()).then(Mono.defer(target::getBodyAsString)).map(body->{
                var fields=form(body);assertEquals("urn:ietf:params:oauth:grant-type:token-exchange",fields.get("grant_type"));
                assertEquals(original.getTokenValue(),fields.get("subject_token"));assertEquals("urn:ietf:params:oauth:token-type:jwt",fields.get("subject_token_type"));
                assertEquals("synthetic-core",fields.get("audience"));assertEquals(SCOPE,fields.get("scope"));assertFalse(fields.containsKey("actor_token"));
                return ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(response(exchanged)).build();
            });
        });
        var transport=ApiClient.buildWebClientBuilder().exchangeFunction(request->{sdkCalls.incrementAndGet();assertEquals("Bearer "+exchanged,request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("{\"entries\":[]}").build());}).build();
        var value=factory(exchange,transport,System::nanoTime).invoke("Bearer "+original.getTokenValue(),Set.of(SCOPE),api->api.historyWithHttpInfo(new UUID(0,1),new UUID(0,2),new UUID(0,3),new UUID(0,4)))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(original))).block(LIMIT);
        assertNotNull(value);assertTrue(value.getBody().getEntries().isEmpty());assertEquals(1,grants.get());assertEquals(1,sdkCalls.get());
        assertNull(original.getNotBefore());assertNull(decoder("synthetic-core").decode(exchanged).block(LIMIT).getNotBefore());
    }


    @Test void concurrentSubjectsReceiveTheirOwnExchangedCredentialsWithoutAuthorizedClientCaching() throws Exception {
        Jwt one=caller("synthetic-one"),two=caller("synthetic-two");
        String first=signed(one.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY);
        String second=signed(two.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY);
        var values=Map.of(one.getTokenValue(),first,two.getTokenValue(),second);var grants=new AtomicInteger();Sinks.Empty<Void> both=Sinks.empty();
        var exchange=nativeClient(request->{
            var target=new MockClientHttpRequest(request.method(),request.url());
            return request.writeTo(target,ExchangeStrategies.withDefaults()).then(Mono.defer(target::getBodyAsString)).flatMap(body->{
                String result=values.get(form(body).get("subject_token"));assertNotNull(result);if(grants.incrementAndGet()==2)both.tryEmitEmpty();
                return both.asMono().thenReturn(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(response(result)).build());
            });
        });
        var seen=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var transport=ApiClient.buildWebClientBuilder().exchangeFunction(request->{seen.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("{\"entries\":[]}").build());}).build();
        var owner=factory(exchange,transport,System::nanoTime);UUID id=new UUID(0,1);
        Mono.when(owner.invoke("Bearer "+one.getTokenValue(),Set.of(SCOPE),api->api.historyWithHttpInfo(id,id,id,id))
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(one))),
                owner.invoke("Bearer "+two.getTokenValue(),Set.of(SCOPE),api->api.historyWithHttpInfo(id,id,id,id))
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(two)))).block(LIMIT);
        assertEquals(2,grants.get());assertEquals(Set.of("Bearer "+first,"Bearer "+second),seen);
    }

    @Test void unverifiedOrMismatchedCallerCannotReachTheTokenEndpoint() throws Exception {
        Jwt original=caller("synthetic-subject");var calls=new AtomicInteger();
        var exchange=nativeClient(request->{calls.incrementAndGet();return Mono.error(new AssertionError("No exchange expected"));});
        var owner=factory(exchange,ApiClient.buildWebClientBuilder().build(),System::nanoTime);
        assertThrows(ResponseStatusException.class,()->owner.invoke("Bearer "+original.getTokenValue(),Set.of(SCOPE),api->Mono.just("unexpected")).block(LIMIT));
        assertThrows(ResponseStatusException.class,()->owner.invoke("Bearer other",Set.of(SCOPE),api->Mono.just("unexpected"))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(original))).block(LIMIT));
        assertEquals(0,calls.get());
    }

    @Test void changedTenantPartySubjectAudienceExpiryOrSignatureNeverCreatesAClient() throws Exception {
        Jwt original=caller("synthetic-subject");
        List<String> tokens=List.of(signed("other-subject","synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY),
                signed(original.getSubject(),"synthetic-core","other-tenant","synthetic-party",NOW.plusSeconds(30),KEY),
                signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","other-party",NOW.plusSeconds(30),KEY),
                signed(original.getSubject(),"wrong-audience","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY),
                signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(120),KEY),
                signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),key()),
                changed(signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY),"scope",SCOPE+" insurance:quotes:offer"),
                changed(signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY),"azp","other-client"),
                changed(signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY),"principal_kind","MACHINE"));
        for(String token:tokens) {
            var owner=factory(nativeClient(request->Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(response(token)).build())),
                    ApiClient.buildWebClientBuilder().build(),System::nanoTime);var invoked=new AtomicBoolean();
            assertThrows(ResponseStatusException.class,()->owner.invoke("Bearer "+original.getTokenValue(),Set.of(SCOPE),api->{invoked.set(true);return Mono.just("unexpected");})
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(original))).block(LIMIT));assertFalse(invoked.get());
        }
    }

    @Test void originalFiveSecondClockCannotRenewAfterTokenLookup() throws Exception {
        Jwt original=caller("synthetic-subject");String token=signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY);
        var nanos=new AtomicLong();var invoked=new AtomicBoolean();
        var owner=factory(nativeClient(request->{nanos.set(Duration.ofSeconds(5).toNanos());return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(response(token)).build());}),
                ApiClient.buildWebClientBuilder().build(),nanos::get);
        assertThrows(ResponseStatusException.class,()->owner.invoke("Bearer "+original.getTokenValue(),Set.of(SCOPE),api->{invoked.set(true);return Mono.just("unexpected");})
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(original))).block(LIMIT));assertFalse(invoked.get());
    }

    @Test void authenticatedCallerChangeDuringExchangeRefusesBeforeTheOwnerCall() throws Exception {
        Jwt original=caller("synthetic-subject");String token=signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY);
        var context=new SecurityContextImpl(authentication(original));var invoked=new AtomicBoolean();
        var owner=factory(nativeClient(request->{context.setAuthentication(null);return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(response(token)).build());}),
                ApiClient.buildWebClientBuilder().build(),System::nanoTime);
        assertThrows(ResponseStatusException.class,()->owner.invoke("Bearer "+original.getTokenValue(),Set.of(SCOPE),api->{invoked.set(true);return Mono.just("unexpected");})
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context))).block(LIMIT));assertFalse(invoked.get());
    }

    @Test void missingWrongOrNonStringIssuedTokenTypeAndOAuthErrorsCannotReachTheOwner() throws Exception {
        Jwt original=caller("synthetic-subject");
        String token=signed(original.getSubject(),"synthetic-core","opaque-platform-tenant","synthetic-party",NOW.plusSeconds(30),KEY);
        String parameter=",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"";
        for(String replacement:List.of("",",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:jwt\"",",\"issued_token_type\":42",
                parameter+",\"error\":\"invalid_grant\"")) {
            var grants=new AtomicInteger();var invoked=new AtomicBoolean();
            var exchange=nativeClient(request->{grants.incrementAndGet();return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type","application/json").body(response(token).replace(parameter,replacement)).build());});
            var owner=factory(exchange,ApiClient.buildWebClientBuilder().build(),System::nanoTime);
            var failure=assertThrows(ResponseStatusException.class,()->owner.invoke("Bearer "+original.getTokenValue(),Set.of(SCOPE),api->{invoked.set(true);return Mono.just("unexpected");})
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(original))).block(LIMIT));
            assertEquals(502,failure.getStatusCode().value());assertEquals(1,grants.get());assertFalse(invoked.get());
        }
    }
}
