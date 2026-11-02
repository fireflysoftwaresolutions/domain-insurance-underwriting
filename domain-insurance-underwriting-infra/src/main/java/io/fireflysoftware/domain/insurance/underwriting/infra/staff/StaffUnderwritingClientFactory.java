/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra.staff;

import io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedUnderwritingApi;
import java.time.Duration;
import java.util.Set;
import reactor.core.publisher.Mono;

public interface StaffUnderwritingClientFactory {
    Mono<Client> current(String authorization,Set<String> scopes);
    interface Client {
        GovernedUnderwritingApi api();
        Duration remaining();
        Mono<Void> revalidate();
    }
}
