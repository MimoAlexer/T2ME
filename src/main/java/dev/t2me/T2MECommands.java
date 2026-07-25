package dev.t2me;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.List;

public final class T2MECommands {
    private static final int MIN_RADIUS_BLOCKS = 16;
    /*
     * SpiralChunkPlan counts accepted chunks synchronously when a job is
     * created. Keep the operator command bounded so an accidental extra zero
     * cannot monopolize the server thread. This still supports a 20 km radius.
     */
    private static final int MAX_RADIUS_BLOCKS = SpiralChunkPlan.MAX_RADIUS_BLOCKS;

    private T2MECommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("t2me")
                        .requires(source -> source.hasPermission(T2MEConfig.PERMISSION_LEVEL.get()))
                        .then(Commands.literal("status")
                                .executes(context -> sendComponents(
                                        context.getSource(),
                                        PregenComponents.status(
                                                PregenService.INSTANCE.progressView()
                                        )
                                )))
                        .then(Commands.literal("metrics")
                                .executes(context -> sendComponents(
                                        context.getSource(),
                                        PregenComponents.metrics(
                                                PregenService.INSTANCE.progressView()
                                        )
                                )))
                        .then(Commands.literal("config")
                                .then(Commands.literal("show")
                                        .executes(context -> sendComponents(
                                                context.getSource(),
                                                PregenComponents.config()
                                        ))))
                        .then(Commands.literal("pregen")
                                .then(spawnStartCommand())
                                .then(explicitStartCommand())
                                .then(Commands.literal("pause")
                                        .executes(context -> sendResult(
                                                context.getSource(),
                                                PregenService.INSTANCE.pause()
                                        )))
                                .then(Commands.literal("resume")
                                        .executes(context -> sendResult(
                                                context.getSource(),
                                                PregenService.INSTANCE.resume()
                                        )))
                                .then(Commands.literal("cancel")
                                        .executes(context -> sendResult(
                                                context.getSource(),
                                                PregenService.INSTANCE.cancel()
                                        )))
                                .then(Commands.literal("status")
                                        .executes(context -> sendComponents(
                                                context.getSource(),
                                                PregenComponents.status(
                                                        PregenService.INSTANCE.progressView()
                                                )
                                        ))))
        );
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<
            CommandSourceStack, ?> spawnStartCommand() {
        return Commands.literal("start")
                .then(Commands.argument(
                                "radiusBlocks",
                                IntegerArgumentType.integer(MIN_RADIUS_BLOCKS, MAX_RADIUS_BLOCKS)
                        )
                        .executes(context -> startAtSpawn(context, PregenShape.CIRCLE))
                        .then(Commands.literal("circle")
                                .executes(context -> startAtSpawn(
                                        context,
                                        PregenShape.CIRCLE
                                )))
                        .then(Commands.literal("square")
                                .executes(context -> startAtSpawn(
                                        context,
                                        PregenShape.SQUARE
                                ))));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<
            CommandSourceStack, ?> explicitStartCommand() {
        return Commands.literal("startat")
                .then(Commands.argument("dimension", ResourceLocationArgument.id())
                        .then(Commands.argument("centerX", IntegerArgumentType.integer())
                                .then(Commands.argument("centerZ", IntegerArgumentType.integer())
                                        .then(Commands.argument(
                                                        "radiusBlocks",
                                                        IntegerArgumentType.integer(
                                                                MIN_RADIUS_BLOCKS,
                                                                MAX_RADIUS_BLOCKS
                                                        )
                                                )
                                                .executes(context -> startExplicit(
                                                        context,
                                                        PregenShape.CIRCLE
                                                ))
                                                .then(Commands.literal("circle")
                                                        .executes(context -> startExplicit(
                                                                context,
                                                                PregenShape.CIRCLE
                                                        )))
                                                .then(Commands.literal("square")
                                                        .executes(context -> startExplicit(
                                                                context,
                                                                PregenShape.SQUARE
                                                        )))))));
    }

    private static int startAtSpawn(
            CommandContext<CommandSourceStack> context,
            PregenShape shape
    ) {
        int radius = IntegerArgumentType.getInteger(context, "radiusBlocks");
        Level overworld = context.getSource().getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        PregenService.OperationResult result = PregenService.INSTANCE.start(
                Level.OVERWORLD.location().toString(),
                spawn.getX(),
                spawn.getZ(),
                radius,
                shape
        );
        return sendResult(context.getSource(), result);
    }

    private static int startExplicit(
            CommandContext<CommandSourceStack> context,
            PregenShape shape
    ) {
        ResourceLocation dimension = ResourceLocationArgument.getId(context, "dimension");
        int centerX = IntegerArgumentType.getInteger(context, "centerX");
        int centerZ = IntegerArgumentType.getInteger(context, "centerZ");
        int radius = IntegerArgumentType.getInteger(context, "radiusBlocks");
        PregenService.OperationResult result = PregenService.INSTANCE.start(
                dimension.toString(),
                centerX,
                centerZ,
                radius,
                shape
        );
        return sendResult(context.getSource(), result);
    }

    private static int sendResult(
            CommandSourceStack source,
            PregenService.OperationResult result
    ) {
        if (result.successful()) {
            source.sendSuccess(
                    () -> PregenComponents.operation(true, result.message()),
                    true
            );
            return 1;
        }
        source.sendFailure(PregenComponents.operation(false, result.message()));
        return 0;
    }

    private static int sendComponents(
            CommandSourceStack source,
            List<Component> components
    ) {
        components.forEach(component ->
                source.sendSuccess(() -> component, false));
        return 1;
    }
}
