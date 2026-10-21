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

package io.fireflysoftware.domain.insurance.underwriting.infra;

import io.fireflysoftware.core.insurance.underwriting.sdk.api.SubmissionsApi;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import io.fireflysoftware.core.insurance.underwriting.sdk.api.SubmissionStatusHistoryApi;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Exposes the {@code core-insurance-underwriting} SDK clients used by the saga handlers.
 */
@Component
public class SubmissionClientFactory {

    private final ApiClient apiClient;

    public SubmissionClientFactory(SubmissionCoreProperties properties) {
        this.apiClient = new ApiClient();
        this.apiClient.setBasePath(properties.getBasePath());
    }

    /**
     * Creates the primary core client.
     *
     * @return the API client
     */
    @Bean
    public SubmissionsApi submissionsApi() {
        return new SubmissionsApi(apiClient);
    }

    /**
     * Creates the status history client.
     *
     * @return the status history API
     */
    @Bean
    public SubmissionStatusHistoryApi submissionStatusHistoryApi() {
        return new SubmissionStatusHistoryApi(apiClient);
    }

}
