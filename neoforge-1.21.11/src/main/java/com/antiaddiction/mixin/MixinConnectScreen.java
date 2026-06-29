package com.antiaddiction.mixin;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.screen.TimeRestrictionScreen;
import com.antiaddiction.screen.VerificationScreen;
import com.antiaddiction.time.PlayTimeChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在多人连接真正发起前拦截。ConnectScreen.startConnecting(...) 是所有多人入口（服务器列表、
 * 直连、quick-play）的唯一汇聚点，连接线程在该方法末尾才启动。HEAD 处 cancel 可彻底阻止连接，
 * 避免"连上又被踢"导致服务器侧出现 join/leave。startConnecting 在 Mojang 映射中名称唯一，
 * 用方法名匹配即可。
 */
@Mixin(ConnectScreen.class)
public class MixinConnectScreen {

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void aa_blockConnect(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        PlayerDataManager mgr = PlayerDataManager.getInstance();

        if (!mgr.hasUsableCredentialForCurrentUser()) {
            client.execute(() -> client.setScreen(new VerificationScreen()));
            ci.cancel();
        } else if (mgr.isMinor() && (!mgr.hasTrustedRules() || !PlayTimeChecker.isPlayAllowed())) {
            client.execute(() -> client.setScreen(new TimeRestrictionScreen()));
            ci.cancel();
        }
    }
}
