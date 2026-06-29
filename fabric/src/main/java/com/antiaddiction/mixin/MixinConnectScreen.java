package com.antiaddiction.mixin;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.screen.TimeRestrictionScreen;
import com.antiaddiction.screen.VerificationScreen;
import com.antiaddiction.time.PlayTimeChecker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在多人连接真正发起前拦截。ConnectScreen.connect(...)（Yarn 静态工厂，对应 Mojang
 * startConnecting）是所有多人入口（服务器列表、直连、quick-play）的唯一汇聚点，连接线程在
 * 该方法末尾才启动。HEAD 处 cancel 可彻底阻止连接，避免"连上又被踢"导致服务器侧出现 join/leave。
 *
 * connect 在 Yarn 中是重载方法（4 参实例 / 6 参静态），必须用完整描述符消歧到 6 参静态版本。
 */
@Mixin(ConnectScreen.class)
public class MixinConnectScreen {

    @Inject(
        method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void aa_blockConnect(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerDataManager mgr  = PlayerDataManager.getInstance();

        if (!mgr.hasUsableCredentialForCurrentUser()) {
            client.execute(() -> client.setScreen(new VerificationScreen()));
            ci.cancel();
        } else if (mgr.isMinor() && (!mgr.hasTrustedRules() || !PlayTimeChecker.isPlayAllowed())) {
            client.execute(() -> client.setScreen(new TimeRestrictionScreen()));
            ci.cancel();
        }
    }
}
