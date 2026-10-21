<a href="https://fireflysoftware.io/openplatform/">
<picture>
  <source media="(max-width: 640px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-narrow-dark.svg">
  <source media="(max-width: 640px)" srcset="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-narrow-light.svg">
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-dark.svg">
  <img src="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-light.svg" alt="Firefly OpenPlatform - Domain Insurance - Underwriting" width="100%">
</picture>
</a>

# Firefly OpenPlatform - Domain Insurance - Underwriting

Developed by the Firefly Software Foundation. [dev@fireflysoftware.io](mailto:dev@fireflysoftware.io)

[Website](https://fireflysoftware.io/) · [OpenPlatform](https://fireflysoftware.io/openplatform/) · [Module catalogue](https://fireflysoftware.io/openplatform/modules/)

Domain layer microservice for underwriting submissions within the Firefly platform. This service orchestrates create, update, status change, close, and query against `core-insurance-underwriting` with compensating saga steps.

Repository: [https://github.com/fireflysoftwaresolutions/domain-insurance-underwriting](https://github.com/fireflysoftwaresolutions/domain-insurance-underwriting)

---

## Overview

`domain-insurance-underwriting` sits between experience services and `core-insurance-underwriting`. Write operations run through the FireflyFramework saga engine. Queries use the CQRS query bus. The domain depends on the core SDK only. It does not import another core's internal packages. Identifiers that point at parties, contracts, products, or policies stay UUIDs owned by those cores.

It creates, updates, changes status, and closes submissions through the underwriting core. It does not call the sanctions screen on that core, and it does not certify IFRS 17 or Solvency II.

### Key features

- **Create** -- register a submission through `core-insurance-underwriting`, with compensation that deletes the core write
- **Update** -- read the current submission and write the new field values through `updateSubmission` on `core-insurance-underwriting`. Status is left unchanged. Compensation writes the previous record back.
- **Status change** -- read the current submission and update its status through `core-insurance-underwriting`. The create and status-change sagas also append a status history row on the same core. Compensation restores the previous status and, when a history row was added, deletes that row.
- **Close** -- Withdraw and decline run the status saga and are refused when the underwriting core lifecycle does not allow that move. Remove deletes a received submission only. A later status is kept.
- **Query** -- read one submission by identifier
- **Compensation** -- a failed later step asks the core to undo the work already accepted
- **CQRS** -- commands and queries travel on separate buses
- **Reactive I/O** -- Spring WebFlux with virtual threads enabled

## Architecture

### Module structure

| Module | Purpose |
|--------|---------|
| `domain-insurance-underwriting-core` | Saga workflows, commands, handlers, and the application service |
| `domain-insurance-underwriting-interfaces` | Request contracts for a status change and a closing reason |
| `domain-insurance-underwriting-infra` | SDK client factory and downstream base-path configuration |
| `domain-insurance-underwriting-web` | Spring Boot application, REST API, actuator, and OpenAPI |
| `domain-insurance-underwriting-sdk` | Java client generated from the OpenAPI document |

### Dependency flow

```
web --> interfaces --> core --> infra --> core-insurance-underwriting-sdk
sdk (generated from openapi.yml)
```

### Saga map

| Saga | Core calls | Compensation |
|------|------------|--------------|
| `RegisterSubmissionSaga` | `createSubmission` on `SubmissionsApi`, then `createSubmissionStatusHistory` on `SubmissionStatusHistoryApi` | Deletes the status history row, then deletes the submission |
| `UpdateSubmissionSaga` | Reads the current record, then writes field changes through `updateSubmission` on `SubmissionsApi`. Status is left unchanged. | Writes the previous record back through `updateSubmission` |
| `UpdateSubmissionStatusSaga` | Reads the current record, updates its status through `updateSubmission`, then appends status history | Deletes the new history row and writes the previous status back through the core |
| Query | `getSubmissionById` on `SubmissionsApi` | None. A read has nothing to undo. |

Status values accepted by the core: `RECEIVED`, `IN_CLEARANCE`, `REFERRED`, `QUOTED`, `BOUND`, `DECLINED`, `WITHDRAWN`.

## Setup

### Prerequisites

- Java 25
- Apache Maven 3.9+
- `core-insurance-underwriting-sdk` 26.10.4
- Apache Kafka on `localhost:9092` when event publishing is left at the default

### Environment

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_ADDRESS` | `localhost` | Bind address |
| `SERVER_PORT` | `8073` | HTTP port |
| `ENDPOINT_CORE_INSURANCE_UNDERWRITING` | `http://localhost:8080` | Base URL of `core-insurance-underwriting` |

### Build and test

```bash
mvn clean verify
```

### Run

```bash
ENDPOINT_CORE_INSURANCE_UNDERWRITING=http://localhost:8080 mvn spring-boot:run -pl domain-insurance-underwriting-web
```

Swagger UI (non-production): [http://localhost:8073/swagger-ui.html](http://localhost:8073/swagger-ui.html)

## API

| Method | Path | Behavior |
|--------|------|----------|
| `POST` | `/api/v1/submissions` | Runs `RegisterSubmissionSaga` |
| `GET` | `/api/v1/submissions/{submissionId}` | Queries the core |
| `PUT` | `/api/v1/submissions/{submissionId}/status` | Runs `UpdateSubmissionStatusSaga` |
| `PUT` | `/api/v1/submissions/{submissionId}` | Runs `UpdateSubmissionSaga` |
| `POST` | `/api/v1/submissions/{submissionId}/withdraw` | Withdraw submission. Runs `UpdateSubmissionStatusSaga` when the core lifecycle allows `WITHDRAWN`. |
| `POST` | `/api/v1/submissions/{submissionId}/decline` | Decline submission. Runs `UpdateSubmissionStatusSaga` when the core lifecycle allows `DECLINED`. |
| `DELETE` | `/api/v1/submissions/{submissionId}` | Deletes the record through `core-insurance-underwriting` when that core allows removal |

The status request body is `status` plus an optional `statusReason`.

Withdraw and decline run the status saga and are refused when the underwriting core lifecycle does not allow that move. Remove deletes a received submission only. A later status is kept.
A closing command body is an optional `statusReason`.

