# T2ME compared with Chunky, C2ME, Lithium, Noisium, and Moonrise

This comparison describes the experimental T2ME `0.2.0-dev.1` branch. It
separates project scope from performance: similar ideas do not make two
implementations equivalent, and a larger concurrency number does not prove
that one is faster.

Primary project references:

- [Chunky source](https://github.com/pop4959/Chunky) and
  [commands](https://github.com/pop4959/Chunky/wiki/Commands)
- [C2ME source](https://github.com/RelativityMC/C2ME-fabric) and its
  [1.20.1 branch](https://github.com/RelativityMC/C2ME-fabric/tree/ver/1.20.1)
- [Lithium source](https://github.com/CaffeineMC/lithium) and its
  [1.20.1 branch](https://github.com/CaffeineMC/lithium/tree/1.20.1)
- [Noisium source](https://github.com/Steveplays28/noisium)
- [Moonrise source](https://github.com/Tuinity/Moonrise)

Consult each upstream project's current documentation for supported loaders
and Minecraft versions. Their support matrices can change independently of
T2ME.

## One-to-one scope

| Capability | T2ME 0.2 dev | Chunky | C2ME | Lithium | Noisium | Moonrise |
| --- | --- | --- | --- | --- | --- | --- |
| Primary role | Forge 1.20.1 pregenerator plus scoped stage scheduler | Mature pregeneration and region administration | Broad chunk generation, loading, scheduling, and I/O optimization | General game-logic optimization | World-generation/noise-path optimization | Broad chunk-system rewrite and scheduler |
| Own pregeneration command/job | Yes | Yes | No equivalent finite region job by itself | No | No | No |
| Circle/square region | Yes | Yes, with broader selection tooling | Not applicable | Not applicable | Not applicable | Not applicable |
| Chunk trim/delete tools | No | Available in supported Chunky platforms/versions | No | No | No | No |
| Adaptive future window | Yes, AIMD-style `32/64/384` defaults | Uses its own task scheduler | Different engine scheduler | No pregen window | No pregen window | Different engine scheduler |
| Batched ticket admission | Yes | Implementation differs | Uses its own chunk-system changes | Not applicable | Not applicable | Uses its own chunk-system changes |
| Selected stage parallelism | Yes, scoped to active T2ME work | Delegates generation to the server | Yes, core project capability | Not a chunk-stage scheduler | Optimizes generation work rather than owning a pregen queue | Yes, within a wider chunk rewrite |
| Async coordinate/neighborhood exclusion | Yes, T2ME-local | Not T2ME's lock design | Has its own scheduling/locking design | Not this role | Not T2ME's lock design | Has radius-aware scheduling/locking concepts |
| Noise algorithm optimization | No; it parallelizes the existing `NOISE` stage | No | Broader generation optimization | No | Yes, its focus | Part of broader chunk-system work |
| Normal entity/AI/block-tick optimization | No | No | Not Lithium's broad role | Yes | No | Not Lithium's broad role |
| Lighting replacement | No | No | Project/version dependent engine changes | No | No | Broader chunk system may alter scheduling |
| Region-file implementation replacement | No | No custom writer in T2ME's sense | Includes chunk I/O work | No | No | Part of the broader rewrite |
| Persistent finite-job progress | Yes | Yes in supported versions | Not the same job concept | Not applicable | Not applicable | Not applicable |
| Live pregen boss bar | Yes | Platform/version integration differs | No finite T2ME-style job | Not applicable | Not applicable | Not applicable |
| T2ME target | Forge 1.20.1 only | Multiple server platforms/loaders | Primarily Fabric | Upstream loader/version matrix | Upstream loader/version matrix includes selected Forge/Fabric releases | Upstream loader/version matrix for newer stacks |
| Embedded in T2ME | T2ME code only | No | No | No | No | No |

## Is T2ME faster than Chunky?

**Not demonstrated yet.**

The T2ME dev scheduler is intentionally more aggressive:

- its initial request window is `64`;
- its configured maximum is `384`;
- it admits up to `64` requests in one ticket/distance-manager batch;
- it drains up to `1024` completion events per tick;
- it removes the old per-request coordinator round-trip; and
- it can start selected generation stages on a dedicated worker pool.

For reference, the cited Chunky
[`GenerationTask` snapshot](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L25)
initializes a 50-future generation pipeline. That is useful
architectural context, not a 64-versus-50 benchmark: the tools use different
scheduling paths, and the effective bottleneck can be CPU, memory bandwidth,
heap/GC, disk, locks, or the generator itself.

Possible outcomes on real hardware include:

- T2ME is faster because batching and stage parallelism keep resources busy;
- the tools tie because native generation or storage is the bottleneck;
- Chunky is faster because T2ME's added scheduling, locking, or queue pressure
  costs more than it saves; or
- one wins raw CPS but loses on MSPT, failures, or output validity.

T2ME may be described as "faster than Chunky" only after it passes the
[benchmark gate](BENCHMARKING.md): at least a 10% median throughput win across
at least three paired runs with equivalent work, semantic world parity, zero
generation errors, and acceptable health metrics.

## T2ME versus Chunky

### What overlaps

Both can request a bounded region ahead of player exploration, report
progress, and pause/resume long work. Both ultimately depend on the server's
world generator and storage stack.

### What differs

T2ME 0.2 is a narrow Forge 1.20.1 performance experiment. It trades Chunky's
mature selection and administration ecosystem for:

- a large adaptive future window;
- one distance-manager pass per admission/removal batch;
- a dedicated completion queue;
- a scoped chunk-status worker pool; and
- worker, lock, latency, MSPT, heap, and throttle diagnostics.

Chunky remains the mature baseline for broader shapes, selections, world
administration, and cross-platform operational familiarity.

### Practical choice

Use Chunky when mature tooling and predictable existing workflows matter most.
Evaluate T2ME dev on a copied world when peak Forge 1.20.1 throughput is the
goal and the modpack can be subjected to parity and stress testing.

Never run both pregenerators over the same world simultaneously. T2ME blocks
Chunky by default.

## T2ME versus C2ME

### What T2ME learned from C2ME

C2ME demonstrates several useful principles:

- classify chunk stages instead of treating world generation as one opaque
  task;
- keep scheduling separate from I/O and lighting concerns;
- use asynchronous exclusion rather than parking workers on overlapping work;
  and
- bound active parallelism while allowing the dependency graph to progress.

T2ME independently applies those principles to a much smaller Forge 1.20.1
scope: selected stages, one active pregeneration area, one fixed worker pool,
and T2ME-local coordinate locks.

The relevant C2ME 1.20.1 references include its
[`SchedulingManager`](https://github.com/RelativityMC/C2ME-fabric/blob/ver/1.20.1/c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingManager.java)
and
[`ChunkStatusUtils`](https://github.com/RelativityMC/C2ME-fabric/blob/ver/1.20.1/c2me-threading-worldgen/src/main/java/com/ishland/c2me/threading/worldgen/common/ChunkStatusUtils.java).

### What T2ME is not

T2ME is not a Forge packaging of C2ME. It does not include C2ME's modules,
source, binary, configuration, I/O system, lighting changes, or complete
scheduler. The two projects should not be assumed behaviorally equivalent.

## T2ME versus Lithium

Lithium optimizes many frequently executed vanilla game systems while aiming
to preserve behavior. It is not a pregenerator and is not primarily a
world-generation stage scheduler.

T2ME's "Lithium-inspired" part is a design discipline, not a port:

- batch repeated operations;
- avoid one task allocation per completion where one queue drain is enough;
- use a primitive `long` issuance sequence rather than per-request UUID
  objects;
- use fixed rate buckets instead of an ever-growing completion history; and
- keep hot-path presentation and persistence work off completion threads.

T2ME does not include Lithium code or claim Lithium's optimizations. A
compatible engine-optimization mod may still improve normal server logic that
T2ME does not touch.

## T2ME versus Noisium

Noisium focuses on making parts of world generation, especially noise-related
work, cheaper. T2ME does not reproduce Noisium's algorithms. It leaves the
installed generator's `NOISE` implementation intact and changes where an
eligible stage action begins execution.

The approaches could be conceptually complementary when a loader/version
combination is actually supported, but simultaneous installation is not
automatically safe. Both projects can touch world-generation paths, so the
exact versions and modpack require build, Mixin-conflict, parity, and stress
testing.

## T2ME versus Moonrise

Moonrise is a broader chunk-system rewrite with separate scheduling and
radius-aware coordination concepts. T2ME borrows only the general idea that
overlapping chunk work should be scheduled with explicit spatial ownership.

T2ME's implementation is far narrower:

- its lock manager coordinates only stages that T2ME routes;
- radius zero is used for default threaded stages;
- radius one is used only for opt-in `FEATURES`;
- native lighting, `FULL` conversion, and region I/O remain in place; and
- the engine exists only around an active T2ME job.

T2ME is not a Moonrise backport and does not include Moonrise source or
binaries.

## Canary and other Forge optimization mods

The compatibility guard reports Canary and ModernFix but does not block them.
Canary historically fills a Lithium-style Forge optimization role; its
availability and compatibility must be checked for the exact distribution,
Minecraft version, and modpack.

T2ME blocks known competing pregenerators and broad threading mods by default,
but that list is not exhaustive. A clear guard result is not proof that every
Mixin or generator combination is safe.

## Decision table

| Goal | Starting point |
| --- | --- |
| Mature pregeneration selections and administration | Chunky |
| Experimental maximum-throughput Forge 1.20.1 pregen | T2ME dev on a disposable clone, followed by the benchmark gate |
| Broad async chunk-engine work on a supported Fabric stack | C2ME |
| General game-logic optimization on a supported loader/version | Lithium or a compatible port |
| Targeted noise/world-generation optimization on a supported loader/version | Noisium |
| Broad modern chunk-system rewrite on a supported stack | Moonrise |
| Run multiple chunk schedulers without compatibility testing | Do not |

## License boundary

T2ME is MIT-licensed original code. The projects above are independent:

| Project | Upstream license reference | T2ME relationship |
| --- | --- | --- |
| Chunky | [GPL-3.0](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/LICENSE) | Behavioral baseline and workflow comparison only |
| C2ME | [MIT](https://github.com/RelativityMC/C2ME-fabric/blob/ver/1.20.1/LICENSE) | Architectural inspiration and cited public references |
| Lithium | [LGPL-3.0](https://github.com/CaffeineMC/lithium/blob/1.20.1/LICENSE.txt) | Optimization principles and comparison only |
| Noisium | [LGPL-3.0](https://github.com/Steveplays28/noisium/blob/1.20-1.20.1/LICENSE) | World-generation comparison only |
| Moonrise | [GPL-3.0](https://github.com/Tuinity/Moonrise/blob/mc/26.2/LICENSE.md) | Scheduling/locking concepts and comparison only |

No source, binary, asset, or dependency from these projects is embedded,
shaded, translated, or redistributed by T2ME. See
[Third-party notices](../THIRD_PARTY_NOTICES.md).
