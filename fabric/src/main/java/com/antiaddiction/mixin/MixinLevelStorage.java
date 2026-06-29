package com.antiaddiction.mixin;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.screen.TimeRestrictionScreen;
import com.antiaddiction.storage.LevelDatCipher;
import com.antiaddiction.storage.StorageKeyManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(LevelStorage.class)
public class MixinLevelStorage {

    /**
     * 拦截世界列表摘要加载路径（Yarn 1.21.11 对应 Mojang readLightweightData）：
     * loadSummaries() → readSummary()
     *   → loadCompactLevelData(Path) → NbtIo.parseCompressed(...)
     * 注意：此方法与下面的 readLevelProperties 是独立路径，必须分开拦截。
     */
    @Inject(
        method = "loadCompactLevelData(Ljava/nio/file/Path;)Lnet/minecraft/nbt/NbtElement;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void aa_decryptForSummary(Path path, CallbackInfoReturnable<NbtElement> cir) {
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
                NbtCompound tag = NbtIo.readCompressed(temp, NbtSizeTracker.ofUnlimitedBytes());
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
    private static NbtCompound makeEncryptedPlaceholderTag() {
        NbtCompound root = new NbtCompound();
        NbtCompound data = new NbtCompound();
        // DataVersion 必须设为当前版本号，否则 DataFixer 从 0 升级时会抛异常导致回落 CorruptedLevelSummary
        data.putInt("DataVersion", net.minecraft.SharedConstants.WORLD_VERSION);
        // version=19133 是 readSummary 合法性检查（19132 或 19133）的必填字段
        data.putInt("version", 19133);
        data.putString("LevelName", "§c[已被防沉迷加密] §7请安装防沉迷模组并完成认证以恢复地图");
        root.put("Data", data);
        return root;
    }

    /**
     * 拦截进入世界的完整读取路径：
     * LevelStorage$Session.readLevelProperties(boolean)
     *   → LevelStorage.readLevelProperties(Path, DataFixer)   [static]
     *     → LevelStorage.readLevelProperties(Path)            [static] ← inject here
     *         → NbtIo.readCompressed(Path, NbtSizeTracker)    ← redirect this call
     */
    @Redirect(
        method = "readLevelProperties(Ljava/nio/file/Path;)Lnet/minecraft/nbt/NbtCompound;",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/nbt/NbtIo;readCompressed(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtSizeTracker;)Lnet/minecraft/nbt/NbtCompound;")
    )
    private static NbtCompound aa_decryptAndRead(Path path, NbtSizeTracker tracker) throws IOException {
        if (!LevelDatCipher.isEncrypted(path)) {
            return NbtIo.readCompressed(path, tracker);
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
                NbtCompound result = NbtIo.readCompressed(temp, tracker);
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
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) {
                mc.execute(() -> mc.setScreen(new TimeRestrictionScreen()));
            }
            throw new IOException("level.dat 解密失败，请确认网络连接后重试: " + e.getMessage(), e);
        }
    }
}
