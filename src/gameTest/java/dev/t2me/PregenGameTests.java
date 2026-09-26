package dev.t2me;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Runs only in the development GameTest source set; never shipped in the mod. */
@GameTestHolder(T2ME.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PregenGameTests {
    @GameTest(template = "empty", timeoutTicks = 120_000)
    public static void generationAndLifecycle(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PregenService service = PregenService.INSTANCE;
        // A single state machine serializes access to the one server-wide job.
        int[] phase = {0};
        int[] waited = {0};
        long[] pausedCursor = {0};
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
        helper.onEachTick(() -> {
            // GameTestServer ticks without the normal 50 ms pacing. Give asynchronous
            // generation real time to complete instead of burning the tick timeout.
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            helper.assertTrue(System.nanoTime() < deadline,
                    "lifecycle timed out in phase " + phase[0] + ": " + service.statusLine());
            PregenJob.Snapshot snapshot = service.snapshot().orElse(null);
            switch (phase[0]) {
                case 0 -> {
                    if (snapshot != null && !snapshot.state().isTerminal()) service.cancel();
                    require(helper, service.start("minecraft:overworld", 4096, 4096, 32, PregenShape.CIRCLE));
                    phase[0]++;
                }
                case 1 -> {
                    if (snapshot.state() != JobState.COMPLETED) return;
                    assertComplete(helper, snapshot);
                    require(helper, service.start("minecraft:overworld", -4096, -4096, 128, PregenShape.SQUARE));
                    phase[0]++;
                }
                case 2 -> {
                    if (++waited[0] < 3) return;
                    require(helper, service.pause());
                    pausedCursor[0] = service.snapshot().orElseThrow().cursor();
                    waited[0] = 0;
                    phase[0]++;
                }
                case 3 -> {
                    helper.assertTrue(snapshot.cursor() == pausedCursor[0], "pause admitted more chunks");
                    if (++waited[0] < 10) return;
                    require(helper, service.resume());
                    waited[0] = 0;
                    phase[0]++;
                }
                case 4 -> {
                    if (++waited[0] < 3) return;
                    var id = snapshot.id();
                    service.detach();
                    server.overworld().getDataStorage().save();
                    try {
                        var disk = server.overworld().getDataStorage().readTagFromDisk(T2MEData.DATA_NAME,
                                net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
                        server.overworld().getDataStorage().set(T2MEData.DATA_NAME,
                                T2MEData.load(disk.getCompound("data")));
                    } catch (java.io.IOException error) {
                        throw new IllegalStateException("saved checkpoint could not be read", error);
                    }
                    service.attach(server);
                    var restored = service.snapshot().orElseThrow();
                    helper.assertTrue(restored.id().equals(id), "restart changed job identity");
                    helper.assertTrue(restored.state() == JobState.PAUSED, "restart was not safely paused");
                    require(helper, service.resume());
                    phase[0]++;
                }
                case 5 -> {
                    if (snapshot.state() != JobState.COMPLETED) return;
                    assertComplete(helper, snapshot);
                    require(helper, service.start("minecraft:overworld", 8192, -8192, 128, PregenShape.CIRCLE));
                    waited[0] = 0;
                    phase[0]++;
                }
                case 6 -> {
                    if (++waited[0] < 3) return;
                    require(helper, service.cancel());
                    helper.assertTrue(service.snapshot().orElseThrow().pending().isEmpty(), "cancel retained work");
                    require(helper, service.start("minecraft:overworld", 8192, -8192, 16, PregenShape.SQUARE));
                    phase[0]++;
                }
                case 7 -> {
                    if (snapshot.state() != JobState.COMPLETED) return;
                    assertComplete(helper, snapshot);
                    // Exercise vanilla's flush path with every completed job's chunks.
                    server.overworld().getChunkSource().save(true);
                    T2ME.LOGGER.info("T2ME integration lifecycle passed: {}", service.statusLine());
                    helper.succeed();
                    phase[0]++;
                }
                default -> { }
            }
        });
    }

    private static void require(GameTestHelper helper, PregenService.OperationResult result) {
        helper.assertTrue(result.successful(), result.message());
    }

    private static void assertComplete(GameTestHelper helper, PregenJob.Snapshot snapshot) {
        long expected = new SpiralChunkPlan(snapshot.centerBlockX(), snapshot.centerBlockZ(),
                snapshot.radiusBlocks(), snapshot.shape()).targetCount();
        helper.assertTrue(snapshot.completed() == expected, "incomplete target: " + snapshot.completed());
        helper.assertTrue(snapshot.pending().isEmpty(), "completed with pending work");
        helper.assertTrue(snapshot.failures() == 0, "generation had failures");
    }
}
