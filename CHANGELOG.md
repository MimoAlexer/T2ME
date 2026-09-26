# Changelog

All notable changes to T2ME are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-09-26

### Changed

- Count square plans in constant time and circle plans by chunk row instead of
  scanning the entire area. Preserve the original traversal and saved cursors.
- Traverse with cached lookahead and incremental coordinates, removing repeated
  square roots and coordinate allocations from the sequential path.
- Adapt the in-flight window gradually up to the configured limit. Defaults are
  32 in flight and 16 admissions per tick, with an absolute bound of 256.
- Back off immediately after a slow tick; require sustained recovery before
  restarting. Scale heap reserve for small JVM heaps and retain player backoff.
- Batch worker completions through a job-specific mailbox on the server thread,
  with one snapshot per batch. Include scheduler overhead in tick health.
- Bound rate-tracking memory with fixed 100 ms buckets.

### Fixed

- Retain every polled coordinate and retry count across checkpoints.
- Reject inconsistent saved progress, preserve unreadable job data, and report
  recovery errors in status. Explicit cancellation is required to discard it.
- Ignore late completions after cancellation, replacement, and server detach.
- Reset server health on attach, preserve health pressure across new jobs, and
  allow an operator pause to suppress pending automatic resume.
- Recognize a fully completed recovered checkpoint without waiting for another
  callback.

### Validation

- Added planner equivalence, checkpoint corruption, retry, limiter and concurrent
  completion mailbox tests.
- Added a separate Forge GameTest suite for actual generation, pause/resume,
  disk checkpoint reload, cancellation, and job replacement; CI and releases
  run it. The test classes are excluded from the production JAR.
- Added a reproducible planner microbenchmark. Planner results do not establish
  world-generation speed relative to Chunky.

## [0.1.0] - 2026-07-25

### Added

- Bounded concurrent `FULL` chunk requests through Forge's supported chunk
  future API.
- Circle and square pregeneration around the shared spawn or explicit
  dimension coordinates.
- Adaptive admission control based on tick time, heap headroom, online
  players, and stalled requests.
- Persistent job state with safe restart recovery and optional automatic
  resume.
- Per-issuance ticket identities, deterministic ticket cleanup, retries, and
  failure pausing.
- Operator status, metrics, configuration, pause, resume, and cancel commands.
- Compatibility detection for Canary, ModernFix, Chunky, and known
  dimension-threading mods.
- Model-level radius and world-coordinate validation.

[Unreleased]: https://github.com/MimoAlexer/T2ME/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/MimoAlexer/T2ME/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/MimoAlexer/T2ME/releases/tag/v0.1.0
