package dev.t2me;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PregenComponents {
    private PregenComponents() {
    }

    static List<Component> status(PregenView view) {
        List<Component> lines = new ArrayList<>();
        if (!view.hasJob()) {
            lines.add(prefix().append(
                    Component.literal(" No pregeneration job exists.")
                            .withStyle(ChatFormatting.GRAY)
            ));
            return List.copyOf(lines);
        }

        lines.add(prefix()
                .append(Component.literal(" Pregeneration — ")
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(view.stateText())
                        .withStyle(stateColor(view.state()), ChatFormatting.BOLD)));
        lines.add(label("Progress: ")
                .append(value(PregenView.formatCount(view.completed())))
                .append(meta(" / " + PregenView.formatCount(view.target()) + " chunks "))
                .append(Component.literal(String.format(
                        Locale.ROOT,
                        "(%.2f%%)",
                        view.percent()
                )).withStyle(ChatFormatting.GREEN)));
        lines.add(label("Region: ")
                .append(value(friendlyDimension(view.dimension())))
                .append(meta(" • " + view.shape().serializedName()))
                .append(meta(" • center " + view.centerBlockX() + ", " + view.centerBlockZ()))
                .append(meta(" • radius " + PregenView.formatCount(view.radiusBlocks())
                        + " blocks")));
        lines.add(label("Speed: ")
                .append(value(String.format(Locale.ROOT, "%.1f chunks/s", view.cps5())))
                .append(meta(String.format(
                        Locale.ROOT,
                        " (60s %.1f) • ETA %s",
                        view.cps60(),
                        PregenView.formatDuration(view.etaSeconds())
                ))));
        lines.add(label("Work: ")
                .append(value(view.inFlight() + " active"))
                .append(meta(" • " + view.retryQueue() + " retrying"))
                .append(meta(" • " + view.failures() + " failures")));
        lines.add(label("Engine: ")
                .append(value(view.engine().enabled() ? "Threaded" : "Native"))
                .append(meta(" • workers " + view.engine().activeWorkers()
                        + "/" + view.engine().workers()))
                .append(meta(" • stages queued " + view.engine().queuedStages()))
                .append(meta(" • window " + view.admission()
                        + "/" + view.adaptiveWindow())));
        lines.add(label("Scheduler: ")
                .append(value(view.throttle()))
                .append(meta(" • MSPT "
                        + String.format(Locale.ROOT, "%.2f", view.tickEwmaMillis())))
                .append(meta(" • job " + view.jobId())));
        if (view.message() != null
                && !view.message().isBlank()
                && !"running".equalsIgnoreCase(view.message())
                && !"completed".equalsIgnoreCase(view.message())) {
            lines.add(label("Message: ").append(
                    Component.literal(PregenView.sanitize(view.message(), 160))
                            .withStyle(stateColor(view.state()))
            ));
        }
        return List.copyOf(lines);
    }

    static List<Component> metrics(PregenView view) {
        List<Component> lines = new ArrayList<>(status(view));
        lines.add(label("Latency: ")
                .append(value(String.format(
                        Locale.ROOT,
                        "%.1f ms EWMA",
                        view.latencyEwmaMillis()
                )))
                .append(meta(" • stage locks " + view.engine().lockedCoordinates())));
        return List.copyOf(lines);
    }

    static List<Component> config() {
        return List.of(
                prefix().append(Component.literal(" Development performance profile")
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)),
                label("Pipeline: ").append(value(
                        T2MEConfig.MIN_IN_FLIGHT.get() + " minimum • "
                                + T2MEConfig.INITIAL_IN_FLIGHT.get() + " initial • "
                                + T2MEConfig.MAX_IN_FLIGHT.get() + " maximum"
                )),
                label("Batching: ").append(value(
                        T2MEConfig.MAX_DISPATCH_PER_TICK.get() + " dispatch/tick • "
                                + T2MEConfig.MAX_COMPLETIONS_PER_TICK.get()
                                + " completions/tick"
                )),
                label("Worldgen: ").append(value(
                        (T2MEConfig.THREADED_WORLDGEN.get() ? "threaded" : "native")
                                + " • workers "
                                + (T2MEConfig.WORLDGEN_THREADS.get() == 0
                                ? "automatic"
                                : T2MEConfig.WORLDGEN_THREADS.get())
                                + " • structures "
                                + T2MEConfig.THREADED_STRUCTURES.get()
                                + " • features "
                                + T2MEConfig.THREADED_FEATURES.get()
                )),
                label("Pressure: ").append(value(
                        T2MEConfig.TARGET_TICK_MILLIS.get() + " ms target • "
                                + T2MEConfig.HARD_STOP_TICK_MILLIS.get()
                                + " ms hard stop • "
                                + T2MEConfig.MIN_HEAP_HEADROOM_MIB.get()
                                + " MiB reserve"
                ))
        );
    }

    static Component operation(boolean successful, String message) {
        return prefix().append(Component.literal(" " + message).withStyle(
                successful ? ChatFormatting.GREEN : ChatFormatting.RED
        ));
    }

    private static MutableComponent prefix() {
        return Component.literal("[T2ME]")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
    }

    private static MutableComponent label(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_AQUA);
    }

    private static MutableComponent value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    private static MutableComponent meta(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static ChatFormatting stateColor(JobState state) {
        if (state == JobState.RUNNING || state == JobState.COMPLETED) {
            return ChatFormatting.GREEN;
        }
        if (state == JobState.PAUSED) {
            return ChatFormatting.YELLOW;
        }
        if (state == JobState.FAILED) {
            return ChatFormatting.RED;
        }
        return ChatFormatting.GRAY;
    }

    private static String friendlyDimension(String dimension) {
        if (LevelNames.OVERWORLD.equals(dimension)) {
            return "Overworld";
        }
        if (LevelNames.NETHER.equals(dimension)) {
            return "Nether";
        }
        if (LevelNames.END.equals(dimension)) {
            return "The End";
        }
        return dimension;
    }

    private static final class LevelNames {
        private static final String OVERWORLD = "minecraft:overworld";
        private static final String NETHER = "minecraft:the_nether";
        private static final String END = "minecraft:the_end";
    }
}
