# Validation record — 2026-09-26

This record separates correctness checks, restart checks, and generation timings.
Results apply to the artifacts and environment below; they do not establish a
universally fastest pregenerator or guarantee every modpack's behavior.

## Current artifact

| Item | Identity |
| --- | --- |
| Source revision | `91851818605999144cca83a8968f2ba5be9321b7` |
| T2ME | `0.2.0`, frozen as `run-benchmark/t2me-refill-candidate.jar` |
| T2ME JAR SHA-256 | `21e20f0b6c622e490bf8eda96ead3cf4c520ef6cf04f28ea2a9f1dc0736fabd5` |
| Comparison artifact | Chunky `1.3.146` for Forge |
| Chunky JAR SHA-256 | `996a3c9b53e6c0e0850fb9e78d3546d2c37a1b789ddaf529b17e4fe62ab23141` |

New T2ME jobs use region traversal with Hilbert locality, a maximum of 64
in-flight requests, 32 admissions per tick, and target/hard-stop thresholds of
45/55 ms. The configured heap reserve is 1 GiB, capped at one quarter of the
JVM's maximum heap. Player backoff remains enabled. Completion-driven refills
and tick-end dispatch share the same admission budget. Chunky's tested default
uses the `region` pattern and 50 working permits.

## Correctness checks

The clean Java 17 build, unit suite, and Forge integration suite passed:

| Check | Result | Retained evidence |
| --- | --- | --- |
| JUnit | 89 tests; zero failures/errors | `build/test-results/test/TEST-*.xml` |
| Forge GameTests | Both required tests passed | `run-benchmark/refill-validation.log` |
| Production packaging | Reobfuscated JAR built successfully | Same validation log |
| Python benchmark harness | 25 tests passed | `tools/test_benchmark_server.py` |

The Java tests cover both traversal orders, cursor/checkpoint compatibility,
retry persistence, invalid saved data, limiter recovery, completion isolation,
and NBT ownership. GameTests exercise generation, pause/resume, disk checkpoint
reload, cancellation/replacement, and saving. GameTest code is excluded from
the production JAR.

During earlier packaged testing, vanilla 1.20.1 emitted an I/O-worker
`ConcurrentModificationException` while saving a chunk. A pending save's mutable
NBT could also be returned to a reader; vanilla datafixing then added/removed
`__context` while the writer serialized the same tag. The current artifact
isolates `IOWorker.loadAsync` results with a deep copy before exposing them to
readers.

The regression GameTest gates the I/O mailbox so a load sees pending save data
before serialization. It checks distinct ownership, mutates the loaded root
and a nested tag, then verifies that the pending original and flushed disk
contents remain unchanged. This checks the ownership defect deterministically;
it does not rely on randomly reproducing the exception.

To repeat the automated checks with a Java 17 JDK and Python 3.11+:

```powershell
.\gradlew.bat clean test build runGameTestServer --console=plain
python -m unittest discover -s tools -p 'test_*.py'
```

## Benchmark environment and method

| Item | Value |
| --- | --- |
| OS / CPU | Windows 11; Intel Core i7-14700HX, 20 cores / 28 threads |
| Physical RAM | 34,053,414,912 bytes |
| Storage | Samsung MZAL81T0HFLB-00BL2, 1 TB NVMe |
| Java / heap | Temurin 17.0.19; `-Xms2G -Xmx4G` |
| Minecraft / Forge | 1.20.1 / 47.4.21 |
| World | Prepared vanilla world, seed `234567890` |
| Players / distances | No players; view distance 5, simulation distance 5 |
| Selection | Overworld square, block center `(8192,8192)`, radius 512 |
| Expected coverage | Chunk coordinates `480..544` on both axes: 4,225 chunks |
| Prepared `level.dat` SHA-256 | `fbceb3bca1677e2a084207b95d8456c11c28a4045447c427548a3ffed2d193de` |

Each candidate runs alone on a separate fresh copy of the same template.
Two pairs alternate candidate order. Timing starts with the start command;
the preferred result ends at the subsequent `save-all flush` acknowledgement.
Every selected chunk must then have persisted `Status=full` before shutdown.
Any server error, storage failure, incomplete selection, or unsuccessful
shutdown invalidates the comparison. See [Benchmarking](BENCHMARKING.md) for
the complete procedure and measurement boundaries.

The current run's evidence directory is `run-benchmark/region-refill-default/`.
To reproduce it, prepare the inputs described above, adjust the Java path, and
use a new output directory:

```powershell
python tools/benchmark-server.py `
  --server-template run-benchmark/template `
  --world-template run-benchmark/template/world `
  --t2me-jar run-benchmark/t2me-refill-candidate.jar `
  --chunky-jar run-benchmark/Chunky-1.3.146.jar `
  --output run-benchmark/region-reproduction `
  --repeats 2 --radius 512 --center-x 8192 --center-z 8192 `
  --command C:/Java/jdk-17/bin/java.exe -Xms2G -Xmx4G `
  '@libraries/net/minecraftforge/forge/1.20.1-47.4.21/win_args.txt' nogui
```

### Current paired results

| Run order | Candidate | Completion (s) | Completion and flush (s) | Verified FULL |
| --- | --- | ---: | ---: | ---: |
| Pair 1, first | T2ME | 61.560 | 62.231 | 4,225 |
| Pair 1, second | Chunky | 62.903 | 63.388 | 4,225 |
| Pair 2, first | Chunky | 61.781 | 62.111 | 4,225 |
| Pair 2, second | T2ME | 62.218 | 62.974 | 4,225 |
| Median | T2ME | 61.889 | 62.603 | |
| Median | Chunky | 62.342 | 62.750 | |

All four runs passed the FULL checks and clean shutdown with no server errors;
the completed report has `comparable=true`. The T2ME median including flush
was 0.147 seconds (0.23%) lower. T2ME won one pair and lost the other, so this
is **effectively a tie, not an established performance advantage**. Two repeats
on one seed do not support a claim that T2ME is the fastest pregenerator.
The [sanitized JSON record](../benchmarks/results/2026-09-26-forge-1.20.1.json)
retains unrounded values, exact configurations, artifact hashes, and provenance.

Player latency and comparable mean/p95/p99 MSPT were not measured. Limiter
blocked-tick counters describe T2ME admission behavior, not a responsiveness
comparison with Chunky. Filesystem caches were not cleared between runs.

## Restart evidence and remaining scope

The exact current production JAR passed a real process stop/start check on the
4,225-chunk selection. The last status before orderly shutdown showed 116
completed. Outstanding requests continued finishing during shutdown, and a
new server process restored the same job UUID with 166 completed and 54 pending
coordinates. It initially paused, then resumed automatically after the
configured 10-second delay and completed all 4,225 chunks.

After `save-all flush`, every selected chunk was verified FULL. Both processes
shut down cleanly and their complete console logs contained no server errors.
The [restart record](../benchmarks/results/2026-09-26-restart.json) identifies
the artifact and retained evidence under `run-benchmark/production-restart-region/`.
This tests normal shutdown and restart, not abrupt power loss or recovery from
arbitrary third-party worldgen failures. Modpack compatibility and player
latency under load remain unmeasured.

## Historical runs, not current performance claims

The preceding region implementation (`4effa829`, JAR SHA-256
`7e11f95c52e4407af4ce72e0131bf1a98424e5fd55ad11607f3eb00140f4f92d`)
passed two paired 4,225-chunk runs. Its median including flush was 63.287 s
against Chunky's 62.209 s, about 1.7% longer. This prompted the queued-refill
optimization. These are separate sessions with possible machine variation;
their absolute times do not isolate the optimization's causal effect.

The earlier NBT-fixed spiral implementation (`069da164`, JAR SHA-256
`614c051f13c0fc131c21da6c2469aa3bf2f54ee985baabd993cff7adda3b9cd8`)
passed one 4,225-chunk pair with the 64/32 profile: 74.019 s for T2ME and
74.041 s for Chunky including flush, also an effective tie.

Both older runs below used T2ME JAR SHA-256
`6e6a3c6544bd27c106796430bac30547a0af0d0a5f80bf6754d1881a3339f3ec` and the same
Chunky artifact identified above.

- `run-benchmark/aligned-default/`: one 289-chunk smoke pair verified FULL
  coverage and clean shutdown. T2ME took 8.079 s to completion / 8.754 s through
  flush; Chunky took 7.099 s / 7.996 s. This small, older-artifact run favored
  Chunky and is not evidence about the current artifact.
- `run-benchmark/large-default/`: the earlier two-pair report initially printed
  medians of 70.286 s for T2ME and 62.343 s for Chunky through flush. The second
  T2ME run logged the chunk-save exception described above. **The comparison is
  invalid**, despite its old report saying `comparable=true` and later FULL
  checks passing. The harness now rejects such server errors; these numbers
  must not be used as validated speed results.

Logs and generated worlds are retained locally under ignored build/benchmark
directories; this document records their identities rather than distributing
those worlds or server installations.
