package com.antiaddiction.screen;

import com.antiaddiction.network.ApiClient;
import com.antiaddiction.time.PlayTimeChecker;
import com.antiaddiction.time.PlayTimeStatus;
import com.antiaddiction.time.RuntimeChecker;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class CalendarScreen extends Screen {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy年M月");

    private YearMonth currentMonth;
    private int panelX, panelY, panelW, panelH;
    private int cellW, cellH;
    private int btnRowY, hdrRowY, gridTopY, titleY;
    private int backY, retX, retW, refX;
    private int currentDrawPanelX;
    private static final int COLS = 7;
    private static final int MAX_ROWS = 6;
    private static final int MARGIN = 8;

    private LocalDate selectedDate = null;
    private boolean selectedPlayable, selectedIsOverride;
    private int selectedStartH, selectedStartM, selectedEndH, selectedEndM, selectedMaxMin;
    private boolean infoExpanded = false;

    private long animStartMs = 0;
    private int animPanelXFrom, animPanelXTo;
    private boolean panelSliding = false;
    private static final long SLIDE_MS = 220;
    private static final long INFO_MS   = 500;

    private final BgmToggleHelper bgm = new BgmToggleHelper();

    private static final int C_BG_TOP    = 0xFF0F172A;
    private static final int C_BG_BOT    = 0xFF1E293B;
    private static final int C_SURFACE   = 0xCC1E293B;
    private static final int C_ACCENT    = 0xFF3B82F6;
    private static final int C_ACCENT_BG = 0x283B82F6;
    private static final int C_GREEN     = 0xFF22C55E;
    private static final int C_RED       = 0xFFEF4444;
    private static final int C_WHITE     = 0xFFFFFFFF;
    private static final int C_MUTED     = 0xFF94A3B8;
    private static final int C_ORANGE    = 0xFFF59E0B;
    private static final int C_BORDER    = 0xFF60A5FA;
    private static final int C_INFO_BG   = 0xCC1E293B;
    private static final int C_DOT       = 0xFF3B82F6;
    private static final int C_BTN_BG    = 0xFF334155;
    private static final int C_BTN_HOVER = 0xFF475569;

    private final Screen parent;

    public CalendarScreen() { this(null); }

    public CalendarScreen(Screen parent) {
        super(Text.literal("游戏日日历"));
        this.parent = parent;
        this.currentMonth = YearMonth.now();
    }

    private float ease(float t) { return t * t * (3f - 2f * t); }

    @Override
    protected void init() {
        // BGM 默认态：ESC 菜单内打开→跟随当前不改；首页打开→开；防沉迷界面打开→关
        if (parent instanceof GameMenuScreen) {
            // 跟随游戏设置，不改动
        } else if (parent instanceof TitleScreen) {
            BgmToggleHelper.setMusicOn(true);
        } else {
            BgmToggleHelper.setMusicOn(false);
        }

        int btnH = 20;
        int legendW = 140;
        retW = 80; int retH = 22;

        int availW = this.width - MARGIN * 2;
        int availH = this.height - MARGIN * 2;

        backY = this.height - 26;
        retX = (this.width - retW) / 2;
        refX = retX + retW + 4;

        panelY = MARGIN;
        btnRowY = panelY + 4;
        titleY = btnRowY + btnH + 8;
        hdrRowY = titleY + this.textRenderer.fontHeight + 6;

        int topH = hdrRowY - panelY;
        int maxCellHForBottom = (backY - 8 - hdrRowY) / (MAX_ROWS + 1);

        cellH = Math.max(14, Math.min(availH / (MAX_ROWS + 3), availW / (COLS + 3)));
        cellH = Math.min(cellH, maxCellHForBottom);
        cellW = cellH;
        panelW = cellW * COLS + 6;

        int maxPanelW = Math.max(60, availW - legendW);
        if (panelW > maxPanelW) { panelW = maxPanelW; cellW = (panelW - 6) / COLS; cellH = cellW; }

        panelX = infoExpanded ? MARGIN : Math.max(MARGIN, (this.width - panelW) / 2);
        int rightSpace = this.width - (panelX + panelW) - MARGIN;
        if (rightSpace < legendW)
            panelX = Math.max(MARGIN, this.width - panelW - legendW - MARGIN);

        gridTopY = hdrRowY + cellH;
        panelH = gridTopY - panelY + cellH * MAX_ROWS;
        int maxPanelBottom = backY - 8;
        if (panelY + panelH > maxPanelBottom) {
            cellH = Math.max(8, (maxPanelBottom - hdrRowY) / (MAX_ROWS + 1));
            cellW = cellH;
            gridTopY = hdrRowY + cellH;
            panelH = gridTopY - panelY + cellH * MAX_ROWS;
        }
        currentDrawPanelX = panelX;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("返回"), btn -> {
                    if (this.client != null) this.client.setScreen(parent != null ? parent : new TimeRestrictionScreen());
                }
        ).dimensions(retX, backY, retW, retH).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("⟳"), btn -> {
                    ApiClient.refreshRules();
                    clearAndInit();
                }
        ).dimensions(refX, backY, 22, retH).build());
    }

    private void navMonth(int delta) {
        currentMonth = currentMonth.plusMonths(delta);
        panelSliding = false; animStartMs = 0;
        clearAndInit();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fillGradient(0, 0, this.width, this.height, C_BG_TOP, C_BG_BOT);

        bgm.render(ctx, this.client, this.width);

        int dx = panelX;
        if (panelSliding) {
            long elapsed = System.currentTimeMillis() - animStartMs;
            float slideT = ease(Math.min(1f, (float)elapsed / (float)SLIDE_MS));
            dx = animPanelXFrom + (int)((animPanelXTo - animPanelXFrom) * slideT);
            if (slideT >= 1f) { panelSliding = false; panelX = animPanelXTo; dx = panelX; }
        }
        currentDrawPanelX = dx;

        ctx.fill(dx - 2, panelY - 2, dx + panelW + 2, panelY + panelH + 2, C_ACCENT);
        ctx.fill(dx, panelY, dx + panelW, panelY + panelH, C_SURFACE);

        String title = currentMonth.format(MONTH_FMT);
        int titleW = this.textRenderer.getWidth(title);
        ctx.drawCenteredTextWithShadow(this.textRenderer, title, dx + panelW - titleW / 2 - 8, titleY, C_WHITE);

        String[] hdr = {"日","一","二","三","四","五","六"};
        for (int c = 0; c < COLS; c++) {
            int x = dx + 3 + c * cellW + cellW / 2;
            int color = (c == 0 || c == 6) ? 0xFF60A5FA : C_MUTED;
            ctx.drawCenteredTextWithShadow(this.textRenderer, hdr[c], x, hdrRowY + 2, color);
        }

        drawNavButtons(ctx, mouseX, mouseY, dx);
        drawCalendarCells(ctx, mouseX, mouseY, dx);

        int legX = dx + panelW + 12;
        drawLegend(ctx, legX, btnRowY);
        drawPlayStatus(ctx, legX, btnRowY + (this.textRenderer.fontHeight + 3) * 3 + 12);

        if (infoExpanded && selectedDate != null) {
            int ipX = dx + panelW + MARGIN;
            int maxTw = this.textRenderer.getWidth(
                    selectedDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINESE)));
            maxTw = Math.max(maxTw, this.textRenderer.getWidth("调休非玩日"));
            if (selectedPlayable)
                maxTw = Math.max(maxTw, this.textRenderer.getWidth(
                        formatWindow(selectedStartH, selectedStartM, selectedEndH, selectedEndM)));
            int ipW = Math.min(maxTw + 24, this.width - ipX - MARGIN);
            if (ipW < 90) ipW = 90;
            if (ipX + ipW > this.width - MARGIN) ipX = this.width - ipW - MARGIN;
            if (ipX < dx + panelW + 2) ipX = dx + panelW + 2;

            int lineCount = 2;
            if (selectedPlayable) lineCount++;
            if (selectedMaxMin > 0) lineCount++;
            int ipH = 8 + lineCount * 13 + 4;
            int ipY = panelY + (panelH - ipH) / 2;
            if (ipY < MARGIN) ipY = MARGIN;
            if (ipY + ipH > this.height - MARGIN) ipY = this.height - ipH - MARGIN;

            long infoElapsed = (animStartMs > 0 && (infoExpanded || panelSliding))
                    ? (System.currentTimeMillis() - animStartMs) : INFO_MS;
            int rightEdge = dx + panelW;
            int midY = panelY + panelH / 2;

            if (infoElapsed < 100) {
                float t = ease(Math.min(1f, (float)infoElapsed / 100f));
                int r = Math.max(1, (int)(2 * t));
                ctx.fill(rightEdge + 4 - r, midY - r, rightEdge + 4 + r, midY + r, C_DOT);
            } else if (infoElapsed < 250) {
                float t = ease(Math.min(1f, (float)(infoElapsed - 100) / 150f));
                int halfH = Math.max(4, (int)((ipH / 2f) * t));
                ctx.fill(rightEdge + 4, midY - halfH, rightEdge + 6, midY + halfH, C_DOT);
            } else {
                float t = ease(Math.min(1f, (float)(infoElapsed - 250) / 250f));
                int bgW = Math.max(4, (int)((ipX + ipW - rightEdge) * t));
                ctx.fill(rightEdge + 4, ipY, rightEdge + 4 + bgW, ipY + ipH, C_INFO_BG);
                ctx.fill(rightEdge + 4, ipY, rightEdge + 6, ipY + ipH, C_DOT);

                if (infoElapsed >= 350) {
                    float textT = ease(Math.min(1f, (float)(infoElapsed - 350) / 150f));
                    int alpha = (int)(textT * 255);
                    int tx = ipX + 6, ty = ipY + 4;

                    String dateStr = selectedDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINESE));
                    int dw = this.textRenderer.getWidth(dateStr);
                    ctx.drawCenteredTextWithShadow(this.textRenderer, dateStr, tx + dw / 2, ty, blendAlpha(C_WHITE, alpha));

                    String statusLine; int statusColor;
                    if (selectedIsOverride) { statusLine = "调休非玩日"; statusColor = C_RED; }
                    else if (selectedPlayable) { statusLine = "可玩日"; statusColor = C_GREEN; }
                    else { statusLine = "非玩日"; statusColor = C_MUTED; }
                    ty += 13; int sw = this.textRenderer.getWidth(statusLine);
                    ctx.drawCenteredTextWithShadow(this.textRenderer, statusLine, tx + sw / 2, ty, blendAlpha(statusColor, alpha));

                    if (selectedPlayable) {
                        ty += 13; String timeStr = formatWindow(selectedStartH, selectedStartM, selectedEndH, selectedEndM);
                        int tw = this.textRenderer.getWidth(timeStr);
                        ctx.drawCenteredTextWithShadow(this.textRenderer, timeStr, tx + tw / 2, ty, blendAlpha(C_WHITE, alpha));
                    }
                    if (selectedMaxMin > 0) {
                        ty += 13; String limitStr = "限时 " + selectedMaxMin + " 分钟";
                        int lw = this.textRenderer.getWidth(limitStr);
                        ctx.drawCenteredTextWithShadow(this.textRenderer, limitStr, tx + lw / 2, ty, blendAlpha(C_ORANGE, alpha));
                    }
                }
            }
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawNavButtons(DrawContext ctx, int mouseX, int mouseY, int dx) {
        int btnY = btnRowY;
        drawButton(ctx, mouseX, mouseY, dx + 4, btnY, 28, 20, "◀");
        drawButton(ctx, mouseX, mouseY, dx + panelW - 32, btnY, 28, 20, "▶");
        drawButton(ctx, mouseX, mouseY, dx + panelW / 2 - 21, btnY, 42, 20, "今天");
    }

    private void drawButton(DrawContext ctx, int mouseX, int mouseY, int bx, int by, int bw, int bh, String text) {
        boolean hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
        ctx.fill(bx, by, bx + bw, by + bh, hovered ? C_BTN_HOVER : C_BTN_BG);
        ctx.fill(bx, by, bx + bw, by + 1, C_ACCENT);
        ctx.fill(bx, by + bh - 1, bx + bw, by + bh, C_ACCENT);
        ctx.fill(bx, by, bx + 1, by + bh, C_ACCENT);
        ctx.fill(bx + bw - 1, by, bx + bw, by + bh, C_ACCENT);
        int tw = this.textRenderer.getWidth(text);
        ctx.drawCenteredTextWithShadow(this.textRenderer, text, bx + bw / 2, by + (bh - this.textRenderer.fontHeight) / 2, C_WHITE);
    }

    private void drawCalendarCells(DrawContext ctx, int mouseX, int mouseY, int dx) {
        LocalDate today = LocalDate.now();
        LocalDate first = currentMonth.atDay(1);
        int startDow = first.getDayOfWeek().getValue() % 7;
        int days = currentMonth.lengthOfMonth();
        Map<String, int[]> gd = PlayTimeChecker.getGameDays();

        for (int row = 0; row < MAX_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col - startDow;
                if (idx < 0 || idx >= days) continue;
                int day = idx + 1;
                int x = dx + 3 + col * cellW;
                int y = gridTopY + row * cellH;
                LocalDate date = currentMonth.atDay(day);
                boolean isToday = date.equals(today);
                boolean isSelected = date.equals(selectedDate);

                int[] v = gd.get(date.toString());
                boolean showGreen = false, showRed = false, showOrange = false;
                if (v != null) {
                    if (v.length > 6 && v[6] == 1) showRed = true;
                    else if (v[0] == 1) { if (v.length > 5 && v[5] > 0) showOrange = true; else showGreen = true; }
                } else if (PlayTimeChecker.isDefaultPlayableWeekday(date)) {
                    if (PlayTimeChecker.getDefaultMaxMinutes() > 0) showOrange = true;
                    else showGreen = true;
                }

                boolean hovered = mouseX >= x && mouseX < x + cellW && mouseY >= y && mouseY < y + cellH;

                if (isSelected) {
                    ctx.fill(x, y, x + cellW, y + cellH, 0x303B82F6);
                    ctx.fill(x, y, x + cellW, y + 1, C_BORDER);
                    ctx.fill(x, y + cellH - 1, x + cellW, y + cellH, C_BORDER);
                    ctx.fill(x, y, x + 1, y + cellH, C_BORDER);
                    ctx.fill(x + cellW - 1, y, x + cellW, y + cellH, C_BORDER);
                } else if (isToday) {
                    ctx.fill(x + 1, y + 1, x + cellW - 1, y + cellH - 1, C_ACCENT_BG);
                }
                if (hovered && !isSelected) ctx.fill(x + 1, y + 1, x + cellW - 1, y + cellH - 1, 0x28FFFFFF);

                int numC = (isToday || isSelected) ? C_ACCENT : C_WHITE;
                int numY = y + Math.max(2, cellH / 2 - this.textRenderer.fontHeight - 1);
                ctx.drawCenteredTextWithShadow(this.textRenderer, String.valueOf(day), x + cellW / 2, numY, numC);

                if (showGreen || showRed || showOrange) {
                    int dotS = Math.max(3, Math.min(6, cellW / 9));
                    int dotX = x + cellW / 2 - dotS / 2, dotY = y + cellH - dotS - 3;
                    int dotC = showRed ? C_RED : (showOrange ? C_ORANGE : C_GREEN);
                    ctx.fill(dotX, dotY, dotX + dotS, dotY + dotS, dotC);
                }
            }
        }
    }

    private void drawLegend(DrawContext ctx, int legX, int legY) {
        int ds = 5, dg = 3, lh = this.textRenderer.fontHeight;
        legX = Math.min(legX, this.width - MARGIN - 120);
        int dy1 = legY + lh / 2 - ds / 2;
        ctx.fill(legX, dy1, legX + ds, dy1 + ds, C_GREEN);
        String t1 = "可玩日"; ctx.drawCenteredTextWithShadow(this.textRenderer, t1,
                legX + ds + dg + this.textRenderer.getWidth(t1) / 2, legY, C_MUTED);
        int legY2 = legY + lh + 3, dy2 = legY2 + lh / 2 - ds / 2;
        ctx.fill(legX, dy2, legX + ds, dy2 + ds, C_ORANGE);
        String t2 = "限时可玩日"; ctx.drawCenteredTextWithShadow(this.textRenderer, t2,
                legX + ds + dg + this.textRenderer.getWidth(t2) / 2, legY2, C_MUTED);
        int legY3 = legY2 + lh + 3, dy3 = legY3 + lh / 2 - ds / 2;
        ctx.fill(legX, dy3, legX + ds, dy3 + ds, C_RED);
        String t3 = "调休非玩日"; ctx.drawCenteredTextWithShadow(this.textRenderer, t3,
                legX + ds + dg + this.textRenderer.getWidth(t3) / 2, legY3, C_MUTED);
    }

    private void drawPlayStatus(DrawContext ctx, int x, int y) {
        x = Math.min(x, this.width - MARGIN - 120);
        PlayTimeStatus st = PlayTimeChecker.getCurrentStatus();
        int lh = this.textRenderer.fontHeight + 3;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("当前时间 " + (st.trustedRules ? st.currentTimeText : "--:--")), x, y, C_WHITE);
        if (!st.trustedRules) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("需要同步可信规则"), x, y + lh, C_MUTED);
            return;
        }
        if (st.limited) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("今日剩余 " + RuntimeChecker.formatDuration(st.remainingSeconds)), x, y + lh, st.allowed ? C_GREEN : C_RED);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("可玩时段 " + st.windowText), x, y + lh * 2, C_MUTED);
        } else {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("可玩时段 " + st.windowText), x, y + lh, C_MUTED);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("剩余 " + RuntimeChecker.formatDuration(st.windowRemainingSeconds)), x, y + lh * 2, st.allowed ? C_GREEN : C_RED);
        }
    }

    private static int blendAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24); }
    private static String formatWindow(int startHour, int startMinute, int endHour, int endMinute) {
        return String.format("%02d:%02d - %02d:%02d", startHour, startMinute, endHour, endMinute);
    }
    @Override public boolean shouldCloseOnEsc() { return false; }

    private void updateSelectedInfo(LocalDate date) {
        Map<String, int[]> gd = PlayTimeChecker.getGameDays();
        int[] v = gd.get(date.toString());
        if (v != null) {
            selectedIsOverride = v.length > 6 && v[6] == 1;
            selectedPlayable = v[0] == 1 && !selectedIsOverride;
            selectedStartH = v[1]; selectedStartM = v.length > 2 ? v[2] : 0;
            selectedEndH = v[3]; selectedEndM = v.length > 4 ? v[4] : 0;
            selectedMaxMin = v.length > 5 ? v[5] : 0;
        } else {
            selectedIsOverride = false;
            selectedPlayable = PlayTimeChecker.isDefaultPlayableWeekday(date);
            selectedStartH = PlayTimeChecker.getDefaultStartHour();
            selectedStartM = PlayTimeChecker.getDefaultStartMinute();
            selectedEndH = PlayTimeChecker.getDefaultEndHour();
            selectedEndM = PlayTimeChecker.getDefaultEndMinute();
            selectedMaxMin = PlayTimeChecker.getDefaultMaxMinutes();
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (doubled || click.button() != 0) return super.mouseClicked(click, doubled);

        double mx = click.x(), my = click.y();
        int dx = currentDrawPanelX;

        // BGM
        if (bgm.mouseClicked(mx, my, this.width, this.client)) return true;

        // Nav buttons
        if (mx >= dx + 4 && mx < dx + 32 && my >= btnRowY && my < btnRowY + 20) { navMonth(-1); return true; }
        if (mx >= dx + panelW - 32 && mx < dx + panelW - 4 && my >= btnRowY && my < btnRowY + 20) { navMonth(1); return true; }
        if (mx >= dx + panelW / 2 - 21 && mx < dx + panelW / 2 + 21 && my >= btnRowY && my < btnRowY + 20) {
            currentMonth = YearMonth.now(); clearAndInit(); return true;
        }

        // Date cells
        LocalDate first = currentMonth.atDay(1);
        int startDow = first.getDayOfWeek().getValue() % 7;
        int days = currentMonth.lengthOfMonth();
        for (int row = 0; row < MAX_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col - startDow;
                if (idx < 0 || idx >= days) continue;
                int x = dx + 3 + col * cellW, y = gridTopY + row * cellH;
                if (mx >= x && mx < x + cellW && my >= y && my < y + cellH) {
                    selectedDate = currentMonth.atDay(idx + 1);
                    updateSelectedInfo(selectedDate);
                    if (!infoExpanded) {
                        infoExpanded = true;
                        animPanelXFrom = panelX; animPanelXTo = MARGIN;
                        animStartMs = System.currentTimeMillis(); panelSliding = true;
                        clearAndInit();
                    }
                    return true;
                }
            }
        }

        if (infoExpanded) {
            selectedDate = null; infoExpanded = false;
            animPanelXFrom = panelX; animStartMs = System.currentTimeMillis(); panelSliding = true;
            clearAndInit(); animPanelXTo = panelX;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }
}
