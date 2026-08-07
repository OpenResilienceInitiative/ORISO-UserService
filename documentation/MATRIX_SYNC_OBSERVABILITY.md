# Matrix background-sync trace attribution

UserService runs Matrix `/sync` on a background executor, so the outbound HTTP
span has no incoming web-request parent. On the current one-replica runtime, the
listener now opens one `userservice.matrix.sync` observation around each long
poll and the complete event-processing path triggered by its result.

The only custom low-cardinality attribute is `result`, bounded to:

- `success` when the long poll and event processing complete;
- `soft_failure` when the Matrix adapter returns no result after a handled
  transport or provider failure;
- `exception` when event processing throws and the outer loop applies its
  existing backoff.

Matrix access tokens, sync cursors, room IDs, user IDs, event IDs, paths and URL
query values are not exported as observation attributes.

## Live baseline

A read-only seven-day SigNoz audit on 2026-07-26 found 2,870 Matrix sync calls
as standalone one-span traces. Their approximately 30-second p50 and p95 match
the configured long poll and are not a latency defect. Of the sampled calls,
2,744 returned HTTP 200. A burst of 123 HTTP 404 responses was confined to two
minutes on 2026-07-22; successful responses resumed afterwards.

That audited pod predates this implementation. After a reviewed merge and
deployment, the required readback is:

1. sampled Matrix `/sync` client spans have a `userservice.matrix.sync` parent;
2. downstream work from the processed batch remains in the same trace;
3. expected successful 30-second polls are not marked as errors;
4. custom attributes contain only the bounded `result` key.

This instrumentation does not change the current one-replica ceiling. When
shared Matrix leadership and durable cursor coordination are introduced, the
same observation must start only after ownership is acquired and cover the
ownership re-check and cursor commit.

The target architecture remains Matrix chat, the ORISO frontend, an
ORISO-controlled Element Call/MatrixRTC fork and LiveKit.
