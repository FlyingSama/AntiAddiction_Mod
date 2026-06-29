package com.antiaddiction.mixin;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.storage.LevelDatCipher;
import com.antiaddiction.storage.StorageKeyManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class MixinLevelStorageAccess {

    @Shadow
    private String levelId;

    @Shadow
    public abstract LevelStorageSource.LevelDirectory getLevelDirectory();

    /**
     * 在世界真正关闭时（所有 save 已完成、lock 即将释放前）同步加密 level.dat。
     * 此处比 saveDataTag TAIL 更可靠：
     *   saveAllChunks 调用的是 3-arg saveDataTag，而原 Mixin 挂的是 2-arg 版，永远不会触发。
     *   close() 只被调用一次，无竞态问题。
     */
    @Inject(
        method = "close()V",
        at = @At("HEAD")
    )
    private void aa_encryptOnClose(CallbackInfo ci) {
        if (!PlayerDataManager.getInstance().hasUsableCredentialForCurrentUser()) return;
        Path levelDat    = getLevelDirectory().dataFile();
        Path levelDatOld = getLevelDirectory().oldDataFile();
        if (!Files.exists(levelDat) && !Files.exists(levelDatOld)) return;
        try {
            byte[] key = StorageKeyManager.INSTANCE.keyFor(levelId);
            String kid = StorageKeyManager.INSTANCE.kidFor(levelId);
            if (Files.exists(levelDat) && !LevelDatCipher.isEncrypted(levelDat)) {
                LevelDatCipher.encryptInPlace(levelDat, key, kid, levelId);
                AntiAddictionMod.LOGGER.info("[防沉迷] level.dat 加密完成 {}", levelId);
            }
            // 同时加密 level.dat_old，否则 Vanilla 的"尝试恢复"功能会用明文备份绕过保护
            if (Files.exists(levelDatOld) && !LevelDatCipher.isEncrypted(levelDatOld)) {
                LevelDatCipher.encryptInPlace(levelDatOld, key, kid, levelId);
                AntiAddictionMod.LOGGER.info("[防沉迷] level.dat_old 加密完成 {}", levelId);
            }
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] level.dat 加密失败 {}: {}", levelId, e.getMessage());
        }
    }
}
