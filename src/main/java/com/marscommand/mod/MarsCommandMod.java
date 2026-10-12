package com.marscommand.mod;

import com.mojang.logging.LogUtils;
import com.marscommand.mod.command.MoonCommands;
import com.marscommand.mod.mission.MoonDirector;
import com.marscommand.mod.network.MoonNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(MarsCommandMod.MOD_ID)
public final class MarsCommandMod {
    public static final String MOD_ID = "mars_command";
    private static final Logger LOGGER = LogUtils.getLogger();

    public MarsCommandMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Loading Mars Command {}", modContainer.getModInfo().getVersion());
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(MoonNetworking::register);
        NeoForge.EVENT_BUS.addListener(MoonDirector::started);
        NeoForge.EVENT_BUS.addListener(MoonDirector::tick);
        NeoForge.EVENT_BUS.addListener(MoonDirector::stopping);
        NeoForge.EVENT_BUS.addListener(MoonDirector::login);
        NeoForge.EVENT_BUS.addListener(MoonDirector::logout);
        NeoForge.EVENT_BUS.addListener(MoonCommands::register);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Mars Command initialized (mod id: {})", MOD_ID);
    }
}
