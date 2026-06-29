package com.antiaddiction.screen;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.security.TrustedClock;
import com.antiaddiction.time.PlayTimeChecker;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class TimeRestrictionScreen extends Screen {

    private static final ZoneId CHINA_TZ = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINESE);

    private long lastCheckMs = 0L;
    private final BgmToggleHelper bgm = new BgmToggleHelper();

    private static final int COLOR_BG_TOP    = 0xFF120A00;
    private static final int COLOR_BG_BOTTOM = 0xFF2A1000;
    private static final int COLOR_ACCENT    = 0xFFE25A00;

    private String line2, line3, line4, lineWarning;
    private int btnY, btnH;
    private int boxX, boxY, boxW, boxH;
    private int lineH, accentY, warningY;

    public TimeRestrictionScreen() {
        super(Component.literal("防沉迷 - 时间限制"));
    }

    @Override
    protected void init() {
        lastCheckMs = System.currentTimeMillis();
        bgm.muteOnce(this.minecraft);

        int cx = this.width / 2;

        String currentTs = TrustedClock.nowShanghai().format(TIME_FMT);
        String nextTime  = PlayTimeChecker.getNextAllowedTime();

        line2 = "当前缺少可信规则或不在允许游玩时段内";
        line3 = "当前时间 " + currentTs;
        line4 = "下次可游玩 " + nextTime;
        lineWarning = "⚠ 删除此模组后，单人存档将被加密保护，无法被原版游戏读取";

        int maxTw = this.font.width(line2);
        maxTw = Math.max(maxTw, this.font.width(line3));
        maxTw = Math.max(maxTw, this.font.width(line4));

        float s = Math.min(1.0f, this.height / 300.0f);
        lineH = Math.max(16, (int)(22 * s));
        int pad = 24;
        boxW = Math.min(maxTw + pad * 2, this.width - 40);
        boxH = lineH * 3 + 16;
        boxX = (this.width - boxW) / 2;

        int titleH = (int)(this.font.lineHeight * 2f) + 4;
        int contentH = boxH;
        int btnAreaH = 36;
        int totalH = titleH + 14 + contentH + 10 + lineH + btnAreaH;
        int startY = (this.height - totalH) / 2;
        if (startY < 8) startY = 8;

        boxY = startY + titleH + 14;
        accentY = boxY + lineH + 4;

        warningY = boxY + boxH + 8;
        btnY = warningY + lineH + 8;
        btnH = Math.max(16, (int)(20 * s));

        this.addRenderableWidget(Button.builder(
                Component.literal("退出"),
                btn -> { if (this.minecraft != null) this.minecraft.stop(); }
        ).bounds(cx - 110, btnY, 60, btnH).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("日历"),
                btn -> { if (this.minecraft != null) this.minecraft.setScreen(new CalendarScreen(this)); }
        ).bounds(cx - 30, btnY, 60, btnH).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("刷新"),
                btn -> checkAndProceed()
        ).bounds(cx + 50, btnY, 60, btnH).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fillGradient(0, 0, this.width, this.height, COLOR_BG_TOP, COLOR_BG_BOTTOM);
        bgm.render(gfx, this.minecraft, this.width);

        int cx = this.width / 2;
        int titleY = boxY - 16;
        gfx.centeredText(this.font,
                Component.literal("⚠  防沉迷系统  ⚠"), cx, titleY, 0xFFFF5555);

        gfx.fill(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, COLOR_ACCENT);
        gfx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xCC1E0A00);
        gfx.fill(boxX + 10, accentY, boxX + boxW - 10, accentY + 1, COLOR_ACCENT);

        gfx.centeredText(this.font, Component.literal(line2), cx, boxY + 4, 0xFFFFAA00);
        gfx.centeredText(this.font, Component.literal(line3), cx, accentY + 6, 0xFFAAAAAA);
        gfx.centeredText(this.font, Component.literal(line4), cx, accentY + 6 + lineH, 0xFF55FF55);
        gfx.centeredText(this.font, Component.literal(lineWarning), cx, warningY, 0xFF888888);
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        if (now - lastCheckMs > 30_000L) {
            lastCheckMs = now;
            ApiClient.refreshRules();
        }
        if (PlayerDataManager.getInstance().hasTrustedRules()
                && PlayTimeChecker.isPlayAllowed()
                && this.minecraft != null) {
            PlayTimeChecker.markSessionStart();
            bgm.restoreVolume(this.minecraft);
            this.minecraft.setScreen(new net.minecraft.client.gui.screens.TitleScreen(false));
        }
    }

    private void checkAndProceed() {
        ApiClient.refreshRules();
    }

    public boolean handleBgmClick(double mouseX, double mouseY) {
        if (BgmToggleHelper.hit(mouseX, mouseY, this.width)) {
            BgmToggleHelper.toggle(this.minecraft); return true;
        }
        return false;
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void onClose() { }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (doubled || event.button() != 0) return super.mouseClicked(event, doubled);
        if (handleBgmClick(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubled);
    }
}
