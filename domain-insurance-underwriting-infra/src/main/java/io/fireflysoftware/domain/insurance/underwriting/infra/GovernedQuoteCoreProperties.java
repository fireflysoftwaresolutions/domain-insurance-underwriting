/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit issuer/receiver/client bindings; absent values never choose an endpoint or actor. */
@ConfigurationProperties(prefix="api-configuration.core-insurance.governed-quotes")
public record GovernedQuoteCoreProperties(String basePath,String registrationId,String issuer,String jwkSetUri,
        String domainAudience,String coreAudience,String tenantClaim,String delegatingClientClaim,String delegatingClientId,
        boolean allowLoopback) { }
