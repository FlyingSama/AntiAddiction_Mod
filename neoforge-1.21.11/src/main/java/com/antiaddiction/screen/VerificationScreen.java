package com.antiaddiction.screen;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.data.VerificationResult;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.time.PlayTimeChecker;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class VerificationScreen extends Screen {

    private EditBox nameField;
    private EditBox idCardField;
    private String errorMessage   = "";
    private String successMessage = "";
    private int    attempts       = 0;
    private int    jumpTicks      = -1;
    private BgmToggleHelper bgm;

    private BgmToggleHelper bgm() {
        if (bgm == null) bgm = new BgmToggleHelper();
        return bgm;
    }

    private static final int COLOR_BG_TOP      = 0xFF0D1B2A;
    private static final int COLOR_BG_BOTTOM   = 0xFF1B2838;
    private static final int COLOR_ACCENT      = 0xFF4A90E2;
    private static final int COLOR_BOX         = 0xCC1E2D3D;
    private static final int COLOR_OK_BG       = 0xCC1B5E20;
    private static final int COLOR_OK_BORDER   = 0xFF4CAF50;
    private static final int COLOR_ERR_BG      = 0xCC5E1B1B;
    private static final int COLOR_ERR_BORDER  = 0xFFEF4444;
    private static final int BOX_WIDTH         = 300;
    private static final int BOX_HEIGHT        = 210;
    private static final int FIELD_WIDTH       = 260;
    private static final int FIELD_HEIGHT      = 22;
    private static final int BUTTON_WIDTH      = 220;
    private static final int BUTTON_HEIGHT     = 22;
    private static final int SUCCESS_DISPLAY_TICKS = 60;

    public VerificationScreen() {
        super(Component.literal("防沉迷实名认证"));
    }

    @Override
    protected void init() {
        bgm().muteOnce(this.minecraft);

        int cx = this.width  / 2;
        int cy = this.height / 2;
        int fieldX = cx - FIELD_WIDTH / 2;

        this.nameField = new EditBox(this.font, fieldX, cy - 6, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal(""));
        this.nameField.setMaxLength(30);
        this.nameField.setHint(Component.literal("请输入真实姓名"));
        this.addRenderableWidget(this.nameField);

        this.idCardField = new EditBox(this.font, fieldX, cy + 34, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal(""));
        this.idCardField.setMaxLength(18);
        this.idCardField.setHint(Component.literal("请输入18位居民身份证号码"));
        this.addRenderableWidget(this.idCardField);

        this.addRenderableWidget(Button.builder(
                Component.literal("提交实名认证"),
                btn -> doVerify()
        ).bounds(cx - BUTTON_WIDTH / 2, cy + 74, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void tick() {
        if (jumpTicks > 0) {
            jumpTicks--;
            if (jumpTicks == 0) {
                doJump();
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (keyEvent.key() == 257 || keyEvent.key() == 335) {
            doVerify();
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    private void doVerify() {
        if (jumpTicks >= 0) return;

        String name   = this.nameField.getValue().trim();
        String idCard = this.idCardField.getValue().trim().toUpperCase();
        this.errorMessage   = "";
        this.successMessage = "";

        if (name.isEmpty()) {
            this.errorMessage = "请输入姓名";
            return;
        }
        if (idCard.length() != 18) {
            this.errorMessage = "身份证号必须为18位";
            return;
        }

        VerificationResult result = ApiClient.verifyIdentity(name, idCard);

        if (!result.isValid()) {
            this.attempts++;
            this.errorMessage = result.getMessage() + "（第 " + this.attempts + " 次尝试）";
            this.nameField.setValue("");
            this.idCardField.setValue("");
            this.nameField.setFocused(true);
            return;
        }

        ApiClient.reportSessionStart(PlayerDataManager.getInstance().getUserName(), result.isMinor());
        this.successMessage = result.getMessage();
        this.jumpTicks = SUCCESS_DISPLAY_TICKS;
    }

    private void doJump() {
        if (this.minecraft == null) return;
        if (PlayerDataManager.getInstance().isMinor() && !PlayTimeChecker.isPlayAllowed()) {
            this.minecraft.setScreen(new TimeRestrictionScreen());
        } else {
            PlayTimeChecker.markSessionStart();
            this.minecraft.setScreen(new TitleScreen(false));
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int cx = this.width  / 2;
        int cy = this.height / 2;

        gfx.fillGradient(0, 0, this.width, this.height, COLOR_BG_TOP, COLOR_BG_BOTTOM);

        bgm().render(gfx, this.minecraft, this.width);

        int boxW = BOX_WIDTH, boxH = BOX_HEIGHT;
        int boxX = cx - boxW / 2, boxY = cy - boxH / 2;

        // 面板
        gfx.fill(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, COLOR_ACCENT);
        gfx.fill(boxX, boxY, boxX + boxW, boxY + boxH, COLOR_BOX);

        // 顶部提示
        drawScaledBoldCenteredString(gfx,
                Component.literal("请完成实名认证"), cx, boxY + 6, 2.0F, 0xFFFFAA00);

        gfx.drawCenteredString(this.font,
                Component.literal("游戏实名认证系统"), cx, boxY + 30, 0xFF4A90E2);
        gfx.drawCenteredString(this.font,
                Component.literal("根据《网络游戏管理办法》，请完成实名认证"), cx, boxY + 46, 0xFFAAAAAA);

        gfx.fill(boxX + 12, boxY + 62, boxX + boxW - 12, boxY + 63, 0xFF4A90E2);

        String label1 = "姓　　名：";
        int fieldX = cx - FIELD_WIDTH / 2;
        gfx.drawString(this.font, Component.literal(label1), fieldX, cy - 22, 0xFFFFFFFF);
        String label2 = "身份证号：";
        gfx.drawString(this.font, Component.literal(label2), fieldX, cy + 18, 0xFFFFFFFF);

        super.render(gfx, mouseX, mouseY, partialTick);

        // 提示框置顶居中显示，避免被输入框/按钮遮挡
        if (!this.errorMessage.isEmpty()) {
            drawPopup(gfx, cx, cy, this.errorMessage, COLOR_ERR_BG, COLOR_ERR_BORDER, 0xFFFF5555);
        }
        if (!this.successMessage.isEmpty()) {
            drawPopup(gfx, cx, cy, this.successMessage, COLOR_OK_BG, COLOR_OK_BORDER, 0xFF55FF55);
        }
    }

    private void drawScaledBoldCenteredString(GuiGraphics gfx, Component text, int cx, int y, float scale, int color) {
        int scaledX = Math.round(cx / scale) - this.font.width(text) / 2;
        int scaledY = Math.round(y / scale);
        gfx.pose().pushMatrix();
        gfx.pose().scale(scale, scale);
        gfx.drawString(this.font, text, scaledX, scaledY, color, true);
        gfx.pose().popMatrix();
    }

    private void drawPopup(GuiGraphics gfx, int cx, int cy, String msg, int bg, int border, int textColor) {
        int pw = Math.min(this.font.width(msg) + 32, this.width - 20);
        int ph = 24;
        int px = cx - pw / 2;
        int py = cy - ph / 2;
        gfx.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, border);
        gfx.fill(px, py, px + pw, py + ph, bg);
        gfx.drawCenteredString(this.font, Component.literal(msg), cx, py + (ph - this.font.lineHeight) / 2, textColor);
    }

    public boolean handleBgmClick(double mouseX, double mouseY) {
        if (BgmToggleHelper.hit(mouseX, mouseY, this.width)) {
            BgmToggleHelper.toggle(this.minecraft); return true;
        }
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (doubled || event.button() != 0) return super.mouseClicked(event, doubled);
        if (handleBgmClick(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubled);
    }
}
