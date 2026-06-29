package com.antiaddiction;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.storage.LevelDatCipher;
import com.antiaddiction.storage.LegacySaveLocker;
import com.antiaddiction.time.RuntimeChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public class AntiAddictionClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AntiAddictionMod.LOGGER.info("[防沉迷] 客户端模块已初始化");
        ApiClient.loadConfigAndFetchHolidays();
        PlayerDataManager.getInstance();
        ApiClient.reportGameStart();
        RuntimeChecker.init();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                com.antiaddiction.network.PlaySessionReporter::tick);
        LevelDatCipher.cleanupAllSaveTempFiles(FabricLoader.getInstance().getGameDir().resolve("saves"));
        LegacySaveLocker.scanAndLockAsync();
    }
}
