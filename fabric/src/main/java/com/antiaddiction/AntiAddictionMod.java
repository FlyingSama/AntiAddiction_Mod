package com.antiaddiction;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AntiAddictionMod implements ModInitializer {

    public static final String MOD_ID = "antiaddiction";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[防沉迷] 服务端模块已加载 (Anti-Addiction System Initialized)");
    }
}
