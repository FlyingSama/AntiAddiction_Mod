package com.antiaddiction.screen;

import com.antiaddiction.data.PlayerDataManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 在 vanilla 首页 / ESC 菜单右上角（BGM 开关位置）绘制防沉迷日历入口按钮。
 * 仅对未成年人显示。可玩时间信息已移入 CalendarScreen 内显示。
 * 点击命中由 ClientEventHandler 处理。
 */
public final class CalendarOverlay {

    private static final float ICON_SCALE = 2.0F;
    private static final int SIZE = 22;
    private static final int Y = 8;

    private CalendarOverlay() {}

    public static boolean isVisible() {
        return PlayerDataManager.getInstance().isMinor();
    }

    public static void render(GuiGraphicsExtractor gfx, Minecraft client, int screenWidth) {
        if (client == null || client.font == null || !isVisible()) return;

        int x = screenWidth - SIZE - 8;
        gfx.fill(x - 1, Y - 1, x + SIZE + 1, Y + SIZE + 1, 0x88FFFFFF);
        gfx.fill(x, Y, x + SIZE, Y + SIZE, 0x33000000);

        Component icon = Component.literal("📅");
        float cxBox = (x + SIZE / 2.0F) / ICON_SCALE;
        float topY  = (Y + SIZE / 2.0F) / ICON_SCALE - client.font.lineHeight / 2.0F;
        gfx.pose().pushMatrix();
        gfx.pose().scale(ICON_SCALE, ICON_SCALE);
        gfx.centeredText(client.font, icon, Math.round(cxBox), Math.round(topY), 0xFFFFFFFF);
        gfx.pose().popMatrix();
    }

    public static boolean hit(double mx, double my, int screenWidth) {
        if (!isVisible()) return false;
        int x = screenWidth - SIZE - 8;
        return mx >= x && mx < x + SIZE && my >= Y && my < Y + SIZE;
    }
}
