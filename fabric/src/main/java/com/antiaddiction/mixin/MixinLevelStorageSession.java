package com.antiaddiction.mixin;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.storage.LevelDatCipher;
import com.antiaddiction.storage.StorageKeyManager;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(LevelStorage.Session.class)
public abstract class MixinLevelStorageSession {

    @Shadow
    private String directoryName;

    @Shadow
    public abstract LevelStorage.LevelSave getDirectory();

    /**
     * 在世界真正关闭时（所有 save 已完成、lock 即将释放前）同步加密 level.dat。
     * close() 只被调用一次，无竞态问题，保证加密在最终存档后执行。
     */
    @Inject(
        method = "close()V",
        at = @At("HEAD")
    )
    private void aa_encryptOnClose(CallbackInfo ci) {
        if (!PlayerDataManager.getInstance().hasUsableCredentialForCurrentUser()) return;
        Path levelDat    = getDirectory().getLevelDatPath();
        Path levelDatOld = levelDat.resolveSibling("level.dat_old");
        if (!Files.exists(levelDat) && !Files.exists(levelDatOld)) return;
        try {
            byte[] key = StorageKeyManager.INSTANCE.keyFor(directoryName);
            String kid = StorageKeyManager.INSTANCE.kidFor(directoryName);
            if (Files.exists(levelDat) && !LevelDatCipher.isEncrypted(levelDat)) {
                LevelDatCipher.encryptInPlace(levelDat, key, kid, directoryName);
                AntiAddictionMod.LOGGER.info("[防沉迷] level.dat 加密完成 {}", directoryName);
            }
            // 同时加密 level.dat_old，否则 Vanilla 的"尝试恢复"功能会用明文备份绕过保护
            if (Files.exists(levelDatOld) && !LevelDatCipher.isEncrypted(levelDatOld)) {
                LevelDatCipher.encryptInPlace(levelDatOld, key, kid, directoryName);
                AntiAddictionMod.LOGGER.info("[防沉迷] level.dat_old 加密完成 {}", directoryName);
            }
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] level.dat 加密失败 {}: {}", directoryName, e.getMessage());
        }
    }
}
