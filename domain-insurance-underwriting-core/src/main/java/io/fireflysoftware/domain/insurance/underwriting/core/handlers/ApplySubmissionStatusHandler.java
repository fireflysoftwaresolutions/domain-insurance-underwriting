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

package io.fireflysoftware.domain.insurance.underwriting.core.handlers;

import io.fireflysoftware.core.insurance.underwriting.sdk.api.SubmissionsApi;
import io.fireflysoftware.core.insurance.underwriting.sdk.model.SubmissionDTO;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.ApplySubmissionStatusCommand;
import lombok.extern.slf4j.Slf4j;
import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Updates a submission, including its status, through the core.
 */
@Slf4j
@CommandHandlerComponent
public class ApplySubmissionStatusHandler extends CommandHandler<ApplySubmissionStatusCommand, SubmissionDTO> {

    private final SubmissionsApi submissionsApi;

    public ApplySubmissionStatusHandler(SubmissionsApi submissionsApi) {
        this.submissionsApi = submissionsApi;
    }

    @Override
    protected Mono<SubmissionDTO> doHandle(ApplySubmissionStatusCommand command) {
        log.debug("Updating submission status: submissionId={}", command.submissionId());
        return submissionsApi.updateSubmission(command.submissionId(), command.record(), UUID.randomUUID().toString());
    }
}
