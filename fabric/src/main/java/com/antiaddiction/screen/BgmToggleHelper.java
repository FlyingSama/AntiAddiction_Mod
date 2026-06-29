package com.antiaddiction.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * BGM 开关：仅接管"是否允许播放背景音乐"的开关状态，<b>不修改</b>玩家的音量设置。
 * 音乐拦截由 MixinMusicTracker 基于 {@link #shouldBlockMusic()} 完成。
 * 因此关闭再打开会自然恢复到原音量。
 */
public class BgmToggleHelper {

    private static final int SIZE = 22, Y = 8;

    /** 默认跟随游戏（开）。 */
    private static boolean musicOn = true;

    public static boolean isMusicOn() { return musicOn; }
    public static boolean shouldBlockMusic() { return !musicOn; }

    public static void setMusicOn(boolean on) {
        if (musicOn == on) return;
        musicOn = on;
        if (!on) stopNow();
    }

    private static void stopNow() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null) c.getMusicTracker().stop();
    }

    /** 防沉迷界面默认关闭 BGM。 */
    public void muteOnce(MinecraftClient client) { setMusicOn(false); }

    /** 进入主界面恢复 BGM（仅放开接管，不改音量）。 */
    public void restoreVolume(MinecraftClient client) { setMusicOn(true); }

    public void render(DrawContext ctx, MinecraftClient client, int screenWidth) {
        if (client == null || client.textRenderer == null) return;
        int x = screenWidth - SIZE - 8;
        int bg     = musicOn ? 0x33000000 : 0x33550000;
        int border = musicOn ? 0x88FFFFFF : 0xAAFF5555;
        ctx.fill(x - 1, Y - 1, x + SIZE + 1, Y + SIZE + 1, border);
        ctx.fill(x, Y, x + SIZE, Y + SIZE, bg);

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(2f, 2f);
        float cxBox = (x + SIZE / 2f) / 2f;
        float topY  = (Y + SIZE / 2f) / 2f - client.textRenderer.fontHeight / 2f;
        ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(musicOn ? "🔊" : "🔇"),
                Math.round(cxBox), Math.round(topY), musicOn ? 0xFFFFFFFF : 0xFFFF5555);
        ctx.getMatrices().popMatrix();
    }

    public boolean mouseClicked(double mx, double my, int screenWidth, MinecraftClient client) {
        int x = screenWidth - SIZE - 8;
        if (mx >= x && mx < x + SIZE && my >= Y && my < Y + SIZE) {
            setMusicOn(!musicOn);
            return true;
        }
        return false;
    }

    public static boolean hit(double mx, double my, int screenWidth) {
        int x = screenWidth - SIZE - 8;
        return mx >= x && mx < x + SIZE && my >= Y && my < Y + SIZE;
    }
}
