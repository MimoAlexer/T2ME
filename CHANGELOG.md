# Changelog

All notable changes to T2ME are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
