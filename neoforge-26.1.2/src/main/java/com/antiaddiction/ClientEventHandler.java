package com.antiaddiction;

import com.antiaddiction.data.PlayerDataManager;

import com.antiaddiction.screen.BgmToggleHelper;
import com.antiaddiction.screen.TimeRestrictionScreen;
import com.antiaddiction.screen.VerificationScreen;
import com.antiaddiction.time.PlayTimeChecker;
import com.antiaddiction.time.RuntimeChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

@EventBusSubscriber(modid = AntiAddictionMod.MOD_ID, value = Dist.CLIENT)
public class ClientEventHandler {

    private static boolean pendingVerification;
    private static boolean pendingTimeRestriction;

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;

        PlayerDataManager mgr = PlayerDataManager.getInstance();

        if (!mgr.hasUsableCredentialForCurrentUser()) {
            event.setCanceled(true);
            pendingVerification = true;
        } else if (mgr.isMinor() && (!mgr.hasTrustedRules() || !PlayTimeChecker.isPlayAllowed())) {
            event.setCanceled(true);
            pendingTimeRestriction = true;
        } else {
            // 通过门控进入主界面：默认开启 BGM
            com.antiaddiction.screen.BgmToggleHelper.setMusicOn(true);
        }
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        BgmToggleHelper.enforceMuted(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        com.antiaddiction.network.PlaySessionReporter.tick(client);

        // 等待 Loading Overlay 完全关闭后再设屏
        if (client.getOverlay() != null) return;

        if (pendingVerification) {
            pendingVerification = false;
            client.setScreen(new VerificationScreen());
        } else if (pendingTimeRestriction) {
            pendingTimeRestriction = false;
            client.setScreen(new TimeRestrictionScreen());
        }
    }

    @SubscribeEvent
    public static void onSelectMusic(SelectMusicEvent event) {
        if (BgmToggleHelper.shouldBlockMusic()) {
            event.overrideMusic(null);
        }
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (BgmToggleHelper.shouldBlockMusic() && sound != null && sound.getSource() == SoundSource.MUSIC) {
            event.setSound(null);
        }
    }

    @SubscribeEvent
    public static void onMouseClickedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) return;

        var screen = event.getScreen();
        if (screen instanceof TitleScreen || screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            Minecraft mc = Minecraft.getInstance();
            int sw = mc.getWindow().getGuiScaledWidth();
            if (com.antiaddiction.screen.CalendarOverlay.hit(event.getMouseX(), event.getMouseY(), sw)) {
                mc.setScreen(new com.antiaddiction.screen.CalendarScreen(screen));
                event.setCanceled(true);
                return;
            }
        }
        if (screen instanceof com.antiaddiction.screen.CalendarScreen cal) {
            if (cal.handleMouseClick(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        }
        if (screen instanceof com.antiaddiction.screen.VerificationScreen vs) {
            if (vs.handleBgmClick(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        }
        if (screen instanceof com.antiaddiction.screen.TimeRestrictionScreen ts) {
            if (ts.handleBgmClick(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        RuntimeChecker.renderCountdownHud(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        var screen = event.getScreen();
        if (screen instanceof TitleScreen || screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            Minecraft mc = Minecraft.getInstance();
            com.antiaddiction.screen.CalendarOverlay.render(
                    event.getGuiGraphics(), mc, mc.getWindow().getGuiScaledWidth());
        }
    }
}
