package com.antiaddiction.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * BGM 开关：仅接管"是否允许播放背景音乐"的开关状态，<b>不修改</b>玩家的音量设置。
 * 音乐拦截由 ClientEventHandler 的 SelectMusicEvent / PlaySoundEvent 基于
 * {@link #shouldBlockMusic()} 完成。因此关闭再打开会自然恢复到原音量。
 */
public class BgmToggleHelper {

    private static final float ICON_SCALE = 2.0F;
    private static final int SIZE = 22;
    private static final int Y = 8;

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
        Minecraft c = Minecraft.getInstance();
        if (c == null) return;
        c.getMusicManager().stopPlaying();
        c.getSoundManager().stop(null, SoundSource.MUSIC);
    }

    public static void toggle(Minecraft client) { setMusicOn(!musicOn); }

    /** 防沉迷界面默认关闭 BGM。 */
    public void muteOnce(Minecraft client) { setMusicOn(false); }

    /** 进入主界面恢复 BGM（仅放开接管，不改音量）。 */
    public void restoreVolume(Minecraft client) { setMusicOn(true); }

    /** 每 tick 兜底：被接管关闭时停止正在播放的音乐（不改音量）。 */
    public static void enforceMuted(Minecraft client) {
        if (!musicOn) stopNow();
    }

    public void render(GuiGraphics gfx, Minecraft client, int screenWidth) {
        if (client == null || client.font == null) return;
        int x = screenWidth - SIZE - 8;
        int bg     = musicOn ? 0x33000000 : 0x33550000;
        int border = musicOn ? 0x88FFFFFF : 0xAAFF5555;
        gfx.fill(x - 1, Y - 1, x + SIZE + 1, Y + SIZE + 1, border);
        gfx.fill(x, Y, x + SIZE, Y + SIZE, bg);

        Component icon = Component.literal(musicOn ? "🔊" : "🔇");
        float cxBox = (x + SIZE / 2.0F) / ICON_SCALE;
        float topY  = (Y + SIZE / 2.0F) / ICON_SCALE - client.font.lineHeight / 2.0F;
        gfx.pose().pushMatrix();
        gfx.pose().scale(ICON_SCALE, ICON_SCALE);
        gfx.drawCenteredString(client.font, icon, Math.round(cxBox), Math.round(topY),
                musicOn ? 0xFFFFFFFF : 0xFFFF5555);
        gfx.pose().popMatrix();
    }

    public static boolean hit(double mx, double my, int screenWidth) {
        int x = screenWidth - SIZE - 8;
        return mx >= x && mx < x + SIZE && my >= Y && my < Y + SIZE;
    }
}
