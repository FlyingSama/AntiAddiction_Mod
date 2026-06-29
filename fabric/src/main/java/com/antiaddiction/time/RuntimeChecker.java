package com.antiaddiction.time;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.network.ApiClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public class RuntimeChecker {

    private static boolean registered;
    public static int countdownSeconds = -1;
    private static long lastCountdownTick = 0;
    private static long lastRuleCheckMs = 0L;
    /** 防止 disconnect 的 while 循环内 tick 事件重入 enforceRestriction，导致嵌套 disconnect 卡死 */
    private static volatile boolean enforcing = false;

    public static void init() {
        if (registered) return;
        registered = true;

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            // disconnect 正在进行时，内部 runTick 仍会触发此事件，必须提前返回防止重入
            if (enforcing) return;

            PlayerDataManager data = PlayerDataManager.getInstance();
            if (!data.isMinor()) return;
            if (client.getOverlay() != null) return;
            if (client.currentScreen instanceof com.antiaddiction.screen.VerificationScreen) return;
            if (client.currentScreen instanceof com.antiaddiction.screen.TimeRestrictionScreen) return;
            if (client.currentScreen instanceof com.antiaddiction.screen.CalendarScreen) return;

            if (client.player == null) {
                PlayTimeStatus status = PlayTimeChecker.getCurrentStatus();
                if (status.trustedRules && !status.allowed) {
                    client.setScreen(new com.antiaddiction.screen.TimeRestrictionScreen());
                }
                return;
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastRuleCheckMs >= 30_000L) {
                lastRuleCheckMs = nowMs;
                ApiClient.refreshRules();
            }

            PlayTimeStatus status = PlayTimeChecker.getCurrentStatus();
            if (status.trustedRules && !status.allowed) {
                countdownSeconds = -1;
                enforceRestriction(client, false);
            } else if (status.allowed && status.remainingSeconds <= 60) {
                PlayTimeChecker.markSessionStart();
                countdownSeconds = (int) status.remainingSeconds;
                lastCountdownTick = System.currentTimeMillis();
            } else if (status.allowed) {
                PlayTimeChecker.markSessionStart();
            }
        });
    }

    public static void renderCountdownHud(DrawContext ctx) {
        long now = System.currentTimeMillis();
        if (countdownSeconds > 0 && now - lastCountdownTick >= 1000) {
            countdownSeconds--;
            lastCountdownTick = now;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        PlayerDataManager data = PlayerDataManager.getInstance();
        if (client.player == null || !data.isMinor() || !data.hasTrustedRules()) return;

        PlayTimeStatus status = PlayTimeChecker.getCurrentStatus();
        if (!status.allowed) return;

        String line1;
        String line2;
        if (status.limited) {
            line1 = "今日剩余 " + formatDuration(status.remainingSeconds);
            line2 = "可玩时段 " + status.windowText;
        } else {
            line1 = "当前时间 " + status.currentTimeText;
            line2 = "可玩时段 " + status.windowText + " · 剩余 " + formatDuration(status.windowRemainingSeconds);
        }

        int x = 8;
        int y = client.getWindow().getScaledHeight() - 34;
        int w = Math.max(client.textRenderer.getWidth(line1), client.textRenderer.getWidth(line2));

        ctx.fill(x - 4, y - 4, x + w + 6, y + client.textRenderer.fontHeight * 2 + 6, 0x99000000);
        ctx.drawCenteredTextWithShadow(client.textRenderer, line1, x + client.textRenderer.getWidth(line1) / 2, y, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(client.textRenderer, line2, x + client.textRenderer.getWidth(line2) / 2, y + client.textRenderer.fontHeight + 2, 0xFFFFD166);
    }

    public static String formatDuration(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + "小时" + String.format("%02d", m) + "分";
        return m + ":" + String.format("%02d", sec);
    }

    private static void enforceRestriction(MinecraftClient client, boolean disconnectMultiplayer) {
        if (enforcing) return;
        enforcing = true;
        try {
            if (client.world != null) {
                // 走原生 disconnectFromWorld 路径（Yarn 映射为 disconnect(Text)）
                // 该方法正确处理单人/多人判断，保存世界后显示 TitleScreen，MixinTitleScreen 重定向到 TimeRestrictionScreen
                client.disconnect(Text.empty());
            } else {
                client.setScreen(new com.antiaddiction.screen.TimeRestrictionScreen());
            }
        } finally {
            enforcing = false;
        }
    }
}
