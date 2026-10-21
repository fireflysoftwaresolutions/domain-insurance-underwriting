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
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RemoveSubmissionCommand;
import lombok.extern.slf4j.Slf4j;
import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Deletes a submission through the core during saga compensation.
 */
@Slf4j
@CommandHandlerComponent
public class RemoveSubmissionHandler extends CommandHandler<RemoveSubmissionCommand, Void> {

    private final SubmissionsApi submissionsApi;

    public RemoveSubmissionHandler(SubmissionsApi submissionsApi) {
        this.submissionsApi = submissionsApi;
    }

    @Override
    protected Mono<Void> doHandle(RemoveSubmissionCommand command) {
        log.debug("Removing submission: submissionId={}", command.submissionId());
        return submissionsApi.deleteSubmission(command.submissionId(), UUID.randomUUID().toString()).then();
    }
}
