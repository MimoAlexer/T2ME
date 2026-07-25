package dev.t2me.mixin;

import com.mojang.datafixers.util.Either;
import dev.t2me.ThreadedWorldgenEngine;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Mixin(ChunkStatus.class)
public abstract class ChunkStatusMixin {
    /**
     * Redirect only the generation-task invocation. Vanilla retains ownership
     * of profiling and status publication, and stages outside the active T2ME
     * scope immediately follow the original path.
     */
    @Redirect(
            method = "generate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkStatus$GenerationTask;"
                            + "doWork(Lnet/minecraft/world/level/chunk/ChunkStatus;"
                            + "Ljava/util/concurrent/Executor;"
                            + "Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/level/chunk/ChunkGenerator;"
                            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/"
                            + "StructureTemplateManager;"
                            + "Lnet/minecraft/server/level/ThreadedLevelLightEngine;"
                            + "Ljava/util/function/Function;"
                            + "Ljava/util/List;"
                            + "Lnet/minecraft/world/level/chunk/ChunkAccess;)"
                            + "Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<Either<
            ChunkAccess,
            ChunkHolder.ChunkLoadingFailure
            >> t2me$scheduleGenerationTask(
            ChunkStatus.GenerationTask task,
            ChunkStatus status,
            Executor executor,
            ServerLevel level,
            ChunkGenerator generator,
            StructureTemplateManager structures,
            ThreadedLevelLightEngine lighting,
            Function<ChunkAccess, CompletableFuture<Either<
                    ChunkAccess,
                    ChunkHolder.ChunkLoadingFailure
                    >>> fullConverter,
            List<ChunkAccess> chunks,
            ChunkAccess target
    ) {
        return ThreadedWorldgenEngine.schedule(
                status,
                level,
                target.getPos(),
                executor,
                stageExecutor -> task.doWork(
                        status,
                        stageExecutor,
                        level,
                        generator,
                        structures,
                        lighting,
                        fullConverter,
                        chunks,
                        target
                )
        );
    }
}
