package com.antiaddiction.storage;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.*;

public final class LegacySaveLocker {

    private LegacySaveLocker() {}

    public static void scanAndLockAsync() {
        if (!PlayerDataManager.getInstance().hasUsableCredentialForCurrentUser()) return;
        if (!ApiClient.isConfigured()) return;
        Thread t = new Thread(LegacySaveLocker::run, "AntiAddiction-Locker");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        Path gameDir  = FMLPaths.GAMEDIR.get();
        Path savesDir = gameDir.resolve("saves");
        if (!Files.isDirectory(savesDir)) return;

        try (var stream = Files.list(savesDir)) {
            stream.filter(Files::isDirectory).forEach(saveDir -> {
                String saveName  = saveDir.getFileName().toString();
                Path levelDat    = saveDir.resolve("level.dat");
                Path levelDatOld = saveDir.resolve("level.dat_old");

                boolean needDat    = Files.exists(levelDat)    && !LevelDatCipher.isEncrypted(levelDat);
                boolean needDatOld = Files.exists(levelDatOld) && !LevelDatCipher.isEncrypted(levelDatOld);
                if (!needDat && !needDatOld) return;

                LevelDatCipher.cleanupTempFiles(saveDir);
                try {
                    byte[] key = StorageKeyManager.INSTANCE.keyFor(saveName);
                    String kid = StorageKeyManager.INSTANCE.kidFor(saveName);
                    if (needDat) {
                        LevelDatCipher.encryptInPlace(levelDat, key, kid, saveName);
                        AntiAddictionMod.LOGGER.info("[防沉迷] 已加密 level.dat: {}", saveName);
                    }
                    if (needDatOld) {
                        LevelDatCipher.encryptInPlace(levelDatOld, key, kid, saveName);
                        AntiAddictionMod.LOGGER.info("[防沉迷] 已加密 level.dat_old: {}", saveName);
                    }
                } catch (Exception e) {
                    AntiAddictionMod.LOGGER.warn("[防沉迷] 加密存档失败 {}: {}", saveName, e.getMessage());
                }
            });
        } catch (IOException e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] LegacySaveLocker 扫描失败: {}", e.getMessage());
        }
    }
}
