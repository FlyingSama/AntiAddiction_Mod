package com.antiaddiction;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AntiAddictionMod.MOD_ID)
public class AntiAddictionMod {

    public static final String MOD_ID = "antiaddiction";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public AntiAddictionMod(IEventBus modEventBus, ModContainer container) {
        LOGGER.info("[防沉迷] NeoForge 防沉迷系统已加载");
        modEventBus.addListener(ClientSetupHandler::onClientSetup);
    }
}
