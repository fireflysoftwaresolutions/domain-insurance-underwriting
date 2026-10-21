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
import io.fireflysoftware.domain.insurance.underwriting.core.commands.GetSubmissionQuery;
import lombok.extern.slf4j.Slf4j;
import org.fireflyframework.cqrs.annotations.QueryHandlerComponent;
import org.fireflyframework.cqrs.query.QueryHandler;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Loads one submission from the core.
 */
@Slf4j
@QueryHandlerComponent
public class GetSubmissionHandler extends QueryHandler<GetSubmissionQuery, SubmissionDTO> {

    private final SubmissionsApi submissionsApi;

    public GetSubmissionHandler(SubmissionsApi submissionsApi) {
        this.submissionsApi = submissionsApi;
    }

    @Override
    protected Mono<SubmissionDTO> doHandle(GetSubmissionQuery query) {
        log.debug("Fetching submission: submissionId={}", query.submissionId());
        return submissionsApi.getSubmissionById(query.submissionId(), UUID.randomUUID().toString());
    }
}
