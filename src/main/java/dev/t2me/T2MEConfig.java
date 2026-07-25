package dev.t2me;

import net.minecraftforge.common.ForgeConfigSpec;

public final class T2MEConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue PERMISSION_LEVEL;
    public static final ForgeConfigSpec.IntValue MIN_IN_FLIGHT;
    public static final ForgeConfigSpec.IntValue INITIAL_IN_FLIGHT;
    public static final ForgeConfigSpec.IntValue MAX_IN_FLIGHT;
    public static final ForgeConfigSpec.IntValue MAX_DISPATCH_PER_TICK;
    public static final ForgeConfigSpec.IntValue MAX_COMPLETIONS_PER_TICK;
    public static final ForgeConfigSpec.IntValue CONTROL_INTERVAL_TICKS;
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
    public static final ForgeConfigSpec.BooleanValue THREADED_WORLDGEN;
    public static final ForgeConfigSpec.IntValue WORLDGEN_THREADS;
    public static final ForgeConfigSpec.BooleanValue THREADED_STRUCTURES;
    public static final ForgeConfigSpec.BooleanValue THREADED_FEATURES;
    public static final ForgeConfigSpec.BooleanValue SHOW_BOSS_BAR;
    public static final ForgeConfigSpec.BooleanValue BOSS_BAR_ALL_PLAYERS;
    public static final ForgeConfigSpec.IntValue BOSS_BAR_UPDATE_TICKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "High-throughput admission window.",
                "The window is a count of pipelined chunk futures, not a thread count."
        ).push("scheduler");

        MIN_IN_FLIGHT = builder
                .comment("Minimum AIMD request window while the engine is healthy.")
                .defineInRange("minInFlight", 32, 1, 512);
        INITIAL_IN_FLIGHT = builder
                .comment("Initial request window. Chunky uses 50 by default.")
                .defineInRange("initialInFlight", 64, 1, 1024);
        MAX_IN_FLIGHT = builder
                .comment(
                        "Maximum simultaneous FULL chunk requests.",
                        "384 is intended for offline, high-throughput pregeneration."
                )
                .defineInRange("maxInFlight", 384, 1, 2048);
        MAX_DISPATCH_PER_TICK = builder
                .comment("Maximum tickets admitted in one batched distance-manager update.")
                .defineInRange("maxDispatchPerTick", 64, 1, 512);
        MAX_COMPLETIONS_PER_TICK = builder
                .comment("Maximum completed futures applied on the server thread per tick.")
                .defineInRange("maxCompletionsPerTick", 1024, 16, 8192);
        CONTROL_INTERVAL_TICKS = builder
                .comment("AIMD window adjustment interval.")
                .defineInRange("controlIntervalTicks", 20, 5, 200);
        TARGET_TICK_MILLIS = builder
                .comment("The request window backs off above this tick EWMA.")
                .defineInRange("targetTickMillis", 48, 20, 100);
        HARD_STOP_TICK_MILLIS = builder
                .comment("Admission stops entirely above this tick EWMA.")
                .defineInRange("hardStopTickMillis", 65, 30, 200);
        MIN_HEAP_HEADROOM_MIB = builder
                .comment("Stop admission when max heap headroom falls below this many MiB.")
                .defineInRange("minHeapHeadroomMiB", 512, 256, 8192);
        STALL_TIMEOUT_SECONDS = builder
                .comment("Pause admission if the oldest in-flight request exceeds this age.")
                .defineInRange("stallTimeoutSeconds", 120, 30, 3600);
        REDUCE_WHEN_PLAYERS_ONLINE = builder
                .comment("Halve the admission window while players are online.")
                .define("reduceWhenPlayersOnline", false);
        builder.pop();

        builder.comment(
                "Experimental C2ME-inspired stage threading.",
                "Only the active T2ME area is affected. Lighting and FULL conversion stay native."
        ).push("threadedWorldgen");
        THREADED_WORLDGEN = builder
                .comment("Run classified generation stages on T2ME's bounded worker pool.")
                .define("enabled", true);
        WORLDGEN_THREADS = builder
                .comment("Worldgen workers. Zero selects available processors minus one.")
                .defineInRange("threads", 0, 0, 64);
        THREADED_STRUCTURES = builder
                .comment(
                        "Thread structure stages. Disabled until the target modpack passes parity tests."
                )
                .define("structures", false);
        THREADED_FEATURES = builder
                .comment(
                        "Thread FEATURES with radius locks.",
                        "This is the highest-risk switch for modded generators."
                )
                .define("features", false);
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

        builder.push("display");
        SHOW_BOSS_BAR = builder
                .comment("Show live T2ME progress as an in-game boss bar.")
                .define("showBossBar", true);
        BOSS_BAR_ALL_PLAYERS = builder
                .comment("Show the boss bar to all players instead of operators only.")
                .define("bossBarAllPlayers", true);
        BOSS_BAR_UPDATE_TICKS = builder
                .comment("Boss bar refresh interval.")
                .defineInRange("bossBarUpdateTicks", 10, 5, 200);
        builder.pop();

        PERMISSION_LEVEL = builder
                .comment("Required command permission level.")
                .defineInRange("permissionLevel", 4, 0, 4);

        SPEC = builder.build();
    }

    private T2MEConfig() {
    }

    /**
     * Upgrade only the exact 0.1 throughput defaults. Any customized value
     * prevents migration so operator tuning is never silently overwritten.
     */
    public static boolean migrateLegacyPerformanceDefaults() {
        boolean legacyDefaults = MAX_IN_FLIGHT.get() == 8
                && MAX_DISPATCH_PER_TICK.get() == 4
                && TARGET_TICK_MILLIS.get() == 45
                && HARD_STOP_TICK_MILLIS.get() == 55
                && MIN_HEAP_HEADROOM_MIB.get() == 1024
                && REDUCE_WHEN_PLAYERS_ONLINE.get();
        if (!legacyDefaults) {
            return false;
        }

        MAX_IN_FLIGHT.set(384);
        MAX_DISPATCH_PER_TICK.set(64);
        TARGET_TICK_MILLIS.set(48);
        HARD_STOP_TICK_MILLIS.set(65);
        MIN_HEAP_HEADROOM_MIB.set(512);
        REDUCE_WHEN_PLAYERS_ONLINE.set(false);
        SPEC.save();
        return true;
    }
}
