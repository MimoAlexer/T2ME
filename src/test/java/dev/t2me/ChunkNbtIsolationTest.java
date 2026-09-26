package dev.t2me;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ChunkNbtIsolationTest {
    @Test
    void delayedLoadDoesNotExposePendingSaveOrNestedTags() {
        CompoundTag saved = new CompoundTag();
        saved.putString("Status", "minecraft:full");
        CompoundTag nested = new CompoundTag();
        nested.putInt("value", 42);
        saved.put("nested", nested);
        CompletableFuture<Optional<CompoundTag>> source = new CompletableFuture<>();
        CompletableFuture<Optional<CompoundTag>> result = ChunkNbtIsolation.isolate(source);
        assertFalse(result.isDone());
        source.complete(Optional.of(saved));
        CompoundTag loaded = result.join().orElseThrow();
        assertEquals(saved, loaded);
        assertNotSame(saved, loaded);
        assertNotSame(nested, loaded.getCompound("nested"));
        loaded.putString("__context", "reader mutation");
        loaded.getCompound("nested").putInt("value", 9);
        assertFalse(saved.contains("__context"));
        assertEquals(42, nested.getInt("value"));
    }

    @Test
    void completedSourceAlsoGetsOwnedCopy() {
        CompoundTag saved = new CompoundTag();
        var source = CompletableFuture.completedFuture(Optional.of(saved));
        CompoundTag first = ChunkNbtIsolation.isolate(source).join().orElseThrow();
        CompoundTag second = ChunkNbtIsolation.isolate(source).join().orElseThrow();
        assertNotSame(saved, first);
        assertNotSame(first, second);
        first.putInt("reader", 1);
        assertFalse(second.contains("reader"));
    }

    @Test
    void missingChunkStaysMissing() {
        var source = CompletableFuture.completedFuture(Optional.<CompoundTag>empty());
        assertTrue(ChunkNbtIsolation.isolate(source).join().isEmpty());
    }

    @Test
    void failedReadPreservesOriginalCause() {
        var failure = new IllegalStateException("disk read failed");
        var source = CompletableFuture.<Optional<CompoundTag>>failedFuture(failure);
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> ChunkNbtIsolation.isolate(source).join());
        assertSame(failure, thrown.getCause());
    }
}
