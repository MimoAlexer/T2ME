package dev.t2me;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(T2ME.MOD_ID)
public final class T2ME {
    public static final String MOD_ID = "t2me";
    public static final Logger LOGGER = LogUtils.getLogger();

    public T2ME(FMLJavaModLoadingContext context) {
        context.registerConfig(
                ModConfig.Type.SERVER,
                T2MEConfig.SPEC,
                "t2me-server.toml"
        );
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        T2MECommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        PregenService.INSTANCE.attach(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        PregenService.INSTANCE.detach();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            PregenService.INSTANCE.tickStart();
        } else if (event.phase == TickEvent.Phase.END) {
            PregenService.INSTANCE.tickEnd();
        }
    }
}
