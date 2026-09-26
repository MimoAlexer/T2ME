package dev.t2me;

import net.minecraft.nbt.CompoundTag;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Separates a loaded chunk's mutable NBT from an unfinished vanilla save. */
public final class ChunkNbtIsolation {
    private ChunkNbtIsolation() {
    }

    public static CompletableFuture<Optional<CompoundTag>> isolate(
            CompletableFuture<Optional<CompoundTag>> source
    ) {
        // No new executor: copy before exposing the result to its first reader.
        // A pending IOWorker save may still own the source tag. In particular,
        // vanilla datafixing adds/removes __context even for current-version NBT.
        return source.thenApply(result -> result.map(CompoundTag::copy));
    }
}
