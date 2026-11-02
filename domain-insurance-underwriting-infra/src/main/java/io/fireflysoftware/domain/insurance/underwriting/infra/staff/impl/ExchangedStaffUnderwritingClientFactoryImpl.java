/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra.staff.impl;

import io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedUnderwritingApi;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import io.fireflysoftware.domain.insurance.underwriting.infra.staff.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.codec.Hints;
import org.springframework.http.*;
import org.springframework.http.client.reactive.*;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(prefix="insurance.underwriting.staff-client",name="enabled",havingValue="true")
public final class ExchangedStaffUnderwritingClientFactoryImpl implements StaffUnderwritingClientFactory {
    private static final Set<String> MUTATIONS=Set.of("insurance:underwriting:referrals:create","insurance:underwriting:referrals:approve","insurance:underwriting:decisions:record");
    private static final Set<String> RECOVERY=Set.of("insurance:underwriting:actions:recover","insurance:underwriting:read");
    private final StaffUnderwritingSettings settings;
    private final ReactiveClientRegistrationRepository registrations;
    private final ReactiveJwtDecoder targetDecoder;
    private final WebClientReactiveTokenExchangeTokenResponseClient exchange;
    private final WebClient transport;
    private final Clock clock;
    private final LongSupplier nanos;

    @Autowired
    public ExchangedStaffUnderwritingClientFactoryImpl(StaffUnderwritingSettings settings,ReactiveClientRegistrationRepository registrations){
        this(settings,registrations,decoder(settings,settings.coreAudience()),new JdkClientHttpConnector(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build()),Clock.systemUTC(),System::nanoTime);
    }
    ExchangedStaffUnderwritingClientFactoryImpl(StaffUnderwritingSettings settings,ReactiveClientRegistrationRepository registrations,
            ReactiveJwtDecoder targetDecoder,ClientHttpConnector connector,Clock clock,LongSupplier nanos){
        this.settings=Objects.requireNonNull(settings);this.registrations=Objects.requireNonNull(registrations);this.targetDecoder=Objects.requireNonNull(targetDecoder);
        this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
        if(!settings.enabled())throw new IllegalArgumentException("STAFF underwriting delegation must be explicitly enabled");
        transport=ApiClient.buildWebClientBuilder().clientConnector(connector).codecs(c->{c.defaultCodecs().maxInMemorySize(65536);c.defaultCodecs().enableLoggingRequestDetails(false);}).build();
        var tokens=WebClient.builder().clientConnector(connector).codecs(c->{c.defaultCodecs().maxInMemorySize(16384);c.defaultCodecs().enableLoggingRequestDetails(false);})
            .filter((request,next)->next.exchange(request).flatMap(reply->reply.statusCode().is2xxSuccessful()?Mono.just(reply):reply.releaseBody().then(Mono.error(unavailable())))).build();
        exchange=new WebClientReactiveTokenExchangeTokenResponseClient();exchange.setWebClient(tokens);
        exchange.addParametersConverter(request->{var body=new LinkedMultiValueMap<String,String>();body.set("audience",settings.coreAudience());return body;});
        var converter=new DefaultMapOAuth2AccessTokenResponseConverter();
        exchange.setBodyExtractor((message,context)->BodyExtractors.toMono(new ParameterizedTypeReference<Map<String,Object>>(){}).extract(message,new BodyExtractor.Context(){
            public List<HttpMessageReader<?>> messageReaders(){return context.messageReaders();}
            public Optional<ServerHttpResponse> serverResponse(){return context.serverResponse();}
            public Map<String,Object> hints(){var copy=new HashMap<String,Object>(context.hints());copy.put(Hints.SUPPRESS_LOGGING_HINT,true);return Map.copyOf(copy);}
        }).map(parameters->{if(parameters.containsKey("error")||!"urn:ietf:params:oauth:token-type:access_token".equals(parameters.get("issued_token_type")))throw forbidden();return converter.convert(parameters);}));
    }
    @Override public Mono<Client> current(String authorization,Set<String> required){
        return Mono.defer(()->{
            Set<String> scopes=Set.copyOf(required);String token=bearer(authorization);
            if(!(scopes.size()==1&&MUTATIONS.containsAll(scopes))&&!scopes.equals(RECOVERY))throw forbidden();
            Instant observed=clock.instant();long started=nanos.getAsLong();
            return authentication().flatMap(original->{
                Jwt subject=original.getToken();original(original,token,scopes);remaining(started,observed,subject.getExpiresAt());
                return registrations.findByRegistrationId(settings.registrationId()).switchIfEmpty(Mono.error(unavailable())).flatMap(registration->{
                    registration(registration,scopes);var selected=ClientRegistration.withClientRegistration(registration).scope(scopes).build();
                    return same(original,token,scopes,started,observed).then(exchange.getTokenResponse(new TokenExchangeGrantRequest(selected,subject,null)))
                        .timeout(remaining(started,observed,subject.getExpiresAt())).switchIfEmpty(Mono.error(unavailable())).flatMap(response->{
                            var access=response.getAccessToken();
                            if(access==null||!OAuth2AccessToken.TokenType.BEARER.equals(access.getTokenType())||!scopes.equals(access.getScopes())
                                ||access.getTokenValue().isBlank()||access.getExpiresAt()==null||!clock.instant().isBefore(access.getExpiresAt()))throw forbidden();
                            return targetDecoder.decode(access.getTokenValue()).switchIfEmpty(Mono.error(forbidden())).flatMap(target->{
                                target(target,subject,scopes);if(!access.getTokenValue().equals(target.getTokenValue()))throw forbidden();
                                var client=new ApiClient(transport).setBasePath(settings.endpoint().toASCIIString().replaceFirst("/$",""));client.setBearerToken(access.getTokenValue());
                                Client lease=new CurrentClientImpl(original,target,scopes,started,observed,access.getExpiresAt(),new GovernedUnderwritingApi(client));
                                return lease.revalidate().thenReturn(lease);
                            });
                        });
                });
            }).timeout(Duration.ofSeconds(5));
        }).onErrorMap(error->error instanceof ResponseStatusException?error:unavailable());
    }
    private final class CurrentClientImpl implements Client {
        private final JwtAuthenticationToken original;private final Jwt target;private final Set<String> scopes;
        private final long started;private final Instant observed,accessExpiry;private final GovernedUnderwritingApi api;
        CurrentClientImpl(JwtAuthenticationToken original,Jwt target,Set<String> scopes,long started,Instant observed,Instant accessExpiry,GovernedUnderwritingApi api){this.original=original;this.target=target;this.scopes=scopes;this.started=started;this.observed=observed;this.accessExpiry=accessExpiry;this.api=api;}
        @Override public GovernedUnderwritingApi api(){return api;}
        @Override public Duration remaining(){return ExchangedStaffUnderwritingClientFactoryImpl.this.remaining(started,observed,earliest(accessExpiry,earliest(original.getToken().getExpiresAt(),target.getExpiresAt())));}
        @Override public Mono<Void> revalidate(){return Mono.defer(()->{target(target,original.getToken(),scopes);remaining();return same(original,original.getToken().getTokenValue(),scopes,started,observed);});}
        @Override public String toString(){return "CurrentStaffUnderwritingClient[redacted]";}
    }
    private Mono<JwtAuthenticationToken> authentication(){return ReactiveSecurityContextHolder.getContext().map(c->c.getAuthentication())
        .filter(value->value instanceof JwtAuthenticationToken&&value.isAuthenticated()).cast(JwtAuthenticationToken.class).switchIfEmpty(Mono.error(unauthorized()));}
    private Mono<Void> same(JwtAuthenticationToken expected,String bearer,Set<String> scopes,long started,Instant observed){
        return authentication().filter(current->current==expected).switchIfEmpty(Mono.error(forbidden()))
            .doOnNext(current->{original(current,bearer,scopes);remaining(started,observed,current.getToken().getExpiresAt());}).then()
            .timeout(remaining(started,observed,expected.getToken().getExpiresAt()));
    }
    private void original(JwtAuthenticationToken authentication,String bearer,Set<String> required){
        Jwt jwt=authentication.getToken();valid(jwt);
        if(!bearer.equals(jwt.getTokenValue())||!List.of(settings.domainAudience()).equals(jwt.getAudience())
            ||!settings.inboundClientId().equals(claim(jwt,"client_id"))||!scopes(jwt).containsAll(required)
            ||!(authentication.getPrincipal()instanceof SecurityPrincipal principal)||!jwt.getSubject().equals(principal.subject())
            ||!settings.issuer().equals(principal.issuer())||!settings.platformTenant().equals(principal.tenantId())
            ||!principal.claims().equals(jwt.getClaims())||!principal.scopes().containsAll(required)
            ||!claim(jwt,"staff_actor_id").equals(principal.attributes().get("staffActorId")))throw forbidden();
    }
    private void target(Jwt target,Jwt original,Set<String> required){
        valid(target);
        if(!List.of(settings.coreAudience()).equals(target.getAudience())||!settings.delegatingClientId().equals(claim(target,"client_id"))
            ||!original.getSubject().equals(target.getSubject())||!claim(original,"staff_actor_id").equals(claim(target,"staff_actor_id"))
            ||target.getExpiresAt().isAfter(original.getExpiresAt())||target.getNotBefore().isBefore(original.getNotBefore())||!required.equals(scopes(target)))throw forbidden();
    }
    private void valid(Jwt jwt){
        Instant now=clock.instant();String staff=claim(jwt,"staff_actor_id");
        if(!settings.issuer().equals(jwt.getIssuer()==null?null:jwt.getIssuer().toString())||jwt.getSubject()==null||jwt.getSubject().isBlank()
            ||jwt.getSubject().length()>255||!settings.platformTenant().equals(claim(jwt,"tenant_id"))||!"STAFF".equals(claim(jwt,"principal_kind"))
            ||!staff.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||UUID.fromString(staff).equals(new UUID(0,0))
            ||!(jwt.getClaims().get("exp")instanceof Instant)||!(jwt.getClaims().get("nbf")instanceof Instant)||jwt.getExpiresAt()==null||jwt.getNotBefore()==null
            ||!now.isBefore(jwt.getExpiresAt())||now.isBefore(jwt.getNotBefore())||!jwt.getNotBefore().isBefore(jwt.getExpiresAt()))throw forbidden();
    }
    private void registration(ClientRegistration value,Set<String> scopes){
        StaffUnderwritingSettings.secure(URI.create(value.getProviderDetails().getTokenUri()),settings.allowLoopbackHttp());
        if(!settings.registrationId().equals(value.getRegistrationId())||!AuthorizationGrantType.TOKEN_EXCHANGE.equals(value.getAuthorizationGrantType())
            ||!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(value.getClientAuthenticationMethod())||!settings.delegatingClientId().equals(value.getClientId())
            ||!settings.issuer().equals(value.getProviderDetails().getIssuerUri())||value.getClientSecret()==null||value.getClientSecret().isBlank()
            ||value.getClientSecret().length()>4096||!value.getScopes().containsAll(scopes))throw forbidden();
    }
    private Duration remaining(long started,Instant observed,Instant until){
        long elapsed=nanos.getAsLong()-started;if(elapsed<0||clock.instant().isBefore(observed))throw forbidden();
        var wall=Duration.between(clock.instant(),until);long left=Math.min(Duration.ofSeconds(5).toNanos(),Duration.between(observed,until).toNanos())-elapsed;
        if(left<=0||wall.isZero()||wall.isNegative())throw forbidden();return wall.compareTo(Duration.ofNanos(left))<0?wall:Duration.ofNanos(left);
    }
    private static Set<String> scopes(Jwt jwt){Object value=jwt.getClaims().get("scope");
        if(!(value instanceof String text)||text.length()>4096||text.chars().anyMatch(c->c<0x20||c>0x7e)||jwt.getClaims().containsKey("scp"))throw forbidden();
        var scopes=Set.copyOf(Arrays.asList(text.split(" ",-1)));if(scopes.contains(""))throw forbidden();return scopes;
    }
    private static String claim(Jwt jwt,String name){Object value=jwt.getClaims().get(name);if(!(value instanceof String text)||text.isBlank()||text.codePoints().anyMatch(Character::isISOControl))throw forbidden();return text;}
    private static String bearer(String value){if(value==null||value.length()>16391||!value.startsWith("Bearer ")||value.length()==7)throw unauthorized();String token=value.substring(7);if(token.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)||c==','))throw unauthorized();return token;}
    private static Instant earliest(Instant a,Instant b){return a.isBefore(b)?a:b;}
    public static ReactiveJwtDecoder decoder(StaffUnderwritingSettings settings,String audience){
        var decoder=NimbusReactiveJwtDecoder.withJwkSetUri(settings.jwkSetUri().toASCIIString()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(settings.issuer()),new JwtTimestampValidator(Duration.ZERO),
            jwt->List.of(audience).equals(jwt.getAudience())?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Exact underwriting audience required",null))));return decoder;
    }
    private static ResponseStatusException unauthorized(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Original signed STAFF caller required");}
    private static ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Current exact STAFF delegation refused");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Original STAFF token exchange unavailable");}
    @Override public String toString(){return "ExchangedStaffUnderwritingClientFactoryImpl[redacted]";}
}
