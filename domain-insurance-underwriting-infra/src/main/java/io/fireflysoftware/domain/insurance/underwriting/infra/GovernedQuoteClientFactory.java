/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra;

import io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedQuoteOffersApi;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Runs one caller-bound owner operation under the original exchange/call window. */
public interface GovernedQuoteClientFactory {
    <T> Mono<T> invoke(String authorization,Set<String> requiredScopes,Function<GovernedQuoteOffersApi,Mono<T>> operation);
}
