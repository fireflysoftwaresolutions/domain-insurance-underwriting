/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.core.services.staff;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.*;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface GovernedStaffUnderwritingService {
    Mono<GovernedUnderwritingPreparedIntent> prepare(String authorization,GovernedUnderwritingIntent intent);
    Mono<GovernedUnderwritingCommandResponse> execute(String authorization,GovernedUnderwritingCommandRequest command);
    Mono<GovernedUnderwritingRecovery> recover(String authorization,UUID actionId,String intentDigest);
}
