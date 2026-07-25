package dev.t2me;

import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class CompatibilityGuard {
    private static final List<KnownMod> COMPETING_PREGENERATORS = List.of(
            new KnownMod("chunky", "Chunky"),
            new KnownMod("c2me", "C2ME"),
            new KnownMod("c2me_forge", "C2ME Forge"),
            new KnownMod("chunk_pregenerator", "Chunk Pregenerator")
    );
    private static final List<KnownMod> THREADING_MODS = List.of(
            new KnownMod("dimthread", "Dimensional Threading"),
            new KnownMod("dimthreads", "Dimensional Threading"),
            new KnownMod("mcmt", "MCMT")
    );

    private CompatibilityGuard() {
    }

    public static Report inspect() {
        ModList mods = ModList.get();
        List<String> competing = loadedNames(mods, COMPETING_PREGENERATORS);
        List<String> threading = loadedNames(mods, THREADING_MODS);
        return new Report(
                competing,
                threading,
                mods.isLoaded("canary"),
                mods.isLoaded("modernfix")
        );
    }

    private static List<String> loadedNames(ModList mods, List<KnownMod> knownMods) {
        List<String> loaded = new ArrayList<>();
        for (KnownMod knownMod : knownMods) {
            if (mods.isLoaded(knownMod.id())) {
                loaded.add(knownMod.name() + " (" + knownMod.id() + ")");
            }
        }
        return List.copyOf(loaded);
    }

    private record KnownMod(String id, String name) {
    }

    public record Report(
            List<String> competingPregenerators,
            List<String> threadingMods,
            boolean canaryLoaded,
            boolean modernFixLoaded
    ) {
        public boolean blocksStart() {
            return (!competingPregenerators.isEmpty() || !threadingMods.isEmpty())
                    && !T2MEConfig.ALLOW_COMPETING_PREGENERATORS.get();
        }

        public String blockingReason() {
            StringJoiner joiner = new StringJoiner(", ");
            competingPregenerators.forEach(joiner::add);
            threadingMods.forEach(joiner::add);
            return joiner.toString();
        }

        public String summary() {
            return "Canary=" + canaryLoaded
                    + ", ModernFix=" + modernFixLoaded
                    + ", competing=[" + String.join(", ", competingPregenerators) + "]"
                    + ", threading=[" + String.join(", ", threadingMods) + "]";
        }
    }
}
