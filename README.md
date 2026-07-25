<p align="center">
  <img src="docs/assets/t2me-logo.svg" alt="T2ME logo" width="220">
</p>

<h1 align="center">T2ME — Threaded Task Management Engine</h1>

[![Build](https://github.com/MimoAlexer/T2ME/actions/workflows/ci.yml/badge.svg)](https://github.com/MimoAlexer/T2ME/actions/workflows/ci.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62b47a)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.21-f16436)](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)
[![Java](https://img.shields.io/badge/Java-17-007396)](https://adoptium.net/temurin/releases/?version=17)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

T2ME is a clean-room, server-side chunk pregenerator for Minecraft Forge
1.20.1. It keeps a bounded pipeline of `FULL` chunk requests moving through
Minecraft's existing task system, saves its progress, and automatically
reduces admission when server health degrades.

The goal is useful throughput **without trading away world safety or server
responsiveness**.

> [!IMPORTANT]
> T2ME is not a Forge port of C2ME or Lithium. It does not replace Minecraft's
> terrain generator, patch game logic, or write region files from custom
> threads. See [Architecture](docs/ARCHITECTURE.md) for the exact threading
> boundary.

## Highlights

- Center-out pregeneration in `circle` or `square` regions.
- Spawn-centered jobs or explicit dimension and block coordinates.
- A bounded pipeline of vanilla/Forge `FULL` chunk futures.
- Adaptive admission using tick-time EWMA, heap headroom, processor count, and
  online-player load.
- Persistent cursor, progress, failures, and pending coordinates.
- Safe clean-restart recovery with an optional 10-second auto-resume delay.
- Pause, resume, cancel, status, metrics, CPS, and ETA commands.
- Per-request region tickets with cleanup on success, failure, cancellation,
  and normal shutdown.
- Stalled-request detection and bounded retries without silently skipping the
  affected coordinate.
- A compatibility guard for known pregenerators and invasive threading mods.
- Server-only installation; unmodified clients can connect.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.21 |
| Java | 17 |
| Side | Dedicated or integrated server |

T2ME has no required third-party mod dependency.

## Installation

1. Back up the world and test the backup before pregenerating a large region.
2. Stop the server.
3. Download the T2ME JAR from
   [GitHub Releases](https://github.com/MimoAlexer/T2ME/releases).
4. Place it in the server's `mods` directory.
5. Remove or disable other active pregenerators, then start the server.

Forge creates the configuration at:

```text
<world>/serverconfig/t2me-server.toml
```

T2ME is server-side only. Players do not need the JAR in their client mod
folder.

## Quick start

Generate a circle with a 10,000-block radius around the overworld's current
shared spawn:

```mcfunction
/t2me pregen start 10000 circle
```

Watch progress and scheduler health:

```mcfunction
/t2me status
/t2me metrics
```

Pause safely at any time:

```mcfunction
/t2me pregen pause
```

Then continue the same job:

```mcfunction
/t2me pregen resume
```

Only one T2ME job can be active or paused at a time. Starting a new job is
allowed after the previous one reaches `completed`, `cancelled`, or `failed`.

## Commands

All commands require permission level `4` by default.

| Command | Description |
| --- | --- |
| `/t2me pregen start <radiusBlocks> [circle\|square]` | Start at the overworld's current shared spawn. The default shape is `circle`. |
| `/t2me pregen startat <dimension> <centerX> <centerZ> <radiusBlocks> [circle\|square]` | Start at explicit block coordinates in a loaded dimension. |
| `/t2me pregen pause` | Stop admitting new requests while preserving the job. Existing requests may finish. |
| `/t2me pregen resume` | Resume a paused job if the compatibility guard permits it. |
| `/t2me pregen cancel` | Release T2ME tickets and permanently cancel the current job. |
| `/t2me pregen status` | Show the same detailed job status as `/t2me status`. |
| `/t2me status` | Show state, target, progress, in-flight work, retries, CPS, ETA, throttle reason, and last message. |
| `/t2me metrics` | Add tick EWMA, decaying tick peak, admission limit, heap headroom, and player count to job status. |
| `/t2me config show` | Show the most important live configuration values. |

`radiusBlocks` must be from `16` through `20,000`. The center plus radius must
remain within Minecraft's safe coordinate range. Dimension identifiers use
resource-location syntax, for example:

```mcfunction
/t2me pregen startat minecraft:the_nether 0 0 5000 square
```

## Configuration

The defaults are intentionally conservative. Measure MSPT, heap, and player
experience before increasing concurrency.

| Key | Default | Range | Effect |
| --- | ---: | ---: | --- |
| `scheduler.maxInFlight` | `8` | `1–32` | Maximum simultaneous `FULL` requests before adaptive caps. |
| `scheduler.maxDispatchPerTick` | `4` | `1–16` | Maximum new requests admitted at the end of one tick. |
| `scheduler.targetTickMillis` | `45` | `20–100` | Halve admission when tick EWMA reaches this value. |
| `scheduler.hardStopTickMillis` | `55` | `30–200` | Stop new admission while tick EWMA is at or above this value. |
| `scheduler.minHeapHeadroomMiB` | `1024` | `256–8192` | Stop new admission below this max-heap headroom. |
| `scheduler.stallTimeoutSeconds` | `120` | `30–3600` | Pause the job when the oldest request exceeds this age. |
| `scheduler.reduceWhenPlayersOnline` | `true` | boolean | Halve admission while one or more players are online. |
| `job.maxRetries` | `2` | `0–10` | Retry count per failed coordinate before pausing the job. |
| `job.saveIntervalTicks` | `100` | `20–1200` | How often to mark a running snapshot for persistence. |
| `job.progressLogIntervalSeconds` | `60` | `0–3600` | Periodic status logging; `0` disables it. |
| `job.autoResume` | `true` | boolean | Resume a cleanly interrupted running job 10 seconds after startup. |
| `job.allowCompetingPregenerators` | `false` | boolean | Override the compatibility guard. Use only when the other mod is inactive. |
| `permissionLevel` | `4` | `0–4` | Required permission level for `/t2me`. |

The effective in-flight limit is also capped at twice the JVM's available
processors, with a minimum processor cap of two and an absolute cap of 32.

## Safety and compatibility

T2ME makes the following guarantees within its own code:

- Chunk tickets, job state, configuration decisions, and completion handling
  are performed on the server thread.
- Its single request-coordinator thread only enters Forge's public
  `getChunkFuture` API and composes the returned future. It does not read or
  mutate chunks.
- Actual terrain generation remains inside Minecraft/Forge's normal worker
  graph.
- Each request uses a job- and issuance-specific ticket identity, preventing a
  late callback from completing a newer job's request.
- A failed coordinate is retried or retained when the job pauses; it is not
  silently counted as complete.

T2ME blocks `start` and `resume` by default when it detects Chunky, C2ME,
C2ME Forge, Chunk Pregenerator, Dimensional Threading, or MCMT. Canary and
ModernFix are detected for reporting but are not blocked.

Canary is complementary on Forge 1.20.1: Canary can provide Lithium-style
engine optimizations while T2ME owns pregeneration admission. Do not run two
pregenerators over the same region at the same time.

For design details and the exact lifecycle, read
[Architecture and safety](docs/ARCHITECTURE.md).

## T2ME, Chunky, Lithium, and Canary

These projects do different jobs. T2ME and Chunky are pregenerators;
Lithium and Canary optimize game-engine logic. There is no honest universal
"faster" winner without a controlled benchmark of the same seed, modpack,
hardware, JVM, and server-health target.

See the [feature-by-feature comparison](docs/COMPARISON.md) for the practical
differences and guidance on choosing a setup.

## Limitations

- Forge 1.20.1 only.
- One T2ME job at a time.
- `circle` and `square` shapes only.
- No trim, delete, world-border import, or selection-management commands.
- No low-level terrain-generation, lighting, entity, AI, redstone, or physics
  optimization patches.
- Previously generated chunks are still requested at `FULL`; they normally
  complete quickly but count toward progress.
- Planning counts the region synchronously when a job starts. The
  20,000-block radius cap limits this work, but very large plans can still
  pause the command thread briefly.
- Recovery depends on normal Forge world saving. Keep external world backups;
  no mod can protect data from every crash, disk, hardware, or third-party
  failure.

## Build and test

Clone the repository and build with a Java 17 JDK:

```powershell
git clone https://github.com/MimoAlexer/T2ME.git
cd T2ME
.\gradlew.bat clean test build
```

Linux and macOS:

```bash
git clone https://github.com/MimoAlexer/T2ME.git
cd T2ME
./gradlew clean test build
```

The reobfuscated production JAR is written to `build/libs/`.

The unit suite covers deterministic spiral traversal, shape boundaries,
cursor restoration, coordinate limits, stale ticket identities, retry
exhaustion, and restart requeue ordering.

## Project status

T2ME is early software. Treat new releases as operational infrastructure:
test on a copy of the world, review configuration changes, and keep verified
backups.

Bug reports should include the T2ME version, Forge version, relevant
configuration, `/t2me status`, `/t2me metrics`, and a log excerpt:
[open an issue](https://github.com/MimoAlexer/T2ME/issues).

## License and clean-room statement

T2ME is available under the [MIT License](LICENSE).

The implementation does not copy or embed C2ME, Lithium, Canary, or Chunky
code. Those projects remain independent and are governed by their respective
licenses.
