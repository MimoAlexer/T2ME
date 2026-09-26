package dev.t2me.mixin;

import dev.t2me.ChunkNbtIsolation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Minecraft 1.20.1 returns a pending save's actual CompoundTag from loadAsync.
 * Its datafixer then mutates the tag on another executor while the I/O worker
 * can be serializing it. Give readers ownership of a deep copy instead.
 */
@Mixin(IOWorker.class)
abstract class IOWorkerMixin {
    @Inject(method = "loadAsync", at = @At("RETURN"), cancellable = true)
    private void t2me$isolateLoadedTag(
            ChunkPos position,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback
    ) {
        callback.setReturnValue(ChunkNbtIsolation.isolate(callback.getReturnValue()));
    }
}
