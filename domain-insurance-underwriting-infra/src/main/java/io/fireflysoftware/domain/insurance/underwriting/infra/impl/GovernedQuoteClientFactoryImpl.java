/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra.impl;

import io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteCoreProperties;
import io.fireflysoftware.domain.insurance.underwriting.infra.GovernedQuoteClientFactory;

import io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedQuoteOffersApi;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.fireflyframework.security.rs.AccessTokenValidators;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.codec.Hints;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyExtractor;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Native caller-subject exchange; no authorized-client cache or machine-subject fallback. */
@Component
@ConditionalOnProperty(prefix="insurance.quotes.governed-client",name="enabled",havingValue="true")
public final class GovernedQuoteClientFactoryImpl implements GovernedQuoteClientFactory {
    private final GovernedQuoteCoreProperties properties;
    private final ReactiveClientRegistrationRepository registrations;
    private final WebClientReactiveTokenExchangeTokenResponseClient exchange;
    private final ReactiveJwtDecoder decoder;
    private final WebClient transport;
    private final Clock clock;
    private final LongSupplier nanos;

    @Autowired
    public GovernedQuoteClientFactoryImpl(GovernedQuoteCoreProperties properties,ReactiveClientRegistrationRepository registrations) {
        this(validated(properties),registrations,tokenClient(),ownerDecoder(properties),
                ApiClient.buildWebClientBuilder().clientConnector(connector()).codecs(codecs->codecs.defaultCodecs().maxInMemorySize(1024*1024)).build(),
                Clock.systemUTC(),System::nanoTime);
    }

    GovernedQuoteClientFactoryImpl(GovernedQuoteCoreProperties properties,ReactiveClientRegistrationRepository registrations,
            WebClientReactiveTokenExchangeTokenResponseClient exchange,ReactiveJwtDecoder decoder,WebClient transport,Clock clock,LongSupplier nanos) {
        this.properties=Objects.requireNonNull(properties);this.registrations=Objects.requireNonNull(registrations);
        endpoint(properties.basePath(),properties.allowLoopback());endpoint(properties.issuer(),properties.allowLoopback());
        endpoint(properties.jwkSetUri(),properties.allowLoopback());
        for(String value:List.of(required(properties.registrationId()),required(properties.domainAudience()),required(properties.coreAudience()),
                required(properties.delegatingClientId())))if(value.length()>255)throw invalid();
        for(String claim:List.of(required(properties.tenantClaim()),required(properties.delegatingClientClaim())))
            if(!claim.matches("[A-Za-z][A-Za-z0-9_:.]{0,63}"))throw invalid();
        if(properties.tenantClaim().equals(properties.delegatingClientClaim())
                || Set.of("sub","iss","exp","nbf","aud","party_id","principal_kind","scope","scp").contains(properties.tenantClaim())
                || Set.of("sub","iss","exp","nbf","aud","party_id","principal_kind","scope","scp").contains(properties.delegatingClientClaim()))throw invalid();
        this.exchange=Objects.requireNonNull(exchange);this.decoder=Objects.requireNonNull(decoder);this.transport=Objects.requireNonNull(transport);
        this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
        exchange.addParametersConverter(request->{var values=new LinkedMultiValueMap<String,String>();values.set("audience",properties.coreAudience());return values;});
    }

    public <T> Mono<T> invoke(String authorization,Set<String> requiredScopes,Function<GovernedQuoteOffersApi,Mono<T>> operation) {
        return Mono.defer(()->{
            long started=nanos.getAsLong();Set<String> scopes=Set.copyOf(requiredScopes);
            if(scopes.isEmpty() || !Set.of("insurance:quotes:offer","insurance:quotes:accept","insurance:quotes:read","insurance:quotes:history:read","insurance:quotes:recover").containsAll(scopes))return Mono.error(invalid());
            String bearer=bearer(authorization);
            Mono<Exchanged> admitted=ReactiveSecurityContextHolder.getContext().flatMap(context->Mono.justOrEmpty(context.getAuthentication()))
                .filter(authentication->authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated())
                .cast(JwtAuthenticationToken.class).switchIfEmpty(Mono.error(unauthorized())).flatMap(authentication->{
                    Jwt subject=authentication.getToken();
                    if(!bearer.equals(subject.getTokenValue()))return Mono.error(unauthorized());
                    Caller caller=caller(subject);
                    if(!subject.getAudience().contains(properties.domainAudience()) || !caller.scopes().containsAll(scopes))return Mono.error(forbidden());
                    return registrations.findByRegistrationId(properties.registrationId()).switchIfEmpty(Mono.error(unconfirmed())).flatMap(registration->{
                        registration(registration,scopes);remaining(started,caller.expiresAt());
                        var perOperation=ClientRegistration.withClientRegistration(registration).scope(scopes).build();
                        return exchange.getTokenResponse(new TokenExchangeGrantRequest(perOperation,subject,null)).switchIfEmpty(Mono.error(unconfirmed()))
                            .flatMap(response->{
                                remaining(started,caller.expiresAt());var token=response.getAccessToken();
                                if(token==null || !OAuth2AccessToken.TokenType.BEARER.equals(token.getTokenType()) || token.getTokenValue().isBlank()
                                        || !"urn:ietf:params:oauth:token-type:access_token".equals(response.getAdditionalParameters().get("issued_token_type")))return Mono.error(unconfirmed());
                                return decoder.decode(token.getTokenValue()).switchIfEmpty(Mono.error(unconfirmed())).map(exchanged->{
                                    Caller target=caller(exchanged);
                                    if(!token.getTokenValue().equals(exchanged.getTokenValue()) || !caller.issuer().equals(target.issuer())
                                            || !caller.subject().equals(target.subject()) || !caller.tenant().equals(target.tenant()) || !caller.party().equals(target.party())
                                            || target.expiresAt().isAfter(caller.expiresAt()) || !caller.scopes().containsAll(target.scopes()) || !target.scopes().containsAll(scopes)
                                            || !token.getScopes().isEmpty() && !token.getScopes().equals(target.scopes())
                                            || caller.notBefore()!=null && (target.notBefore()==null || target.notBefore().isBefore(caller.notBefore()))
                                            || exchanged.getAudience().size()!=1 || !properties.coreAudience().equals(exchanged.getAudience().getFirst())
                                            || !properties.delegatingClientId().equals(text(exchanged,properties.delegatingClientClaim())))throw forbidden();
                                    remaining(started,caller.expiresAt());
                                    return new Exchanged(authentication,caller,token.getTokenValue(),target.expiresAt());
                                });
                            });
                    });
                }).timeout(Duration.ofSeconds(2));
            return admitted.flatMap(accepted->sameCaller(accepted).then(Mono.defer(()->{
                remaining(started,accepted.until());
                var client=new ApiClient(transport).setBasePath(properties.basePath());client.setBearerToken(accepted.token());
                return Mono.defer(()->operation.apply(new GovernedQuoteOffersApi(client))).switchIfEmpty(Mono.error(unconfirmed()))
                        .timeout(remaining(started,accepted.until())).flatMap(result->sameCaller(accepted).onErrorMap(error->unconfirmed()).then(Mono.fromSupplier(()->{remaining(started,accepted.until());return result;})));
            }))).timeout(Duration.ofSeconds(5));
        }).onErrorMap(error->error instanceof ResponseStatusException response && Set.of(400,401,403,502).contains(response.getStatusCode().value())?error:unconfirmed());
    }

    private Mono<Void> sameCaller(Exchanged accepted) {
        return ReactiveSecurityContextHolder.getContext().filter(context->context.getAuthentication()==accepted.authentication()
                && accepted.authentication().isAuthenticated() && accepted.caller().equals(caller(accepted.authentication().getToken())))
                .switchIfEmpty(Mono.error(forbidden())).then();
    }
    private Caller caller(Jwt jwt) {
        Instant now=clock.instant(),expiry=jwt.getExpiresAt(),notBefore=jwt.getNotBefore();
        if(jwt.getAudience()==null || jwt.getAudience().isEmpty() || !properties.issuer().equals(jwt.getClaimAsString("iss")) || jwt.getSubject()==null || jwt.getSubject().isBlank()
                || !"HUMAN".equals(text(jwt,"principal_kind")) || expiry==null || !now.isBefore(expiry)
                || jwt.getClaims().containsKey("nbf") && (notBefore==null || now.isBefore(notBefore) || !notBefore.isBefore(expiry)))throw unauthorized();
        return new Caller(properties.issuer(),jwt.getSubject(),text(jwt,properties.tenantClaim()),text(jwt,"party_id"),expiry,notBefore,scopes(jwt),List.copyOf(jwt.getAudience()));
    }
    private void registration(ClientRegistration registration,Set<String> scopes) {
        endpoint(registration.getProviderDetails().getTokenUri(),properties.allowLoopback());
        if(!properties.registrationId().equals(registration.getRegistrationId()) || !AuthorizationGrantType.TOKEN_EXCHANGE.equals(registration.getAuthorizationGrantType())
                || !ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod())
                || registration.getClientSecret()==null || registration.getClientSecret().isBlank()
                || !properties.issuer().equals(registration.getProviderDetails().getIssuerUri())
                || !properties.delegatingClientId().equals(registration.getClientId()) || !registration.getScopes().containsAll(scopes))throw forbidden();
    }
    private Duration remaining(long started,Instant until) {
        long elapsed=nanos.getAsLong()-started;if(elapsed<0)throw unconfirmed();
        long left=Duration.ofSeconds(5).toNanos()-elapsed;Duration token=Duration.between(clock.instant(),until);
        if(left<=0 || token.isNegative() || token.isZero())throw unconfirmed();return token.compareTo(Duration.ofNanos(left))<0?token:Duration.ofNanos(left);
    }
    private static Set<String> scopes(Jwt jwt) {
        Set<String> scope=scopeValue(jwt.getClaims().get("scope")),scp=scopeValue(jwt.getClaims().get("scp"));
        if(jwt.getClaims().containsKey("scope") && jwt.getClaims().containsKey("scp") && !scope.equals(scp))throw forbidden();
        return jwt.getClaims().containsKey("scope")?scope:scp;
    }
    private static Set<String> scopeValue(Object value) {
        if(value==null)return Set.of();Collection<?> values=value instanceof String text?Arrays.asList(text.split(" ")):value instanceof Collection<?> list?list:null;
        if(values==null)throw forbidden();var result=new HashSet<String>();
        for(Object item:values) {if(!(item instanceof String text))throw forbidden();if(text.isEmpty())continue;
            if(text.chars().anyMatch(character->Character.isWhitespace(character) || Character.isISOControl(character)))throw forbidden();result.add(text);}
        return Set.copyOf(result);
    }
    private static String text(Jwt jwt,String claim) {Object value=jwt.getClaims().get(claim);if(!(value instanceof String text) || text.isBlank())throw forbidden();return text;}
    private static String bearer(String value) {if(value==null || value.length()<=7 || !value.regionMatches(true,0,"Bearer ",0,7))throw unauthorized();
        String token=value.substring(7);if(token.chars().anyMatch(character->Character.isWhitespace(character) || Character.isISOControl(character) || character==','))throw unauthorized();return token;}
    static URI endpoint(String value,boolean loopback) {try {URI uri=URI.create(value);
        if(uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.normalize().equals(uri)
                || !("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost())))throw invalid();return uri;
        }catch(RuntimeException invalid){throw invalid();}}
    private static GovernedQuoteCoreProperties validated(GovernedQuoteCoreProperties properties) {
        Objects.requireNonNull(properties);endpoint(properties.basePath(),properties.allowLoopback());endpoint(properties.issuer(),properties.allowLoopback());
        endpoint(properties.jwkSetUri(),properties.allowLoopback());
        required(properties.registrationId());required(properties.domainAudience());required(properties.coreAudience());
        required(properties.tenantClaim());required(properties.delegatingClientClaim());required(properties.delegatingClientId());return properties;
    }
    private static ReactiveJwtDecoder ownerDecoder(GovernedQuoteCoreProperties properties) {
        endpoint(properties.issuer(),properties.allowLoopback());endpoint(properties.jwkSetUri(),properties.allowLoopback());required(properties.coreAudience());
        var decoder=NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).webClient(quietTransport(1024*1024)).build();
        decoder.setJwtValidator(AccessTokenValidators.forIssuerAndAudience(properties.issuer(),properties.coreAudience()));return decoder;
    }
    private static WebClientReactiveTokenExchangeTokenResponseClient tokenClient() {
        return tokenClient(quietTransport(16384));
    }
    static WebClientReactiveTokenExchangeTokenResponseClient tokenClient(WebClient transport) {
        var client=new WebClientReactiveTokenExchangeTokenResponseClient();client.setWebClient(transport);
        var converter=new DefaultMapOAuth2AccessTokenResponseConverter();
        // Nimbus promotes issued_token_type out of custom parameters; preserve it with Spring's map converter.
        client.setBodyExtractor((message,context)->BodyExtractors.toMono(new ParameterizedTypeReference<Map<String,Object>>(){}).extract(message,new BodyExtractor.Context(){
            public List<HttpMessageReader<?>> messageReaders(){return context.messageReaders();}
            public Optional<ServerHttpResponse> serverResponse(){return context.serverResponse();}
            public Map<String,Object> hints(){var hints=new HashMap<String,Object>(context.hints());hints.put(Hints.SUPPRESS_LOGGING_HINT,true);return Map.copyOf(hints);}
        }).map(parameters->{
            if(parameters.containsKey("error"))throw unconfirmed();
            try {
                if(!com.nimbusds.oauth2.sdk.TokenResponse.parse(new net.minidev.json.JSONObject(parameters)).indicatesSuccess())throw unconfirmed();
            } catch(com.nimbusds.oauth2.sdk.ParseException invalid) {throw unconfirmed();}
            return converter.convert(parameters);
        }));return client;
    }
    private static WebClient quietTransport(int limit) {return WebClient.builder().clientConnector(connector()).codecs(codecs->{
        codecs.defaultCodecs().maxInMemorySize(limit);codecs.defaultCodecs().enableLoggingRequestDetails(false);})
            .filter((request,next)->next.exchange(request).flatMap(response->response.statusCode().is2xxSuccessful()?Mono.just(response):response.releaseBody().then(Mono.error(unconfirmed())))).build();}
    private static JdkClientHttpConnector connector() {return new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}}).build());}
    private static String required(String value){if(value==null || value.isBlank())throw invalid();return value;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Explicit caller-bound quote exchange configuration required");}
    private static ResponseStatusException unauthorized(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"VERIFIED_QUOTE_CALLER_REQUIRED");}
    private static ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"QUOTE_DELEGATION_REQUIRED");}
    private static ResponseStatusException unconfirmed(){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"QUOTE_OWNER_OUTCOME_UNCONFIRMED");}
    private record Caller(String issuer,String subject,String tenant,String party,Instant expiresAt,Instant notBefore,Set<String> scopes,List<String> audiences) {
        @Override public String toString(){return "Caller[redacted]";}
    }
    private record Exchanged(JwtAuthenticationToken authentication,Caller caller,String token,Instant until) {
        @Override public String toString(){return "Exchanged[redacted]";}
    }
    @Override public String toString(){return "GovernedQuoteClientFactoryImpl[redacted]";}
}
