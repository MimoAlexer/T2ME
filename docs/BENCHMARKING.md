# Reproducible Forge comparison

`tools/benchmark-server.py` measures T2ME and Chunky on separate copies of the
same prepared Forge 1.20.1 server and world. It uses Python 3.11 or newer, with
no Python dependencies. It does not install Forge, download mods, accept the
Minecraft EULA, modify the source server, or delete generated runs.

A successful comparison describes **this seed, region, modpack, configuration,
JVM, storage, and machine**. It does not establish a universally fastest mod.
The planner benchmark in `benchmarks/` measures a different thing: traversal
CPU overhead, without a Minecraft server.

## Prepare the inputs

1. Install Forge 47.4.21 in a dedicated benchmark template directory. Start it
   once with Java 17, set the desired world seed and generator, allow startup
   to finish, and stop it cleanly. Accept the EULA yourself if you agree to it.
2. Keep the resulting world as the world template. No selected target chunk
   may already exist, including partially generated chunks. Do not run either
   pregenerator on the template. Existing `t2me_jobs.dat` and saved Chunky tasks
   are rejected. Stop every process using the template and keep it unchanged.
3. Obtain the built T2ME JAR and a compatible Forge 1.20.1 Chunky JAR. Set
   Chunky's language to English so completion messages can be checked. The
   harness checks the candidate mod IDs inside each JAR; filenames are not
   used to identify mods.
4. Put the desired shared mods in the template's `mods/`. Candidate T2ME and
   Chunky JARs are excluded while copying, then exactly one candidate is added
   to each run. Remove other pregenerators and background map renderers.
   Preserve the same worldgen mods, Java heap, view distance, and other server
   settings for both candidates.
5. Configure each pregenerator's intended concurrency and health limits in
   the template before the comparison. Report those settings with results.
   Comparing conservative defaults with a tuned setup answers a different
   question from comparing tuned setups with the same responsiveness target.

The template may contain its world directory; the harness excludes directories
containing `level.dat` from the server copy and copies only the explicitly
selected world to `benchmark-world`. Templates containing symlinks or junctions
are rejected. Every run binds only to localhost with an automatically assigned
port, disables query/RCON/status, and enables the whitelist in its copied
`server.properties`.

## Run

PowerShell example; adjust the Java and input paths. All arguments after
`--command` are passed directly to the server process, without a shell. Use
Java itself instead of `run.bat`, a shell script, or a wrapper that leaves a
child server process behind.

```powershell
python tools/benchmark-server.py `
  --server-template C:/bench/forge-template `
  --world-template C:/bench/forge-template/world `
  --t2me-jar C:/builds/t2me-0.2.0.jar `
  --chunky-jar C:/builds/Chunky-1.3.146.jar `
  --output C:/bench/results/run-01 `
  --repeats 3 --radius 128 --center-x 8192 --center-z 8192 `
  --command C:/Java/jdk-17/bin/java.exe -Xms4G -Xmx4G `
  '@libraries/net/minecraftforge/forge/1.20.1-47.4.21/win_args.txt' nogui
```

On Linux use the same options with your Java executable and the installed
Forge `unix_args.txt`. JVM argument files referenced by relative paths are
resolved inside each copied server. Use the same JVM options for all runs;
the exact command and both candidate JAR SHA-256 hashes are recorded.

The output directory must not exist and must be outside both templates. Check
available disk space: all worlds, logs, configurations, and server copies are
retained. Three repeats create six separate servers, run sequentially.

Default time limits are 300 seconds for startup, 1,800 seconds for generation,
and 120 seconds for shutdown. Override these with `--startup-timeout`,
`--generation-timeout`, and `--shutdown-timeout`. `--settle-seconds` defaults to
10 seconds after the server is ready. On failure or interruption the harness
requests `stop`; an unresponsive benchmark process is terminated after the
shutdown deadline. The comparison is then rejected and its files retained.

## Identical selection and validation

The default square uses block center `(8192,8192)` and radius `128`. Its block
bounds are `(8064,8064)` through `(8320,8320)`, covering exactly chunk coordinates
`504..520` in both axes: **289 chunks**. The T2ME command is:

```text
t2me pregen startat minecraft:overworld 8192 8192 128 square
```

Chunky receives the same block corners using its documented selection commands:

```text
chunky world minecraft:overworld
chunky corners 8064 8064 8320 8320
chunky start
```

The harness requires both centers and the radius to be divisible by 16, with
a radius between 32 and 2048. Chunky 1.3.146 rounds its square chunk radius up
using `ceil(radius / 16)` and includes both outer rows. Matching chunk-aligned
endpoints give both candidates the same inclusive square. For example, radius
127 would give T2ME 256 chunks but Chunky 289; the harness rejects that input.
Other versions or configurations whose completion counts differ are also
rejected. Use radius 512 or 1024 for a longer run after the small smoke test.
Only the overworld is supported. If a compatible Chunky build uses a different
name for the overworld, set `--chunky-world` and verify the resulting selection
in its retained console log.

The harness refuses a comparison unless all of the following hold:

- Each copied world has no selected chunks before pregeneration.
- T2ME's target and completion count, and Chunky's reported completion count,
  equal the independently calculated square size.
- The server acknowledges `save-all flush` after completion.
- Every selected coordinate has a persisted chunk with NBT `Status=full` or
  `minecraft:full` immediately after that flush, before the additional save
  performed by server shutdown.
- Every benchmark process shuts down successfully.

Completion counts alone are insufficient: pregenerators can report processing
a chunk that was skipped or failed. The persisted FULL check detects holes in
the selected square. It does not establish byte-identical worlds, validate
every generated structure, or prove that all neighboring dependency chunks
match; inspect the retained worlds for those questions.

## Read the results

`report.json` contains each run and medians for both candidates. Each run also
has `result.json`, `console.log`, `commands.jsonl`, and its complete server tree.
The first pair runs T2ME then Chunky; the second reverses that order, alternating
thereafter. Any failed, timed-out, or mismatched run leaves `comparable=false`,
exits unsuccessfully, and prevents a speed ratio from being reported.

Two elapsed times are measured from sending the start command:

- `completion_seconds`: until the console reports completion.
- `completion_and_flush_seconds`: until the subsequent `save-all flush`
  acknowledgement. Use this when reporting completed and saved work.

Both exclude JVM startup, settling, world copying, post-flush verification,
and shutdown. `chunky_time_divided_by_t2me_time` above 1 means T2ME took less
time in that measurement; below 1 means Chunky took less time. Publish all run
values, not just the best one. Small runs are smoke tests and have substantial
startup/JIT and measurement noise. Use several larger regions/seeds, repeated
paired runs, an idle machine, and report CPU, memory, OS, storage, Java version,
shared mod versions, configs, and server responsiveness alongside timings.
The harness does not clear filesystem caches or measure player latency/MSPT.

Command and output references:
[Chunky command documentation](https://github.com/pop4959/Chunky/wiki/Commands),
[Chunky corners implementation](https://github.com/pop4959/Chunky/blob/master/common/src/main/java/org/popcraft/chunky/command/CornersCommand.java),
and [Chunky English messages](https://github.com/pop4959/Chunky/blob/master/common/src/main/resources/lang/en.json).
