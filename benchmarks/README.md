# Planner benchmark

This dependency-free benchmark compares the original T2ME planner algorithm
with the current implementation. It verifies matching target counts and ordered
sequence hashes, warms up both paths, and reports medians. It measures planning
CPU overhead only: it makes no claim about Minecraft generation throughput or
performance relative to Chunky.

Run from the repository root with a Java 17 or newer JDK. Both commands must use
the same JDK (check `java -version` and `javac -version`).

PowerShell:

```powershell
New-Item -ItemType Directory -Force build/planner-benchmark | Out-Null
javac --release 17 -d build/planner-benchmark src/main/java/dev/t2me/PregenShape.java src/main/java/dev/t2me/SpiralChunkPlan.java benchmarks/dev/t2me/PlannerBenchmark.java
java -cp build/planner-benchmark dev.t2me.PlannerBenchmark 20000
```

Linux/macOS:

```sh
mkdir -p build/planner-benchmark
javac --release 17 -d build/planner-benchmark src/main/java/dev/t2me/PregenShape.java src/main/java/dev/t2me/SpiralChunkPlan.java benchmarks/dev/t2me/PlannerBenchmark.java
java -cp build/planner-benchmark dev.t2me.PlannerBenchmark 20000
```

Use an idle machine and repeat runs. Construction of the optimized planner is
batched because it is too short for reliable single-invocation timing. This is
a diagnostic microbenchmark, not JMH or an end-to-end generation benchmark.
