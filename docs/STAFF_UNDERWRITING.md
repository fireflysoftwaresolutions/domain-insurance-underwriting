# Governed staff underwriting

The staff boundary coordinates referral creation, independent approval, decision recording, and recovery of the original operation. Experience applications call this Domain service through its generated SDK. Domain delegates through the generated `core-insurance-underwriting` SDK. Only Core persists the referral, decision, operation receipt, and associated audit records.

This is a separate staff operation from customer quote offer and acceptance. It does not bind or issue a policy, collect a premium, or establish an insurance accounting entry. Existing submission Saga workflows retain their existing responsibilities. The governed staff command delegates one atomic owner operation; Domain must not invent a second transaction or delete a completed owner action as compensation.

The current governed owner implementation handles a selected `PROPERTY` risk with a positive USD insured value. Decision recording produces an unconditional `ACCEPT` after independent referral approval. Governed decline, refer decisions, conditional acceptance, multi-risk orchestration, re-underwriting, and other product/currency combinations remain separate implementation and integration work; this boundary does not advertise them as supported.

## Operation sequence

1. Obtain the current submission and risk identifiers through their supported business journeys. A caller-provided identifier is not authorization.
2. Choose the intended operation: `REFERRAL_CREATE`, `REFERRAL_APPROVE`, or `DECISION_RECORD`. Keep a single original `actionId` and the complete request for this operation. The request also carries the applicable referral identity, expected referral version, expected receipt digest, and reason defined by the generated contract.
3. Call `POST /api/v1/staff/underwriting/intents`. Core validates and prepares the intent. Domain returns its opaque `intentDigest` unchanged. Preparing does not perform the business mutation.
4. Call `POST /api/v1/staff/underwriting/actions` with the unchanged `intent` and `originalIntentDigest`. The current actor, owner state/version, and authorization must still permit the operation. Approval requires the owner's independent maker/checker rules; a field in the request cannot choose the authenticated employee.
5. Retain the original action and digest when delivery fails. Call `GET /api/v1/staff/underwriting/actions/{actionId}?intentDigest=...` to observe that action. Recovery does not submit the mutation again or create another action identifier.

The exact request/response field types come from the original controller-produced OpenAPI. Use the generated SDK methods and models from the selected build. Do not construct a substitute client or modify generated Java.

## Generated Domain SDK

The generated client is `io.fireflysoftware.domain.insurance.underwriting.sdk.api.StaffUnderwritingApi`. Experience integrations configure its `ApiClient` with the Domain endpoint and the current delegated staff bearer token. They must preserve the original action and request across uncertain responses. They never select a Core endpoint through this client.

| Generated method | Input | Successful body |
| --- | --- | --- |
| `prepareStaffUnderwritingIntentWithHttpInfo` | `GovernedUnderwritingIntent` | `GovernedUnderwritingPreparedIntent` |
| `executeStaffUnderwritingActionWithHttpInfo` | `GovernedUnderwritingCommandRequest` | `GovernedUnderwritingCommandResponse` |
| `recoverStaffUnderwritingActionWithHttpInfo` | Original UUID action and intent digest | `GovernedUnderwritingRecovery` |

These methods return reactive `Mono<ResponseEntity<...>>` values. Read the response body and its operation outcome; HTTP success alone does not establish a committed transition. Non-success responses follow the generated WebClient error contract. Preserve typed503 outcomes using the exact published schema and bounded parsing, and never automatically retry a mutation after a transport failure.

## Identity and delegation

The native resource server verifies the incoming JWT before the staff projection is built. The trusted profile requires an exact issuer and single Domain audience, the registered inbound Experience client, `principal_kind=STAFF`, the configured opaque `tenant_id`, and a canonical nonzero UUID `staff_actor_id`. The employee identifier comes from the signed claim and is projected into the native principal. A customer `party_id` or DTO field cannot supply staff authority.

Domain uses Spring's RFC 8693 token exchange with the original signed token as `subject_token`. Its registered client authenticates the exchange; machine credentials do not replace the staff subject. Domain verifies the returned JWT using the configured JWKS, exact Core audience and registered Domain client. Original issuer, subject, staff actor, and tenant remain unchanged. The target token must not extend the original validity window or broaden the requested scopes. No authorized-client cache or synthetic principal substitutes for the current request.

| Owner operation | Exact delegated scopes |
| --- | --- |
| Referral creation | `insurance:underwriting:referrals:create` |
| Independent approval | `insurance:underwriting:referrals:approve` |
| Decision recording | `insurance:underwriting:decisions:record` |
| Original-action recovery | `insurance:underwriting:actions:recover` and `insurance:underwriting:read` |

The source token must contain the required scopes. Each exchange requests only the scopes for that operation, and the returned scope set must match exactly. A current Core policy decision remains necessary; possessing a scope alone does not approve a business action.

## Configuration

The staff client is off until explicitly enabled. Supply the following properties under `insurance.underwriting.staff-client`:

| Property | Meaning |
| --- | --- |
| `enabled` | Explicitly selects this staff integration. |
| `endpoint` | Core owner origin; no application path, credentials, query, or fragment. |
| `issuer` | Trusted staff token issuer. |
| `jwk-set-uri` | Trusted signing-key endpoint for native JWT verification. |
| `domain-audience` | Exact audience accepted by this Domain boundary. |
| `core-audience` | Exact target audience requested in token exchange. |
| `inbound-client-id` | Registered Experience client allowed to invoke Domain. |
| `delegating-client-id` | Registered Domain client used for the Core exchange. |
| `registration-id` | Spring OAuth client registration selected for the exchange. |
| `platform-tenant` | Exact opaque namespace from the signed tenant claim. |
| `allow-loopback-http` | Explicit test-only allowance for owned loopback HTTP endpoints. Production endpoints use HTTPS. |

The Spring registration must use the same issuer and Domain client ID, `client_secret_basic`, the token-exchange grant, the trusted token endpoint, and the configured allowed scope set. Supply the client secret through the deployment secret mechanism. The Domain and Core audiences must differ; the Experience and Domain client registrations must also differ.

Keep the genuine global resource-server chain, JWT decoder, principal factory, and current policy implementation configured. The scoped quote and staff receivers supplement that chain; enabling a staff feature must not disable authentication on unrelated routes. The actual deployment must allow the intended signed staff route through both its global and scoped checks. Do not disable global security as a startup workaround.

Domain has no business database. Technical cache/event adapters are independent deployment concerns; the staff client does not create business tables or treat cache entries as authoritative referral state.

## Outcomes and recovery

All successful responses use `Cache-Control: no-store`. These operations opt out of generic HTTP response caching, so repeating a transport idempotency header cannot bypass current authorization.

| Response | Meaning and next action |
| --- | --- |
| HTTP 200, `COMMITTED` | Core returned a receipt matching the original action, digest, operation, and applicable referral. Preserve that receipt. |
| HTTP 200, `REFUSED` | Core reported a refusal. This is not a successful business transition. Inspect the current workflow state through authorized reads. |
| HTTP 503, `COMMITTED_RESPONSE_UNAVAILABLE` | The original commit is known, but the protected receipt cannot currently be delivered or validated. Recover the same action; do not generate a replacement operation. |
| HTTP 503, `OUTCOME_UNKNOWN` | Completion cannot be established. Recover the original action under current authorization. Transport failure does not prove rollback. |
| HTTP 400/401/403/404/409/422 | A request, authentication, authority, resource, or conflict refusal. Preserve the actual status; it is not a committed receipt. |
| Generic HTTP 503 platform error | The boundary could not provide a valid observation. This error envelope is distinct from a typed command outcome. |

Recovery returns `FOUND`, `NOT_OBSERVED_AT_THIS_READ`, or `INTENT_CONFLICT`. `NOT_OBSERVED_AT_THIS_READ` is a current read result, not a guarantee that a delayed write cannot complete. A recovered receipt must match the original action and digest. Current authority is checked again before releasing protected data.

A single original five-second budget covers token exchange, owner I/O, and post-response checks, bounded further by token expiration. Time spent waiting is not renewed between steps. A committed result stays committed if final receipt authorization fails; the caller receives committed-response-unavailable instead of a false unknown or rollback.

## Qualification boundary

Native token-exchange, current-identity, deadline, service-outcome, global-security coexistence, and full original OpenAPI regressions belong to the source qualification. Whole producer capture and official SDK generation are required before consumer adoption. Passing those tests does not establish a complete Experience→Domain→Core insurance journey: real service startup, owner persistence, original-action recovery, identity/provider deployment, and policy lifecycle operations require their own integration evidence.

The current source qualification passed all876 required invocations across Config, Core underwriting, and this Domain, including74 Domain invocations. The original full producer emitted15 operations, and the unmodified official generator produced the Domain SDK. Every packaged SDK class and resource matched the fresh compiled output. This qualifies source and the generated contract; signed original-service startup and the full three-layer insurance journey remain open.
