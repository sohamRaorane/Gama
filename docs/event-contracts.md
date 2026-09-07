# Event Envelope Contract

This document defines the canonical event envelope that all asynchronous MeshPay events must follow.

Every service that produces or consumes Kafka events must use this envelope.

## Envelope Structure

```json
{
  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "eventType": "PaymentSubmitted",
  "eventVersion": 1,
  "occurredAt": "2026-09-06T12:00:00Z",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "causationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "source": "ingestion-service",
  "payload": {}
}
```

## Field Definitions

### eventId

Unique identifier for the individual event. Globally unique. Use UUID v4.

Purpose: event identity, idempotent processing, duplicate detection.

Two events can never share the same `eventId`. If a producer publishes the same logical fact twice (retry, duplicate call), it must use the same `eventId` so consumers can deduplicate.

### eventType

Logical type of the event. Describes what happened.

Examples:

| eventType | Meaning |
|-----------|---------|
| `PaymentSubmitted` | A payment request was received and validated |
| `PaymentAccepted` | Payment passed fraud checks and is accepted |
| `PaymentRejected` | Payment failed fraud or validation checks |
| `PaymentSettled` | Payment was settled and balances updated |

`eventType` is not a database table name. It is a logical label for the event.

### eventVersion

Integer version of the event contract. Starts at `1`.

Allows the event schema to evolve without silently breaking consumers. When the payload structure changes in a backward-incompatible way, `eventVersion` increments. Consumers can use this to decide how to interpret the payload.

Do not implement a full schema registry yet. This is a simple integer for now.

### occurredAt

Timestamp representing when the event occurred. UTC only.

Format: ISO-8601 / RFC 3339.

Example: `2026-09-06T12:00:00Z`

Never use local timezone timestamps in event contracts. All timestamps in the event system are UTC.

### correlationId

Identifies the overall request or operation across service boundaries.

This corresponds to the HTTP `X-Correlation-ID` header introduced in Day 3. The same value must propagate from client through Gateway through Auth through all downstream services and events.

Purpose: tracing one logical operation across the entire system.

### causationId

Identifies the specific event or command that directly caused this event.

`correlationId` and `causationId` are different things.

`correlationId` = "this all belongs to the same user operation"

`causationId` = "this specific event caused that specific event"

Example:

- Event A is published with `eventId = A-001`
- Event B is caused by Event A
- Event B has `causationId = A-001`
- Both Event A and Event B share the same `correlationId`

### source

Identifies which service or component produced the event.

Use service identity, not machine hostname or IP.

Examples: `ingestion-service`, `settlement-service`, `fraud-service`, `notification-service`.

### payload

Contains the actual business-specific event data. The envelope stays generic; payload is event-specific.

The shape of `payload` depends on `eventType`. Each event type defines its own payload structure. The payload for `PaymentSubmitted` is different from the payload for `PaymentSettled`.

Do not define detailed payment payloads yet. That belongs to Phase 2.

## Design Principles

1. Every event has a unique `eventId`. Producers must not silently reuse event IDs.

2. Consumers must tolerate duplicate delivery. Design for idempotency from the start.

3. `eventVersion` identifies the payload contract version. Consumers should check this.

4. `occurredAt` is always UTC in ISO-8601 format.

5. `correlationId` is propagated across all service boundaries. It originates from the client request.

6. `causationId` represents the direct cause. It does not replace `correlationId`.

7. `source` identifies the producing component by service name.

8. `payload` contains event-specific information. The envelope is not event-specific.

9. Consumers should not rely on event ordering unless explicitly guaranteed later.

10. Events are immutable facts. Once published, an event must never be mutated.

11. Producers must not silently change already-published events.

12. Future producers and consumers must use this envelope for all Kafka events.

13. Event processing must eventually be idempotent. Use `eventId` for deduplication.

## Kafka Topics

| Topic | Purpose | Producer | Consumer |
|-------|---------|----------|----------|
| `meshpay.payment.ingested` | Payment ingestion events | Ingestion Service (future) | Settlement Service (future) |
| `meshpay.payment.settled` | Payment settlement events | Settlement Service (future) | Fraud Service (future), Notification Service (future) |
| `meshpay.fraud.events` | Fraud evaluation results | Fraud Service (future) | Settlement Service (future) |
| `meshpay.notification.events` | Notification dispatch events | Notification Service (future) | — |
| `meshpay.dead-letter` | Events that failed processing | Any service (future) | Operations / debugging (future) |

All topics use the `meshpay.` prefix for namespace isolation.

### Topic Details

**meshpay.payment.ingested**

Payment requests arrive here after validation by the Ingestion Service. This is the entry point into the asynchronous payment pipeline.

Producer: Ingestion Service (future).
Consumer: Settlement Service (future).

**meshpay.payment.settled**

Settlement completion events. Published after a payment is settled and balances are updated.

Producer: Settlement Service (future).
Consumer: Fraud Service (future), Notification Service (future).

**meshpay.fraud.events**

Fraud evaluation results. Produced when the Fraud Service completes its analysis of a payment.

Producer: Fraud Service (future).
Consumer: Settlement Service (future).

**meshpay.notification.events**

Notification events for delivery to end users or systems.

Producer: Notification Service (future).
Consumer: None defined (future: push notification gateway, email service, etc.).

**meshpay.dead-letter**

Events that could not be processed successfully. All failed events land here for manual inspection or retry.

Producer: Any service that fails to process an event after retries.
Consumer: Operations tooling (future).

## Event Flow

```
Mesh Simulator / Client
        |
        | HTTP (sync)
        v
    API Gateway :8080
        |
        | HTTP (sync)
        v
  Ingestion Service
        |
        | publish
        v
meshpay.payment.ingested
        |
        v
  Settlement Service
        |
        +---> meshpay.payment.settled
        |           |
        |           +---> Fraud Service
        |           |         |
        |           |         +---> meshpay.fraud.events
        |           |                     |
        |           |                     v
        |           |              Settlement Service
        |           |
        |           +---> Notification Service
        |                         |
        |                         +---> meshpay.notification.events
        |
        v
   Dashboard (future)
```

Failed events at any stage flow to `meshpay.dead-letter`.
