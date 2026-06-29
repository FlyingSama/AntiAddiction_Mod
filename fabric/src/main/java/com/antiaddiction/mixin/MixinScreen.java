package com.antiaddiction.mixin;

import com.antiaddiction.screen.CalendarOverlay;
import com.antiaddiction.screen.CalendarScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 vanilla 首页（TitleScreen）和 ESC 菜单（GameMenuScreen）右上角注入防沉迷日历入口。
 * 注入到 Screen 基类并用 instanceof 过滤，避免 GameMenuScreen 未 override render 导致注入失败，
 * 同时排除 mod 自有界面。
 */
@Mixin(Screen.class)
public abstract class MixinScreen {

    private boolean aa_isOverlayScreen() {
        Object self = this;
        return self instanceof TitleScreen || self instanceof GameMenuScreen;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void aa_renderCalendarButton(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!aa_isOverlayScreen()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        CalendarOverlay.render(ctx, mc, mc.getWindow().getScaledWidth());
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void aa_clickCalendarButton(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (!aa_isOverlayScreen() || click.button() != 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (CalendarOverlay.hit(click.x(), click.y(), mc.getWindow().getScaledWidth())) {
            mc.setScreen(new CalendarScreen((Screen) (Object) this));
            cir.setReturnValue(true);
        }
    }
}
