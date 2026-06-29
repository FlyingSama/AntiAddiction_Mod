package com.antiaddiction.screen;

import com.antiaddiction.data.PlayerDataManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 在 vanilla 首页 / ESC 菜单右上角（BGM 开关位置）绘制防沉迷日历入口按钮。
 * 仅对未成年人显示。可玩时间信息已移入 CalendarScreen 内显示。
 * 点击命中由 MixinScreen 处理。
 */
public final class CalendarOverlay {

    private static final int SIZE = 22;
    private static final int Y = 8;

    private CalendarOverlay() {}

    public static boolean isVisible() {
        return PlayerDataManager.getInstance().isMinor();
    }

    public static void render(DrawContext ctx, MinecraftClient client, int screenWidth) {
        if (client == null || client.textRenderer == null || !isVisible()) return;

        int x = screenWidth - SIZE - 8;
        ctx.fill(x - 1, Y - 1, x + SIZE + 1, Y + SIZE + 1, 0x88FFFFFF);
        ctx.fill(x, Y, x + SIZE, Y + SIZE, 0x33000000);

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(2f, 2f);
        float cxBox = (x + SIZE / 2f) / 2f;
        float topY  = (Y + SIZE / 2f) / 2f - client.textRenderer.fontHeight / 2f;
        ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal("📅"),
                Math.round(cxBox), Math.round(topY), 0xFFFFFFFF);
        ctx.getMatrices().popMatrix();
    }

    public static boolean hit(double mx, double my, int screenWidth) {
        if (!isVisible()) return false;
        int x = screenWidth - SIZE - 8;
        return mx >= x && mx < x + SIZE && my >= Y && my < Y + SIZE;
    }
}
