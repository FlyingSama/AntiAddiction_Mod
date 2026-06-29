package com.antiaddiction.mixin;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.screen.TimeRestrictionScreen;
import com.antiaddiction.storage.LevelDatCipher;
import com.antiaddiction.storage.StorageKeyManager;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(LevelStorageSource.class)
public class MixinLevelStorageSource {

    /**
     * 拦截世界列表摘要加载路径：
     * loadLevelSummaries() → readLevelSummary()
     *   → readLightweightData(Path)  ← @Inject HEAD（此方法内部调用 NbtIo.parseCompressed，
     *                                   与 readLevelDataTagRaw 是独立路径，需单独拦截）
     */
    @Inject(
        method = "readLightweightData(Ljava/nio/file/Path;)Lnet/minecraft/nbt/Tag;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void aa_decryptForSummary(Path path, CallbackInfoReturnable<Tag> cir) {
        if (!LevelDatCipher.isEncrypted(path)) return;

        Path parent = path.getParent();
        String saveName = parent != null ? parent.getFileName().toString() : path.toString();

        if (!ApiClient.isConfigured() || !PlayerDataManager.getInstance().hasUsableCredentialForCurrentUser()) {
            // 未认证：返回带自定义提示名称的伪造 NBT，使世界列表显示说明而非"加载世界摘要失败"
            cir.setReturnValue(makeEncryptedPlaceholderTag());
            return;
        }

        try {
            byte[] key  = StorageKeyManager.INSTANCE.keyFor(saveName);
            Path   temp = LevelDatCipher.decryptToTemp(path, key);
            try {
                CompoundTag tag = NbtIo.readCompressed(temp, NbtAccounter.uncompressedQuota());
                cir.setReturnValue(tag);
            } finally {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] 世界列表摘要解密失败 {}: {}", saveName, e.getMessage());
            StorageKeyManager.INSTANCE.invalidate(saveName);
            cir.setReturnValue(makeEncryptedPlaceholderTag()); // 解密失败仍显示提示名称
        }
    }

    /** 当世界已加密且无法提供密钥时，返回带说明文字的伪造摘要 Tag，使世界列表显示提示而非"加载世界摘要失败" */
    private static CompoundTag makeEncryptedPlaceholderTag() {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        // DataVersion 必须设为当前版本号，否则 DataFixer 从 0 升级时会抛异常导致回落 CorruptedLevelSummary
        data.putInt("DataVersion", net.minecraft.SharedConstants.WORLD_VERSION);
        // version=19133 是 makeLevelSummary 合法性检查（19132 或 19133）的必填字段
        data.putInt("version", 19133);
        data.putString("LevelName", "§c[已被防沉迷加密] §7请安装防沉迷模组并完成认证以恢复地图");
        root.put("Data", data);
        return root;
    }

    /**
     * 拦截进入世界的完整读取路径：
     * LevelStorageAccess.getDataTag(boolean)
     *   → LevelStorageSource.readLevelDataTagFixed(Path, DataFixer)   [static]
     *     → LevelStorageSource.readLevelDataTagRaw(Path)              [static] ← inject here
     *         → NbtIo.readCompressed(Path, NbtAccounter)              ← redirect this call
     */
    @Redirect(
        method = "readLevelDataTagRaw(Ljava/nio/file/Path;)Lnet/minecraft/nbt/CompoundTag;",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/nbt/NbtIo;readCompressed(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;")
    )
    private static CompoundTag aa_decryptAndRead(Path path, NbtAccounter accounter) throws IOException {
        if (!LevelDatCipher.isEncrypted(path)) {
            return NbtIo.readCompressed(path, accounter);
        }

        Path parent = path.getParent();
        String saveName = parent != null ? parent.getFileName().toString() : path.toString();

        if (!ApiClient.isConfigured() || !PlayerDataManager.getInstance().hasUsableCredentialForCurrentUser()) {
            throw new IOException("level.dat 已加密，需联网并完成认证后才能加载此存档");
        }

        long t0 = System.nanoTime();
        try {
            byte[] key  = StorageKeyManager.INSTANCE.keyFor(saveName);
            Path   temp = LevelDatCipher.decryptToTemp(path, key);
            try {
                CompoundTag result = NbtIo.readCompressed(temp, accounter);
                AntiAddictionMod.LOGGER.info("[防沉迷] level.dat 解密完成 ({} μs)",
                        (System.nanoTime() - t0) / 1000);
                return result;
            } finally {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
            }
        } catch (IOException rethrow) {
            throw rethrow;
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] level.dat 解密失败 {}: {}", saveName, e.getMessage());
            StorageKeyManager.INSTANCE.invalidate(saveName);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> mc.setScreen(new TimeRestrictionScreen()));
            }
            throw new IOException("level.dat 解密失败，请确认网络连接后重试: " + e.getMessage(), e);
        }
    }
}
