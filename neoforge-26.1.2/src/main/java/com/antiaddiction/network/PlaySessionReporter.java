package com.antiaddiction.network;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.security.SignedPayloadVerifier;
import com.antiaddiction.time.PlayTimeChecker;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.UUID;

public final class PlaySessionReporter {

    public static final long HEARTBEAT_MS = 10_000L;
    public static final long FLUSH_MS = 15_000L;

    private static ActiveSession active;

    private PlaySessionReporter() {}

    public static void tick(Minecraft client) {
        if (client == null) return;
        PlaySessionEventQueue.flushPeriodically(FLUSH_MS);
        if (!PlayerDataManager.getInstance().hasUsableCredentialForCurrentUser()) {
            endIfActive();
            return;
        }
        String credentialToken = ApiClient.currentPlaySessionCredentialToken();
        if (credentialToken.isBlank()) {
            endIfActive();
            return;
        }
        Target target = detectTarget(client);
        if (target == null) {
            endIfActive();
            return;
        }
        if (active == null || !active.sameTarget(target) || !active.sameCredentialToken(credentialToken)) {
            endIfActive();
            start(target, credentialToken);
            return;
        }
        if (System.currentTimeMillis() - active.lastHeartbeatMs >= HEARTBEAT_MS) {
            heartbeat();
        }
    }

    private static Target detectTarget(Minecraft client) {
        if (client.level == null || client.player == null) return null;
        if (client.isLocalServer()) {
            String name = client.getSingleplayerServer() != null
                    ? client.getSingleplayerServer().getWorldData().getLevelName()
                    : "singleplayer";
            return new Target("singleplayer", name, name);
        }
        if (client.getCurrentServer() != null) {
            String name = client.getCurrentServer().name;
            String address = client.getCurrentServer().ip;
            return new Target("multiplayer", name == null || name.isBlank() ? address : name, address);
        }
        return new Target("unknown", "unknown", "unknown");
    }

    private static void start(Target target, String credentialToken) {
        long now = System.currentTimeMillis();
        active = new ActiveSession(UUID.randomUUID().toString(), target, credentialToken, now, now);
        send("play_start");
    }

    private static void heartbeat() {
        if (active == null) return;
        active.lastHeartbeatMs = System.currentTimeMillis();
        send("play_heartbeat");
    }

    private static void endIfActive() {
        if (active == null) return;
        send("play_end");
        active = null;
    }

    private static void send(String eventName) {
        ActiveSession session = active;
        if (session == null) return;

        long now = System.currentTimeMillis();
        long durationSeconds = Math.max(0, (now - session.startedAtMs) / 1000);
        JsonObject body = new JsonObject();
        body.addProperty("eventId", UUID.randomUUID().toString());
        body.addProperty("sessionId", session.sessionId);
        body.addProperty("event", eventName);
        body.addProperty("targetType", session.target.type);
        body.addProperty("targetName", session.target.name);
        body.addProperty("targetId", session.target.id);
        body.addProperty("durationSeconds", durationSeconds);
        body.addProperty("playedTodaySeconds", PlayTimeChecker.getPlayedSeconds());
        body.addProperty("remainingSeconds", PlayTimeChecker.getCurrentStatus().remainingSeconds);
        body.addProperty("clientVersion", SignedPayloadVerifier.CLIENT_VERSION);
        body.addProperty("eventTimeMillis", now);

        Thread t = new Thread(() -> {
            if (!ApiClient.postPlaySessionEvent(body, session.credentialToken)) {
                JsonObject queued = body.deepCopy();
                queued.addProperty(PlaySessionEventQueue.CREDENTIAL_TOKEN_FIELD, session.credentialToken);
                PlaySessionEventQueue.enqueue(queued);
            }
        }, "AntiAddiction-PlaySessionReport");
        t.setDaemon(true);
        t.start();
    }

    private record Target(String type, String name, String id) {}

    private static final class ActiveSession {
        private final String sessionId;
        private final Target target;
        private final String credentialToken;
        private final long startedAtMs;
        private long lastHeartbeatMs;

        private ActiveSession(String sessionId, Target target, String credentialToken, long startedAtMs, long lastHeartbeatMs) {
            this.sessionId = sessionId;
            this.target = target;
            this.credentialToken = credentialToken;
            this.startedAtMs = startedAtMs;
            this.lastHeartbeatMs = lastHeartbeatMs;
        }

        private boolean sameTarget(Target other) {
            return target.equals(other);
        }

        private boolean sameCredentialToken(String other) {
            return credentialToken.equals(other);
        }
    }
}
