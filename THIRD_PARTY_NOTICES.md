# Third-party notices

T2ME is original MIT-licensed software. The projects below are cited for
architectural context, behavioral comparison, and benchmark baselines. They
are not T2ME dependencies and no source code, binary, asset, mapping, or
configuration from them is included, shaded, translated, or redistributed in
the T2ME JAR.

The license links are provided so contributors can preserve the boundary and
review the upstream terms before proposing any future integration.

## C2ME

- Project: Concurrent Chunk Management Engine
- Source: <https://github.com/RelativityMC/C2ME-fabric>
- Forge-relevant research reference:
  [1.20.1 branch](https://github.com/RelativityMC/C2ME-fabric/tree/ver/1.20.1)
- License: [MIT](https://github.com/RelativityMC/C2ME-fabric/blob/ver/1.20.1/LICENSE)
- Relationship: architectural research only

T2ME is informed by public C2ME concepts such as chunk-stage classification,
separation of scheduling concerns, bounded active parallelism, and
future-based exclusion. T2ME implements its own Forge 1.20.1 scheduler, scope,
locks, worker pool, configuration, and lifecycle. It is not a port, fork, or
packaging of C2ME.

## Chunky

- Project: Chunky
- Source: <https://github.com/pop4959/Chunky>
- Generation-task reference:
  [`GenerationTask.java`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L25)
- License:
  [GPL-3.0](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/LICENSE)
- Relationship: independent benchmark baseline and workflow comparison

T2ME does not include Chunky code. Public Chunky behavior and source are used
to define a reproducible performance baseline and to explain differences in
future-pipeline design. T2ME and Chunky must not own pregeneration for the
same test world at the same time.

## Lithium

- Project: Lithium
- Source: <https://github.com/CaffeineMC/lithium>
- Research reference:
  [1.20.1 branch](https://github.com/CaffeineMC/lithium/tree/1.20.1)
- License: [LGPL-3.0](https://github.com/CaffeineMC/lithium/blob/1.20.1/LICENSE.txt)
- Relationship: optimization principles and scope comparison only

T2ME does not port or reproduce Lithium patches. Its Lithium-informed
development principles are generic: batch repeated work, reduce hot-path
allocations, prefer compact data, avoid redundant tasks, and measure before
claiming a performance improvement.

## Noisium

- Project: Noisium
- Source: <https://github.com/Steveplays28/noisium>
- Forge 1.20/1.20.1 reference:
  <https://github.com/Steveplays28/noisium/tree/1.20-1.20.1>
- License:
  [LGPL-3.0](https://github.com/Steveplays28/noisium/blob/1.20-1.20.1/LICENSE)
- Relationship: world-generation performance comparison only

Noisium optimizes world-generation work. T2ME does not include or reproduce
its noise-generation algorithms; T2ME instead schedules the installed
generator's existing chunk-status stage.

## Moonrise

- Project: Moonrise
- Source: <https://github.com/Tuinity/Moonrise>
- License:
  [GPL-3.0](https://github.com/Tuinity/Moonrise/blob/mc/26.2/LICENSE.md)
- Relationship: scheduling and spatial-locking concepts only

Moonrise is a broad chunk-system rewrite. T2ME independently implements a
small, job-scoped future-tail lock for selected Forge 1.20.1 generation
stages. It does not backport Moonrise, replace the complete chunk system, or
include Moonrise code.

## Independent implementation policy

Contributions to T2ME must follow this clean-room-style boundary:

1. Describe the performance problem and desired observable behavior.
2. Implement the solution independently against Minecraft/Forge mappings and
   APIs.
3. Do not copy, translate, transcribe, decompile, shade, or embed third-party
   implementation code.
4. Do not copy project-specific tests, constants, comments, configuration
   text, or internal structure unless the upstream license has been reviewed
   and its obligations are intentionally adopted.
5. Attribute public architectural ideas and benchmark baselines in this file.
6. Validate behavior with T2ME-owned tests and controlled benchmarks.
7. If any third-party code is intentionally incorporated in the future, stop
   calling that part an independent implementation and update T2ME's license,
   source notices, distribution contents, and build metadata before release.

This policy is intended to keep the current T2ME distribution license-simple.
It is not legal advice and does not override an upstream project's license.

## Platform acknowledgements

T2ME is built for Minecraft Forge and uses Mojang's official mappings in
development. Forge, Sponge Mixin, Gradle, and other build/runtime dependencies
remain governed by their own licenses and notices as distributed by their
respective projects.

Minecraft is a trademark of Microsoft. T2ME is not affiliated with or
endorsed by Mojang Studios or Microsoft.
