package com.antiaddiction;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.storage.LevelDatCipher;
import com.antiaddiction.storage.LegacySaveLocker;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;

public class ClientSetupHandler {

    public static void onClientSetup(FMLClientSetupEvent event) {
        AntiAddictionMod.LOGGER.info("[防沉迷] 客户端初始化...");
        ApiClient.loadConfigAndFetchHolidays();
        PlayerDataManager.getInstance();
        ApiClient.reportGameStart();
        LevelDatCipher.cleanupAllSaveTempFiles(FMLPaths.GAMEDIR.get().resolve("saves"));
        LegacySaveLocker.scanAndLockAsync();
    }
}
