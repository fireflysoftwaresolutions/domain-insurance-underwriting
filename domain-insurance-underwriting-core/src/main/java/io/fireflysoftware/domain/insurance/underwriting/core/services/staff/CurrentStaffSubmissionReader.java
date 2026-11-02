/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.core.services.staff;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionDTO;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface CurrentStaffSubmissionReader {
    Mono<SubmissionDTO> read(String authorization,UUID submissionId);
}
