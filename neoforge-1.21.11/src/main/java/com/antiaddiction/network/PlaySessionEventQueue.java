package com.antiaddiction.network;

import com.antiaddiction.AntiAddictionMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class PlaySessionEventQueue {

    public static final String CREDENTIAL_TOKEN_FIELD = "_credentialToken";

    private static final int MAX_EVENTS = 500;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static long lastFlushMs = 0L;
    private static long lastMissingCredentialTokenWarnMs = 0L;
    private static boolean flushInProgress = false;

    private PlaySessionEventQueue() {}

    public static synchronized void enqueue(JsonObject event) {
        if (event == null) return;

        JsonArray events = readQueue();
        if (events == null) events = new JsonArray();
        events.add(event.deepCopy());
        writeQueue(events);
    }

    public static synchronized void flush() {
        JsonArray events = readQueue();
        if (events == null || events.isEmpty()) return;

        JsonArray remaining = new JsonArray();
        boolean failed = false;
        for (JsonElement element : events) {
            if (failed || !element.isJsonObject()) {
                remaining.add(element.deepCopy());
                continue;
            }

            JsonObject event = element.getAsJsonObject();
            String credentialToken = event.has(CREDENTIAL_TOKEN_FIELD) && !event.get(CREDENTIAL_TOKEN_FIELD).isJsonNull()
                    ? event.get(CREDENTIAL_TOKEN_FIELD).getAsString()
                    : "";
            if (credentialToken.isBlank()) {
                warnMissingCredentialToken();
                failed = true;
                remaining.add(event.deepCopy());
                continue;
            }

            JsonObject body = event.deepCopy();
            body.remove(CREDENTIAL_TOKEN_FIELD);
            if (!ApiClient.postPlaySessionEvent(body, credentialToken)) {
                failed = true;
                remaining.add(event.deepCopy());
            }
        }

        writeQueue(remaining);
    }

    public static void flushPeriodically(long intervalMs) {
        long now = System.currentTimeMillis();
        synchronized (PlaySessionEventQueue.class) {
            if (flushInProgress || now - lastFlushMs < intervalMs) return;
            lastFlushMs = now;
            flushInProgress = true;
        }

        Thread t = new Thread(() -> {
            try {
                flush();
            } finally {
                synchronized (PlaySessionEventQueue.class) {
                    flushInProgress = false;
                }
            }
        }, "AntiAddiction-PlaySessionFlush");
        t.setDaemon(true);
        t.start();
    }

    private static Path queuePath() {
        return FMLPaths.GAMEDIR.get()
                .resolve("antiaddiction_play_sessions_" + ApiClient.getEnvironmentName() + ".json");
    }

    private static void warnMissingCredentialToken() {
        long now = System.currentTimeMillis();
        if (now - lastMissingCredentialTokenWarnMs < 60_000L) return;
        lastMissingCredentialTokenWarnMs = now;
        AntiAddictionMod.LOGGER.warn("[防沉迷] 游玩日志队列缺少 credential token，保留等待诊断");
    }

    private static JsonArray readQueue() {
        Path path = queuePath();
        if (!Files.exists(path)) return new JsonArray();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element != null && element.isJsonArray()) {
                return element.getAsJsonArray();
            }
            moveCorruptQueue(path, "根元素不是数组");
        } catch (Exception e) {
            moveCorruptQueue(path, e.getMessage());
        }
        return new JsonArray();
    }

    private static void moveCorruptQueue(Path path, String reason) {
        Path corruptPath = path.resolveSibling(path.getFileName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(path, corruptPath, StandardCopyOption.REPLACE_EXISTING);
            AntiAddictionMod.LOGGER.warn("[防沉迷] 游玩日志队列损坏，已移至 {}: {}", corruptPath, reason);
        } catch (Exception moveError) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] 游玩日志队列损坏且无法移走: {}; {}", reason, moveError.getMessage());
        }
    }

    private static JsonArray capToMaxEvents(JsonArray events) {
        JsonArray capped = new JsonArray();
        if (events == null) return capped;
        int start = Math.max(0, events.size() - MAX_EVENTS);
        for (int i = start; i < events.size(); i++) {
            capped.add(events.get(i).deepCopy());
        }
        return capped;
    }

    private static void writeQueue(JsonArray events) {
        Path path = queuePath();
        try {
            JsonArray capped = capToMaxEvents(events);
            if (events == null || events.isEmpty()) {
                Files.deleteIfExists(path);
                return;
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(capped, writer);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] 保存游玩日志队列失败: {}", e.getMessage());
        }
    }
}
