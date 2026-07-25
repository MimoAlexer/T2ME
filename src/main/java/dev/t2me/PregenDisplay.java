package dev.t2me;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class PregenDisplay {
    private static final long TERMINAL_DISPLAY_TICKS = 200L;

    private MinecraftServer server;
    private ServerBossEvent bossBar;
    private UUID lastJobId;
    private JobState lastState;
    private long nextUpdateTick;
    private long terminalUntilTick = Long.MIN_VALUE;
    private long nextViewerReconcileTick;

    void attach(MinecraftServer server) {
        detach();
        this.server = server;
        bossBar = new ServerBossEvent(
                Component.literal("T2ME"),
                BossEvent.BossBarColor.BLUE,
                BossEvent.BossBarOverlay.PROGRESS
        );
        bossBar.setDarkenScreen(false);
        bossBar.setPlayBossMusic(false);
        bossBar.setCreateWorldFog(false);
        bossBar.setVisible(false);
    }

    void detach() {
        if (bossBar != null) {
            bossBar.setVisible(false);
            bossBar.removeAllPlayers();
        }
        server = null;
        bossBar = null;
        lastJobId = null;
        lastState = null;
        nextUpdateTick = 0L;
        terminalUntilTick = Long.MIN_VALUE;
        nextViewerReconcileTick = 0L;
    }

    void playerJoined(ServerPlayer player) {
        if (bossBar != null && bossBar.isVisible() && isViewer(player)) {
            bossBar.addPlayer(player);
        }
    }

    void playerLeft(ServerPlayer player) {
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    void update(long tick, PregenView view) {
        if (server == null || bossBar == null) {
            return;
        }

        boolean changedJob = !Objects.equals(lastJobId, view.jobId());
        boolean changedState = changedJob || lastState != view.state();
        if (!changedState && tick < nextUpdateTick) {
            if (tick >= nextViewerReconcileTick) {
                reconcileViewers();
                nextViewerReconcileTick = tick + 100L;
            }
            return;
        }
        nextUpdateTick = tick + T2MEConfig.BOSS_BAR_UPDATE_TICKS.get();
        lastJobId = view.jobId();
        lastState = view.state();

        if (!T2MEConfig.SHOW_BOSS_BAR.get() || !view.hasJob()) {
            hide();
            return;
        }

        if (view.state().isTerminal()) {
            if (changedState) {
                terminalUntilTick = tick + TERMINAL_DISPLAY_TICKS;
            }
            if (tick >= terminalUntilTick) {
                hide();
                return;
            }
        } else {
            terminalUntilTick = Long.MIN_VALUE;
        }

        bossBar.setName(Component.literal(title(view)));
        bossBar.setProgress(view.progressFraction());
        bossBar.setColor(color(view));
        bossBar.setOverlay(BossEvent.BossBarOverlay.PROGRESS);
        reconcileViewers();
        nextViewerReconcileTick = tick + 100L;
        bossBar.setVisible(true);
    }

    private void reconcileViewers() {
        if (server == null || bossBar == null) {
            return;
        }
        Set<ServerPlayer> wanted = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isViewer(player)) {
                wanted.add(player);
                bossBar.addPlayer(player);
            }
        }
        for (ServerPlayer current : Set.copyOf(bossBar.getPlayers())) {
            if (!wanted.contains(current)) {
                bossBar.removePlayer(current);
            }
        }
    }

    private boolean isViewer(ServerPlayer player) {
        return T2MEConfig.BOSS_BAR_ALL_PLAYERS.get()
                || player.createCommandSourceStack()
                        .hasPermission(T2MEConfig.PERMISSION_LEVEL.get());
    }

    private void hide() {
        if (bossBar != null) {
            bossBar.setVisible(false);
            bossBar.removeAllPlayers();
        }
    }

    private static String title(PregenView view) {
        if (view.state() == JobState.RUNNING) {
            String mode = view.admission() == 0 ? "THROTTLED • " : "";
            return String.format(
                    Locale.ROOT,
                    "T2ME • %s%.2f%% • %s/%s • %.1f ch/s • ETA %s • %d active",
                    mode,
                    view.percent(),
                    PregenView.formatCount(view.completed()),
                    PregenView.formatCount(view.target()),
                    view.cps5(),
                    PregenView.formatDuration(view.etaSeconds()),
                    view.inFlight()
            );
        }
        if (view.state() == JobState.COMPLETED) {
            return "T2ME • COMPLETE • " + PregenView.formatCount(view.completed())
                    + " chunks";
        }
        return "T2ME • " + view.state().name() + " • "
                + String.format(Locale.ROOT, "%.2f%%", view.percent())
                + " • " + PregenView.sanitize(view.message(), 64);
    }

    private static BossEvent.BossBarColor color(PregenView view) {
        if (view.state() == JobState.COMPLETED) {
            return BossEvent.BossBarColor.GREEN;
        }
        if (view.state() == JobState.FAILED) {
            return BossEvent.BossBarColor.RED;
        }
        if (view.state() == JobState.PAUSED || view.admission() == 0) {
            return BossEvent.BossBarColor.YELLOW;
        }
        if (view.state() == JobState.CANCELLED) {
            return BossEvent.BossBarColor.WHITE;
        }
        return BossEvent.BossBarColor.BLUE;
    }
}
