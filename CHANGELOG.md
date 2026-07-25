# Changelog

All notable changes to T2ME are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Experimental stage-aware world-generation engine scoped to the active T2ME
  job plus a 12-chunk dependency margin.
- Dedicated configurable worker pool for `BIOMES`, `NOISE`, `SURFACE`,
  `CARVERS`, and `SPAWN` by default.
- Independent opt-in switches for threaded structure stages and radius-locked
  `FEATURES`.
- Non-blocking multi-coordinate future-tail lock manager.
- Adaptive request-window controller with additive increase, multiplicative
  MSPT/heap backoff, best-window restoration, and live throttle reasons.
- Multi-producer completion queue with bounded server-thread draining.
- Live boss bar showing progress, rate, ETA, active work, throttle state, and
  color-coded terminal outcomes.
- Styled multi-line `/t2me status`, `/t2me metrics`, and
  `/t2me config show` output.
- Detailed architecture, one-to-one project comparison, third-party notices,
  and reproducible A/B benchmark protocol.
- Release gate requiring a median throughput win of at least 10% over Chunky
  plus world-integrity, MSPT, heap/GC, error, and restart checks before T2ME
  claims to be faster.

### Changed

- Replaced the 0.1 per-request coordinator path with batched server-thread
  ticket admission, one distance-manager update per batch, and direct
  main-thread `FULL` future lookup.
- Raised development scheduler defaults from a conservative eight-request
  pipeline to `32` minimum, `64` initial, and `384` configured maximum
  in-flight futures.
- Raised the default dispatch batch to 64 requests and added a default
  completion-drain budget of 1,024 events per tick.
- Added an exact-default migration from the untouched 0.1 scheduler profile;
  customized legacy profiles remain unchanged.
- Changed per-issuance identity from UUID allocation to a monotonic `long`
  sequence.
- Replaced the completion timestamp history with fixed rate buckets and added
  request-latency EWMA reporting.
- Persisted per-coordinate retry counts so restart recovery does not reset a
  repeatedly failing chunk's retry budget.
- Optimized circle target counting and batched spiral-plan polling.
- Changed stall handling to release all tracked tickets, requeue active
  coordinates, and pause with a visible diagnostic.
- Changed target-dimension loss to requeue tracked work, deactivate the scoped
  engine, and pause for operator recovery.

### Safety

- Lighting, `FULL` conversion, and region-file I/O remain on the native
  Minecraft/Forge path.
- Structure and `FEATURES` threading are disabled by default pending
  target-modpack parity testing.
- The new chunk-status Mixin and direct server-chunk-cache invokers make this
  branch experimental and unsuitable for production until the benchmark and
  compatibility gates pass.
- No C2ME, Chunky, Lithium, Noisium, Moonrise, or Canary code/binary is
  embedded in T2ME.

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

[Unreleased]: https://github.com/MimoAlexer/T2ME/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/MimoAlexer/T2ME/releases/tag/v0.1.0
