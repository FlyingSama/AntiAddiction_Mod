package com.antiaddiction.screen;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.security.TrustedClock;
import com.antiaddiction.time.PlayTimeChecker;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

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
        super(Text.literal("防沉迷 - 时间限制"));
    }

    @Override
    protected void init() {
        lastCheckMs = System.currentTimeMillis();
        bgm.muteOnce(this.client);

        int cx = this.width / 2;

        String currentTs = TrustedClock.nowShanghai().format(TIME_FMT);
        String nextTime  = PlayTimeChecker.getNextAllowedTime();

        line2 = "当前缺少可信规则或不在允许游玩时段内";
        line3 = "当前时间 " + currentTs;
        line4 = "下次可游玩 " + nextTime;
        lineWarning = "⚠ 删除此模组后，单人存档将被加密保护，无法被原版游戏读取";

        int maxTw = this.textRenderer.getWidth(line2);
        maxTw = Math.max(maxTw, this.textRenderer.getWidth(line3));
        maxTw = Math.max(maxTw, this.textRenderer.getWidth(line4));

        float s = Math.min(1.0f, this.height / 300.0f);
        lineH = Math.max(16, (int)(22 * s));
        int pad = 24;
        boxW = Math.min(maxTw + pad * 2, this.width - 40);
        boxH = lineH * 3 + 16;
        boxX = (this.width - boxW) / 2;

        int titleH = (int)(this.textRenderer.fontHeight * 2f) + 4;
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

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("退出"),
                btn -> { assert this.client != null; this.client.stop(); }
        ).dimensions(cx - 110, btnY, 60, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("日历"),
                btn -> openCalendar()
        ).dimensions(cx - 30, btnY, 60, btnH).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("刷新"),
                btn -> checkAndProceed()
        ).dimensions(cx + 50, btnY, 60, btnH).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fillGradient(0, 0, this.width, this.height, COLOR_BG_TOP, COLOR_BG_BOTTOM);

        bgm.render(ctx, this.client, this.width);

        int cx = this.width / 2;
        int titleY = boxY - 24;
        String title = "⚠  防沉迷系统  ⚠";
        ctx.getMatrices().pushMatrix();
        float ts = 2f;
        ctx.getMatrices().scale(ts, ts);
        ctx.drawCenteredTextWithShadow(this.textRenderer, title, (int)(cx / ts), (int)(titleY / ts), 0xFFFF5555);
        ctx.getMatrices().popMatrix();

        ctx.fill(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, COLOR_ACCENT);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xCC1E0A00);
        ctx.fill(boxX + 10, accentY, boxX + boxW - 10, accentY + 1, COLOR_ACCENT);

        ctx.drawCenteredTextWithShadow(this.textRenderer, line2, cx, boxY + 4, 0xFFFFAA00);
        ctx.drawCenteredTextWithShadow(this.textRenderer, line3, cx, accentY + 6, 0xFFAAAAAA);
        ctx.drawCenteredTextWithShadow(this.textRenderer, line4, cx, accentY + 6 + lineH, 0xFF55FF55);
        ctx.drawCenteredTextWithShadow(this.textRenderer, lineWarning, cx, warningY, 0xFF888888);

        super.render(ctx, mouseX, mouseY, delta);
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
                && this.client != null) {
            PlayTimeChecker.markSessionStart();
            bgm.restoreVolume(this.client);
            this.client.setScreen(new TitleScreen(false));
        }
    }

    private void openCalendar() {
        if (this.client != null) this.client.setScreen(new CalendarScreen(this));
    }

    private void checkAndProceed() {
        ApiClient.refreshRules();
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void close() { }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (doubled || click.button() != 0) return super.mouseClicked(click, doubled);
        if (bgm.mouseClicked(click.x(), click.y(), this.width, this.client)) return true;
        return super.mouseClicked(click, doubled);
    }
}
