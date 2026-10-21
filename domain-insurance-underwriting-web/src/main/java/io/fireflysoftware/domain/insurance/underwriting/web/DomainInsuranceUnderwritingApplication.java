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

package io.fireflysoftware.domain.insurance.underwriting.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.web.reactive.config.EnableWebFlux;

/**
 * Entry point for domain-insurance-underwriting.
 */
@SpringBootApplication(scanBasePackages = {
        "io.fireflysoftware.domain.insurance.underwriting",
        "org.fireflyframework.web"
})
@EnableWebFlux
@ConfigurationPropertiesScan(basePackages = "io.fireflysoftware.domain.insurance.underwriting")
@OpenAPIDefinition(
info = @Info(
        title = "Domain Insurance Underwriting API",
        version = "1.0.0",
        description = "Orchestrates underwriting submissions through core-insurance-underwriting. It records submission status through the underwriting core. It does not call the sanctions screen on that core, and it does not certify IFRS 17 or Solvency II.",
        contact = @Contact(
                name = "Firefly Software Foundation",
                email = "dev@fireflysoftware.io"
        )
),
servers = {
        @Server(
                url = "https://openplatform.fireflysoftware.io/domain-insurance-underwriting",
                description = "Development Environment"
        ),
        @Server(
                url = "/",
                description = "Local Development Environment"
        )
}
)

public class DomainInsuranceUnderwritingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DomainInsuranceUnderwritingApplication.class, args);
    }
}
