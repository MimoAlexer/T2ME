# Contributing

Contributions are welcome, especially reproducible bug reports, compatibility
tests, documentation improvements, and performance measurements.

## Development setup

Requirements:

- Java 17
- Git
- An internet connection for ForgeGradle dependencies

Build and test:

```bash
./gradlew clean test build
./gradlew runGameTestServer
```

On Windows:

```powershell
.\gradlew.bat clean test build
.\gradlew.bat runGameTestServer
```

The production JAR is written to `build/libs/`.

## Safety invariants

Changes must preserve these rules:

1. Do not mutate levels, chunks, tickets, or SavedData from a custom thread.
2. Do not write region files directly.
3. Every issued ticket must have a deterministic release path.
4. A restart must not silently skip requests that were in flight.
5. Invalid operator or persisted input must be rejected before traversal.
6. Compatibility takes priority over benchmark-only throughput.

## Pull requests

- Keep each change focused.
- Add or update tests for planner and persistence behavior.
- Explain any threading or ticket-lifecycle impact.
- Run `clean test build` before submitting.
- Run `runGameTestServer` for generation, ticket, and lifecycle changes. The
  disposable development world lives in `run-gametest/`; test sources are not
  packaged into the production mod.
- Use the [planner benchmark](benchmarks/README.md) for planner CPU changes and
  the [server benchmark](docs/BENCHMARKING.md) for end-to-end comparisons.
- Do not include Minecraft, Forge, or third-party mod binaries.
