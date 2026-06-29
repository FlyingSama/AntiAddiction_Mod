package com.antiaddiction.screen;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.data.VerificationResult;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.time.PlayTimeChecker;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class VerificationScreen extends Screen {

    private TextFieldWidget nameField;
    private TextFieldWidget idCardField;
    private String errorMessage = "";
    private String successMessage = "";
    private int attempts = 0;
    private int jumpTicks = -1;
    private final BgmToggleHelper bgm = new BgmToggleHelper();

    private static final int COLOR_BG_TOP      = 0xFF0D1B2A;
    private static final int COLOR_BG_BOTTOM   = 0xFF1B2838;
    private static final int COLOR_ACCENT      = 0xFF4A90E2;
    private static final int COLOR_BOX         = 0xCC1E2D3D;
    private static final int COLOR_OK_BG       = 0xCC1B5E20;
    private static final int COLOR_OK_BORDER   = 0xFF4CAF50;
    private static final int COLOR_ERR_BG      = 0xCC5E1B1B;
    private static final int COLOR_ERR_BORDER  = 0xFFEF4444;
    private static final int SUCCESS_DISPLAY_TICKS = 60;

    public VerificationScreen() {
        super(Text.literal("防沉迷实名认证"));
    }

    @Override
    protected void init() {
        int cx = this.width  / 2;
        int cy = this.height / 2;

        this.nameField = new TextFieldWidget(
                this.textRenderer, cx - 120, cy - 22, 240, 22,
                Text.literal("姓名")
        );
        this.nameField.setMaxLength(30);
        this.nameField.setPlaceholder(Text.literal("请输入真实姓名"));
        this.addDrawableChild(this.nameField);

        this.idCardField = new TextFieldWidget(
                this.textRenderer, cx - 120, cy + 14, 240, 22,
                Text.literal("身份证号")
        );
        this.idCardField.setMaxLength(18);
        this.idCardField.setPlaceholder(Text.literal("请输入18位居民身份证号码"));
        this.addDrawableChild(this.idCardField);

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("提交实名认证"),
                btn -> this.doVerify()
        ).dimensions(cx - 100, cy + 48, 200, 22).build());
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
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.key() == 257 || keyInput.key() == 335) {
            doVerify();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    private void doVerify() {
        if (jumpTicks >= 0) return;

        String name   = this.nameField.getText().trim();
        String idCard = this.idCardField.getText().trim().toUpperCase();

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
            this.nameField.setText("");
            this.idCardField.setText("");
            this.nameField.setFocused(true);
            return;
        }

        ApiClient.reportSessionStart(PlayerDataManager.getInstance().getUserName(), result.isMinor());
        this.successMessage = result.getMessage();
        this.jumpTicks = SUCCESS_DISPLAY_TICKS;
    }

    private void doJump() {
        assert this.client != null;
        if (PlayerDataManager.getInstance().isMinor() && !PlayTimeChecker.isPlayAllowed()) {
            this.client.setScreen(new TimeRestrictionScreen());
        } else {
            PlayTimeChecker.markSessionStart();
            this.client.setScreen(new TitleScreen(false));
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width  / 2;
        int cy = this.height / 2;

        ctx.fillGradient(0, 0, this.width, this.height, COLOR_BG_TOP, COLOR_BG_BOTTOM);

        bgm.render(ctx, this.client, this.width);

        int boxW = 280, boxH = 200;
        int boxX = cx - boxW / 2, boxY = cy - boxH / 2;

        // 面板
        ctx.fill(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, COLOR_ACCENT);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, COLOR_BOX);

        // 顶部提示 — 蓝框内部顶端, 2x 放大（必须在 box 填充之后）
        float ts = 2f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(ts, ts);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("请完成实名认证"), (int)(cx / ts), (int)((boxY + 4) / ts), 0xFFFFAA00);
        ctx.getMatrices().popMatrix();

        // 标题
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("游戏实名认证系统"), cx, boxY + 12, 0x4A90E2);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("根据《网络游戏管理办法》，请完成实名认证"), cx, boxY + 26, 0xAAAAAA);

        ctx.fill(boxX + 10, boxY + 38, boxX + boxW - 10, boxY + 39, 0xFF4A90E2);

        String label1 = "姓　　名：";
        int lw1 = this.textRenderer.getWidth(label1);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label1), cx - 120 + lw1 / 2, cy - 36, 0xFFFFFF);
        String label2 = "身份证号：";
        int lw2 = this.textRenderer.getWidth(label2);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label2), cx - 120 + lw2 / 2, cy, 0xFFFFFF);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("[ 生产模式必须连接 HTTPS 可信后端 | 本地不保存明文身份信息 ]"),
                cx, this.height - 20, 0x555555);

        super.render(ctx, mouseX, mouseY, delta);

        // 提示框置顶居中显示，避免被输入框/按钮遮挡
        if (!this.errorMessage.isEmpty()) {
            drawPopup(ctx, cx, this.height / 2, this.errorMessage, COLOR_ERR_BG, COLOR_ERR_BORDER, 0xFFFF5555);
        }
        if (!this.successMessage.isEmpty()) {
            drawPopup(ctx, cx, this.height / 2, this.successMessage, COLOR_OK_BG, COLOR_OK_BORDER, 0xFF55FF55);
        }
    }

    private void drawPopup(DrawContext ctx, int cx, int cy, String msg, int bg, int border, int textColor) {
        int pw = Math.min(this.textRenderer.getWidth(msg) + 32, this.width - 20);
        int ph = 24;
        int px = cx - pw / 2;
        int py = cy - ph / 2;
        ctx.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, border);
        ctx.fill(px, py, px + pw, py + ph, bg);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(msg), cx, py + (ph - this.textRenderer.fontHeight) / 2, textColor);
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void close() { }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (doubled || click.button() != 0) return super.mouseClicked(click, doubled);
        if (bgm.mouseClicked(click.x(), click.y(), this.width, this.client)) return true;
        return super.mouseClicked(click, doubled);
    }
}
