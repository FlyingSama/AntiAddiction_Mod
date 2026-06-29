package com.antiaddiction.mixin;

import com.antiaddiction.screen.BgmToggleHelper;
import net.minecraft.client.sound.MusicTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric 端从源头拦截背景音乐：当 BGM 开关关闭时，取消 MusicTracker.tick 并停止当前音乐。
 * 不修改玩家音量设置，重新开启后音乐按原音量恢复。
 */
@Mixin(MusicTracker.class)
public class MixinMusicTracker {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void aa_blockMusic(CallbackInfo ci) {
        if (BgmToggleHelper.shouldBlockMusic()) {
            ((MusicTracker) (Object) this).stop();
            ci.cancel();
        }
    }
}
