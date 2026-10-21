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

import io.fireflysoftware.core.insurance.underwriting.sdk.api.SubmissionStatusHistoryApi;
import io.fireflysoftware.domain.insurance.underwriting.core.commands.RemoveSubmissionStatusHistoryCommand;
import lombok.extern.slf4j.Slf4j;
import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Deletes a status history row during saga compensation.
 */
@Slf4j
@CommandHandlerComponent
public class RemoveSubmissionStatusHistoryHandler extends CommandHandler<RemoveSubmissionStatusHistoryCommand, Void> {

    private final SubmissionStatusHistoryApi submissionStatusHistoryApi;

    public RemoveSubmissionStatusHistoryHandler(SubmissionStatusHistoryApi submissionStatusHistoryApi) {
        this.submissionStatusHistoryApi = submissionStatusHistoryApi;
    }

    @Override
    protected Mono<Void> doHandle(RemoveSubmissionStatusHistoryCommand command) {
        log.debug("Removing submission status history");
        return submissionStatusHistoryApi.deleteSubmissionStatusHistory(
                command.submissionId(),
                command.submissionStatusHistoryId(),
                UUID.randomUUID().toString()).then();
    }
}
