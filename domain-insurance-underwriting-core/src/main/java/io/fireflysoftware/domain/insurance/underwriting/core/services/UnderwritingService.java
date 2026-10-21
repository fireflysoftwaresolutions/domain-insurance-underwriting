/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.domain.insurance.underwriting.core.services;

import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.CreateSubmissionCommand;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.UpdateSubmissionStatusCommand;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Application service for submission orchestration.
 */
public interface UnderwritingService {

    /**
     * Creates a submission through the register saga.
     *
     * @param command the create command
     * @return the record returned by the core
     */
    Mono<SubmissionDTO> create(CreateSubmissionCommand command);

    /**
     * Moves a submission to a new status through the core.
     *
     * @param command the status change
     * @return the updated record
     */
    Mono<SubmissionDTO> updateStatus(UpdateSubmissionStatusCommand command);

    /**
     * Reads one submission.
     *
     * @param submissionId the core identifier
     * @return the record, or empty when the core has none
     */
    Mono<SubmissionDTO> get(UUID submissionId);
}
