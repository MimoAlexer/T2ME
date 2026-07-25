# Acknowledgements

T2ME is built with the Minecraft Forge toolchain and Mojang's official 1.20.1
mappings.

The 0.2 development architecture was informed by public work across the
Minecraft performance community:

- [C2ME](https://github.com/RelativityMC/C2ME-fabric) demonstrated the value
  of classifying chunk stages, separating schedulers from I/O/lighting
  concerns, and coordinating concurrent generation work asynchronously.
- [Moonrise](https://github.com/Tuinity/Moonrise) informed the general idea of
  explicit spatial ownership for overlapping chunk work.
- [Lithium](https://github.com/CaffeineMC/lithium) exemplifies measurement-led
  optimization, specialized hot-path data, batching, and avoiding unnecessary
  allocations.
- [Noisium](https://github.com/Steveplays28/noisium) provides an important
  comparison point for optimization inside world-generation work.
- [Chunky](https://github.com/pop4959/Chunky) provides the mature
  pregeneration workflow and performance baseline that T2ME must beat under a
  controlled test.

T2ME remains an independent implementation. It does not copy, embed, shade,
translate, or redistribute source or binaries from those projects. Their
names do not imply endorsement or compatibility.

See [Third-party notices](THIRD_PARTY_NOTICES.md) for license references and
the contribution boundary, [Comparison](docs/COMPARISON.md) for scope, and
[Benchmarking](docs/BENCHMARKING.md) for the evidence required before a speed
claim.

Minecraft is a trademark of Microsoft. T2ME is not affiliated with or
endorsed by Mojang Studios or Microsoft.
