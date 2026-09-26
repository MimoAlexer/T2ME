package dev.t2me;

import net.minecraftforge.common.ForgeConfigSpec;

public final class T2MEConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue PERMISSION_LEVEL;
    public static final ForgeConfigSpec.IntValue MAX_IN_FLIGHT;
    public static final ForgeConfigSpec.IntValue MAX_DISPATCH_PER_TICK;
    public static final ForgeConfigSpec.IntValue TARGET_TICK_MILLIS;
    public static final ForgeConfigSpec.IntValue HARD_STOP_TICK_MILLIS;
    public static final ForgeConfigSpec.IntValue MIN_HEAP_HEADROOM_MIB;
    public static final ForgeConfigSpec.IntValue STALL_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_RETRIES;
    public static final ForgeConfigSpec.IntValue SAVE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue PROGRESS_LOG_INTERVAL_SECONDS;
    public static final ForgeConfigSpec.BooleanValue AUTO_RESUME;
    public static final ForgeConfigSpec.BooleanValue REDUCE_WHEN_PLAYERS_ONLINE;
    public static final ForgeConfigSpec.BooleanValue ALLOW_COMPETING_PREGENERATORS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "T2ME never executes Minecraft world mutation on its own thread.",
                "These values control how many vanilla/Forge chunk futures are admitted."
        ).push("scheduler");

        MAX_IN_FLIGHT = builder
                .comment(
                        "Maximum simultaneous FULL chunk requests. Hard-capped at 256.",
                        "Admission starts at four and grows while tick time and heap are healthy.",
                        "Futures use Minecraft's worker pool; this is not a thread count."
                )
                .defineInRange("maxInFlight", 64, 1, AdaptiveLimiter.MAX_PIPELINE_SIZE);
        MAX_DISPATCH_PER_TICK = builder
                .comment("Maximum new requests shared by tick-end dispatch and queued refills in one tick.")
                .defineInRange("maxDispatchPerTick", 32, 1, AdaptiveLimiter.MAX_PIPELINE_SIZE);
        TARGET_TICK_MILLIS = builder
                .comment(
                        "Admission is reduced when the latest tick or tick EWMA reaches this value.",
                        "Keep below hardStopTickMillis; reversed thresholds use hardStopTickMillis minus one."
                )
                .defineInRange("targetTickMillis", 45, 20, 100);
        HARD_STOP_TICK_MILLIS = builder
                .comment(
                        "Admission stops immediately when the latest tick or tick EWMA reaches this value.",
                        "Recovery requires 20 ticks with both measurements below 90% of the target."
                )
                .defineInRange("hardStopTickMillis", 55, 30, 200);
        MIN_HEAP_HEADROOM_MIB = builder
                .comment(
                        "Stop admission below this heap reserve, capped at 25% of the JVM maximum heap.",
                        "Admission resumes only after additional headroom is available to avoid oscillation."
                )
                .defineInRange("minHeapHeadroomMiB", 1024, 256, 8192);
        STALL_TIMEOUT_SECONDS = builder
                .comment("Pause admission if the oldest in-flight request exceeds this age.")
                .defineInRange("stallTimeoutSeconds", 120, 30, 3600);
        REDUCE_WHEN_PLAYERS_ONLINE = builder
                .comment("Halve the admission limit while players are online.")
                .define("reduceWhenPlayersOnline", true);
        builder.pop();

        builder.push("job");
        MAX_RETRIES = builder
                .comment("Retries per failed chunk request before the job is paused.")
                .defineInRange("maxRetries", 2, 0, 10);
        SAVE_INTERVAL_TICKS = builder
                .comment("Persist the job cursor and pending requests this often.")
                .defineInRange("saveIntervalTicks", 100, 20, 1200);
        PROGRESS_LOG_INTERVAL_SECONDS = builder
                .comment("Interval for progress logging. Zero disables periodic logs.")
                .defineInRange("progressLogIntervalSeconds", 60, 0, 3600);
        AUTO_RESUME = builder
                .comment(
                        "Resume a job 10 seconds after a clean server start.",
                        "A job marked stalled or failed never auto-resumes."
                )
                .define("autoResume", true);
        ALLOW_COMPETING_PREGENERATORS = builder
                .comment(
                        "Allow T2ME to start while another known pregenerator is installed.",
                        "Keep false unless the other pregenerator is disabled."
                )
                .define("allowCompetingPregenerators", false);
        builder.pop();

        PERMISSION_LEVEL = builder
                .comment("Required command permission level.")
                .defineInRange("permissionLevel", 4, 0, 4);

        SPEC = builder.build();
    }

    private T2MEConfig() {
    }
}
