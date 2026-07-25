# T2ME compared with Chunky, Lithium, and Canary

T2ME is often discussed alongside these projects because all can appear in a
performance-focused modpack. They are not interchangeable:

- **T2ME and Chunky schedule pregeneration jobs.**
- **Lithium and Canary optimize Minecraft's ongoing game logic.**

This comparison is about roles and behavior, not a claim that one project is
universally better. Features and loader support in external projects can
change; verify their current release documentation before changing a live
server.

## One-to-one feature comparison

| Capability | T2ME | Chunky | Lithium | Canary |
| --- | --- | --- | --- | --- |
| Primary role | Adaptive, resumable chunk pregeneration | Mature chunk pregeneration and region administration | General game-logic optimization | Lithium-style optimization for Forge-era environments |
| Generates a selected region ahead of exploration | Yes | Yes | No | No |
| Optimizes normal entity, AI, physics, ticking, or block logic | No | No | Yes | Yes |
| T2ME's target environment | Native Forge 1.20.1 | Separate builds exist for supported loaders/versions | Not a drop-in Forge 1.20.1 T2ME replacement | Common complementary choice on Forge 1.20.1 |
| Client installation required for a dedicated server | No | No for normal server operation | Normally no for server optimizations | Normally no for server optimizations |
| Circle and square jobs | Yes | Yes | Not applicable | Not applicable |
| Broader shapes and selection tools | No | Yes, depending on Chunky version/platform | Not applicable | Not applicable |
| Chunk trim/delete tools | No | Yes, depending on Chunky version/platform | No | No |
| World-border-oriented workflow | Manual center/radius | Richer selection workflow | No | No |
| Active job model | One server-wide T2ME job | More mature task/selection command model | No job | No job |
| Concurrency model | Bounded `FULL` future pipeline; actual generation stays in Minecraft/Forge | Schedules pregeneration through the server platform | Patches hot game-engine paths | Ports/reimplements Lithium-style patches for Forge |
| Automatic tick-time backpressure | Yes, target and hard-stop EWMA thresholds | Different scheduler; no direct 1:1 T2ME setting | Not applicable to a pregen queue | Not applicable to a pregen queue |
| Heap-headroom admission stop | Yes | Not T2ME's specific admission model | No pregen admission | No pregen admission |
| Reduce admission while players are online | Yes | Operator workflow/rate control differs | Optimizations remain active | Optimizations remain active |
| Per-coordinate retries with pause on exhaustion | Yes | Different internal/task failure model | Not applicable | Not applicable |
| Stalled in-flight request detection | Yes | Different internal/task monitoring model | Not applicable | Not applicable |
| Pause and resume | Yes | Yes | Not applicable | Not applicable |
| Persistent progress after normal restart | Yes | Yes in supported Chunky versions/configurations | No job to persist | No job to persist |
| CPS, ETA, in-flight, retry, MSPT, heap, and throttle metrics | Yes, in command output | Progress/rate reporting; exact metrics differ | No pregen job metrics | No pregen job metrics |
| Low-level mixins/engine patches | No | Not its primary role | Yes | Yes |
| Direct custom region-file generation in T2ME's model | No | Uses its platform-specific generation workflow | No pregen | No pregen |
| Runs alongside T2ME by default | Not applicable | Blocked to avoid competing pregenerators | Loader/version dependent | Allowed and detected |
| Code embedded in T2ME | T2ME clean-room code only | No | No | No |

External project references:

- [Chunky source](https://github.com/pop4959/Chunky) and
  [command reference](https://github.com/pop4959/Chunky/wiki/Commands)
- [Lithium](https://github.com/CaffeineMC/lithium)
- [Canary](https://modrinth.com/mod/canary)

## Which is faster: T2ME or Chunky?

There is no responsible universal answer.

Raw chunks per second depend heavily on:

- CPU allocation and available worker threads;
- storage latency and region-file contention;
- JVM heap, garbage collection, and Java version;
- seed, dimension, and terrain-generator complexity;
- structure and biome mods;
- whether chunks already exist;
- view/simulation distance and online players; and
- the amount of tick-time degradation the operator is willing to accept.

T2ME intentionally trades peak admission for control when its health signals
cross configured thresholds. A healthy Chunky setup may be faster, equal, or
slower in raw CPS. T2ME may finish sooner in a particular pack when its retry
and stall behavior avoids an operational failure, but that is not proof of a
general performance advantage.

The meaningful question is usually:

> Which tool completes the same region with acceptable MSPT, no missing
> chunks, no failures, and the least operator intervention?

### A fair benchmark

To compare the pregenerators:

1. Clone the same pre-generation world twice.
2. Use the same seed, modpack, configs, JVM, heap, hardware, and target region.
3. Disable the other pregenerator for each run.
4. Keep players offline, or replay the same controlled load.
5. Warm the JVM consistently before measurement.
6. Record wall-clock completion time, completed chunks, average and p95/p99
   MSPT, peak heap, GC pauses, failures/retries, and whether the job recovered
   from one controlled restart.
7. Repeat each run and report variance.

Do not compare a fresh region in one tool with an already generated region in
the other.

## T2ME versus Chunky

Choose **T2ME** when the requirements are:

- a small, server-only Forge 1.20.1 pregenerator;
- automatic tick-time and heap backpressure;
- a strict guard against competing pregenerators/threading mods;
- explicit in-flight, retry, stall, and scheduler-health reporting; and
- a conservative threading boundary that delegates generation to Forge.

Choose **Chunky** when the requirements are:

- a mature cross-server pregeneration ecosystem;
- richer shape, selection, world-border, or trim workflows;
- established commands and integrations used by an existing operations team;
  or
- a platform/version outside T2ME's narrow Forge 1.20.1 target.

Do not actively pregenerate the same world with both tools. T2ME blocks Chunky
by default for this reason.

## T2ME versus Lithium

They solve different problems:

- T2ME creates a finite job that asks the server to bring a selected set of
  chunks to `FULL`.
- Lithium changes implementation details of frequently executed Minecraft
  systems to make normal play and server simulation more efficient while
  preserving vanilla behavior.

Lithium is not a pregenerator. T2ME is not a general tick-optimization mod.
Installing one does not replace the other role. Loader and Minecraft-version
compatibility determine whether upstream Lithium is available in a given
pack.

## T2ME versus Canary

Canary is the directly relevant complementary role for this Forge 1.20.1
project:

- Canary supplies Lithium-style engine optimizations.
- T2ME supplies the bounded pregeneration job and adaptive admission.

T2ME detects Canary, reports it in the log, and deliberately avoids duplicating
its patches. This keeps ownership clear: Canary optimizes engine paths;
T2ME coordinates pregeneration.

Compatibility still depends on the complete modpack. Back up and test the
actual pack rather than assuming two individually safe mods are safe with
every third-party terrain generator.

## Practical decision table

| Goal | Recommended starting point |
| --- | --- |
| Pregenerate a Forge 1.20.1 circle while automatically protecting tick/heap headroom | T2ME |
| Use advanced selection or chunk-trimming operations | Chunky |
| Improve normal server simulation without pregeneration | Canary on a compatible Forge 1.20.1 pack |
| Improve normal simulation on a loader/version supported by upstream Lithium | Lithium |
| Pregenerate and optimize normal Forge simulation | T2ME + Canary, after modpack testing |
| Run two pregenerators simultaneously | Do not; choose one owner |

For T2ME's exact implementation boundaries, see
[Architecture and safety](ARCHITECTURE.md).
