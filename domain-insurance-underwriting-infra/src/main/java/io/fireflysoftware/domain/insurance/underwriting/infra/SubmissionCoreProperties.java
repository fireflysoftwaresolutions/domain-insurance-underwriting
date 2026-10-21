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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Base path for {@code core-insurance-underwriting}.
 */
@ConfigurationProperties(prefix = "api-configuration.core-insurance.underwriting")
@Data
public class SubmissionCoreProperties {

    private String basePath;
}
