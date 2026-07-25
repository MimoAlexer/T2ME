# T2ME versus Chunky benchmark protocol

This protocol defines the evidence required before T2ME may claim that it is
faster than Chunky. It is designed for long Forge 1.20.1 pregeneration runs,
where world state, JIT warmup, filesystem cache, thermal behavior, and
selection semantics can otherwise overwhelm the scheduler difference.

## Release gate

T2ME passes the speed gate only when all of the following are true:

1. At least three paired T2ME/Chunky runs complete; five pairs are preferred.
2. Both tools start from byte-identical clones of the same ungenerated base
   world for every pair.
3. The median normalized T2ME throughput is at least 10% higher:

   ```text
   throughput win (%) =
       (median(T2ME new FULL chunks/second)
        / median(Chunky new FULL chunks/second) - 1) * 100

   required result: >= 10.0%
   ```

4. The median of the per-pair T2ME/Chunky throughput ratios is also at least
   `1.10`; this prevents one unusually slow baseline run from deciding the
   result.
5. Every required target chunk reaches `FULL`, all region files parse, and
   semantic sampling finds no terrain, biome, heightmap, or structure
   divergence attributable to T2ME.
6. Neither profile logs an unexplained generation exception, missing holder,
   ticket leak, invalid chunk NBT, watchdog event, crash, or forced retry.
7. T2ME p95 and p99 MSPT are no more than 10% worse than Chunky's respective
   values, unless a stricter target-modpack health budget was declared before
   the runs.
8. T2ME stays inside the declared heap budget without an out-of-memory event
   or sustained full-GC cycle. Peak heap and total GC pause must be reported,
   not hidden.
9. A separate controlled restart test resumes without missing target chunks,
   advancing the wrong job, or retaining T2ME region tickets after
   completion.

If any condition fails, the result is "gate not passed." A throughput-only win
does not qualify.

## Benchmark claims

The claim must name its scope. Acceptable examples are:

> T2ME 0.2 commit `<sha>` was 13.4% faster in median normalized throughput than
> Chunky `<version>` across five paired 10,000-block circle runs on hardware
> `<description>` with modpack `<hash>`.

Unacceptable examples are:

- "T2ME is always faster."
- "384 is greater than 50, therefore T2ME is 7.68 times faster."
- "One run looked faster."
- "The server felt smoother."

Do not generalize a result to a different processor, JVM, heap, storage
device, seed, dimension, generator, modpack, radius, or concurrency profile
without repeating the test.

## Required scenarios

### Scenario A: exact-selection scheduler comparison

Use a square centered on a chunk boundary-aligned coordinate. Square
selection makes the expected coordinate set easy to calculate independently
and avoids circle-boundary differences between tools.

Recommended minimum:

```text
dimension:     minecraft:overworld
center:        0, 0
radius:        8192 blocks
shape:         square
players:       0
world state:   target region never generated
```

This is the primary scheduler release gate.

### Scenario B: target deployment workload

Use the intended operational workload:

```text
dimension:     minecraft:overworld
center:        actual shared spawn
radius:        10000 blocks
shape:         circle
players:       0
world state:   target region never generated
```

T2ME includes any chunk whose bounds intersect the block-space circle.
Chunky's boundary rule may differ. Therefore:

1. record the actual newly generated `FULL` chunk set for each run;
2. verify complete coverage of the independently defined required circle;
3. report both total wall time and normalized new-FULL-chunks/second; and
4. do not interpret fewer generated boundary chunks as faster generation.

A broad "faster for the 10k spawn circle" claim requires Scenario B to pass
the same 10% median gate.

### Scenario C: modded stress cases

Repeat a smaller but still saturated region in every dimension and generator
configuration used by the target pack. Include structure-heavy and
feature-heavy biomes when applicable.

`threadedWorldgen.structures` and `threadedWorldgen.features` remain `false`
for the default release profile. If either option is enabled, it creates a
separate experimental profile that needs its own full parity and speed gate.

## Experimental controls

Record these before the first run:

- T2ME commit SHA and JAR SHA-256;
- Chunky version, commit/build identifier, and JAR SHA-256;
- Minecraft, Forge, Java vendor/build, and every mod/config SHA-256;
- world seed, generator settings, dimension, center, shape, and radius;
- processor model, physical/logical core count, RAM, storage model/filesystem,
  operating system/build, and virtualization limits;
- JVM flags, min/max heap, garbage collector, process priority, and CPU
  affinity;
- view distance, simulation distance, spawn-chunk setting, and server
  properties;
- T2ME and Chunky configuration;
- profiler/telemetry versions and sampling settings;
- ambient/initial CPU temperature where thermal throttling is possible; and
- the filesystem-cache policy.

The only intentional difference between profiles must be the pregenerator and
its required configuration. Keep all other performance mods present in both
profiles, or absent from both.

T2ME and Chunky must never be active in the same run.

## World preparation

1. Create one base world with the final seed, generator settings, datapacks,
   mods, and configs.
2. Generate only the minimum area needed to boot and perform the declared
   warmup; keep the measured target untouched.
3. Stop the server normally.
4. Validate and archive that base world.
5. Before every timed run, make a fresh byte-for-byte clone of the base
   archive.
6. Give the clone a unique run identifier and keep it after completion for
   validation.
7. Confirm the expected target region has no pre-existing chunk records.

Never compare a fresh target in one tool with an already generated target in
the other. Do not "reset" a world by deleting an uncertain subset of region
files; restore the known base archive.

## Warmup and run order

JIT and filesystem caches can bias the first tool tested.

1. Boot every run from a fresh world clone and fresh JVM process.
2. Use the same fixed warmup script outside the measured region. A suitable
   warmup runs for 10 minutes and loads a small sacrificial area not included
   in the result.
3. Begin measurement only after the same warmup completion condition.
4. Alternate run order by pair:

   ```text
   pair 1: T2ME, Chunky
   pair 2: Chunky, T2ME
   pair 3: T2ME, Chunky
   ```

5. For five or more pairs, randomize order in advance with the seed recorded.
6. Return CPU temperature and background load to the declared range before
   every run.
7. Use one documented filesystem-cache policy. If caches cannot be cleared
   reproducibly, reboot between runs or rely on balanced randomized order and
   disclose that limitation.

Do not discard a completed run because it is inconvenient. Define invalid-run
criteria before testing, such as power loss or unrelated host activity, and
retain the evidence for every excluded run.

## Tool profiles

### T2ME default development profile

Use the committed defaults unless a separately named tuned profile is being
tested:

```toml
[scheduler]
minInFlight = 32
initialInFlight = 64
maxInFlight = 384
maxDispatchPerTick = 64
maxCompletionsPerTick = 1024
controlIntervalTicks = 20
targetTickMillis = 48
hardStopTickMillis = 65
minHeapHeadroomMiB = 512
reduceWhenPlayersOnline = false

[threadedWorldgen]
enabled = true
threads = 0
structures = false
features = false
```

Capture `/t2me config show`, `/t2me status`, and `/t2me metrics` at start,
once per minute, and completion. Automated console capture is preferred to
manual transcription.

### Chunky baseline

Use an unmodified official Chunky build and its documented default generation
concurrency unless the claim explicitly names another profile. Record every
selection and task setting. The current source reference for its generation
task is the cited
[`GenerationTask.java` snapshot](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L25).

Use Chunky's documented commands to select the same dimension, center, shape,
and radius. Save its start, progress, and completion output.

### Tuned profiles

Tuning is allowed only after the default comparison and must be reported as a
separate result. Do not tune T2ME against the measured baseline and then
present that result as a defaults comparison. Apply the same number of tuning
trials and the same time budget to both tools.

## Measurement

### Timing boundary

Start the stopwatch when the server accepts the final start command and stop
it when the tool reports completion. Include:

- adaptive throttling;
- queue drain time;
- pauses caused by the tool;
- retries;
- saving during the job; and
- any scheduler overhead.

Exclude server boot and the fixed pre-run warmup. Do not subtract "bad" pauses
after the fact.

### Throughput

Use:

```text
normalized throughput =
    count of chunks that changed from absent/non-FULL to valid FULL
    / elapsed wall-clock seconds
```

Do not use a tool's target number without validating the output world. Report
the tool-reported CPS as a separate diagnostic.

### Health and resource metrics

Collect at least:

| Metric | Required summary |
| --- | --- |
| Wall-clock duration | Exact start/end and elapsed seconds |
| New valid `FULL` chunks | Count and independently derived expected count |
| Throughput | Mean for run, then median across runs |
| MSPT | Median, p95, p99, maximum, and time above 50/65/100 ms |
| TPS | Median and time below declared target |
| Heap | Used-heap time series and peak |
| GC | Collection count, total pause, p95 pause, longest pause |
| CPU | Process average/peak and evidence of thermal throttling |
| Storage | Read/write bytes, utilization, and queue latency if available |
| T2ME internals | Window, admission, active workers, queued stages, locks, latency, retries |
| Errors | Generation exceptions, retries, stalls, watchdog events, crashes |

Use the same profiler in both profiles. Java Flight Recorder and GC logs are
suitable when enabled with identical settings. A server telemetry mod is also
acceptable if its exact version/config is identical.

Profiling has overhead. The result remains valid only when that overhead is
applied symmetrically.

## World validation

Compressed region files are not expected to be byte-identical: timestamps,
save ordering, compression layout, and incidental metadata can differ. Validate
semantics instead.

For every completed run:

1. Stop the server normally and retain the complete log.
2. Parse every target `.mca` file and every present chunk NBT payload.
3. Confirm every independently expected coordinate exists at `FULL`.
4. Confirm no out-of-target coordinate is counted as useful work.
5. Check status, position, data version, and required heightmaps.
6. Compare deterministic samples against a control generation of the same
   seed and modpack:
   - surface and underground block states;
   - biome values;
   - heightmaps;
   - structure starts/references; and
   - dimension-specific generator output.
7. Sample all boundary chunks and a seeded random set of at least 1% of
   interior chunks, with a minimum of 1,000 when the region is large enough.
8. Reopen the completed world, traverse the region under scripted load, save,
   stop, and parse it again.
9. Search logs for chunk, region, NBT, lighting, structure, ticket, Mixin,
   concurrency, and watchdog errors.

Any unexplained semantic mismatch blocks release even if the world appears to
load.

## Controlled restart test

Run this separately from the uninterrupted speed measurements:

1. Start from a fresh base clone.
2. Begin T2ME pregeneration and record the job UUID and counters.
3. After at least 10% progress, issue a normal server stop.
4. Confirm the log shows ticket release and the snapshot is saved.
5. Restart with the same JAR/config and wait for the 200-tick auto-resume.
6. Confirm the same job resumes, no coordinate is skipped, and stale
   completions do not update the new process.
7. Let the job complete and run the full world validation.
8. Confirm no T2ME tickets remain after completion.

A hard-kill test is useful additional evidence, but it must not replace the
normal-stop test and must not be performed on an irreplaceable world.

## Analysis

For each tool, report the individual run results and:

- median throughput;
- median elapsed time;
- median p95/p99 MSPT;
- median peak heap and GC pause;
- median absolute deviation or interquartile range; and
- 95% bootstrap confidence intervals when at least five runs are available.

Also report paired ratios:

```text
pair ratio = T2ME throughput / Chunky throughput
```

Do not average percentages directly. Do not hide the slowest run. With only
three pairs, label the evidence preliminary even when the numeric gate passes;
five or more pairs are preferred before a public release claim.

## Results template

### Environment

| Field | Value |
| --- | --- |
| Date | |
| Scenario | |
| T2ME commit / JAR SHA-256 | |
| Chunky version / JAR SHA-256 | |
| Modpack/config hash | |
| World archive SHA-256 | |
| Minecraft / Forge / Java | |
| CPU / RAM / storage / OS | |
| JVM flags / heap / GC | |
| Telemetry | |
| Cache and warmup policy | |

### Runs

| Pair | Order | Tool | Elapsed s | New FULL chunks | Chunks/s | p95 MSPT | p99 MSPT | Peak heap | GC pause | Errors |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | A first | T2ME | | | | | | | | |
| 1 | B second | Chunky | | | | | | | | |
| 2 | B first | Chunky | | | | | | | | |
| 2 | A second | T2ME | | | | | | | | |
| 3 | A first | T2ME | | | | | | | | |
| 3 | B second | Chunky | | | | | | | | |

### Gate

| Requirement | Result | Pass |
| --- | --- | --- |
| Median T2ME throughput | | |
| Median Chunky throughput | | |
| Median throughput win >= 10% | | |
| Median paired ratio >= 1.10 | | |
| p95/p99 MSPT within budget | | |
| Heap and GC within budget | | |
| Zero unexplained generation errors | | |
| Complete target coverage | | |
| Semantic parity | | |
| Restart recovery | | |

Final status: **PASS / FAIL / INCONCLUSIVE**

Until this table is populated with reproducible evidence, the documented
answer to "Is T2ME faster than Chunky?" remains **not proven**.
