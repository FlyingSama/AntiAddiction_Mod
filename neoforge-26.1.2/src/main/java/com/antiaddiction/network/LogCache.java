package com.antiaddiction.network;

import com.antiaddiction.AntiAddictionMod;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class LogCache {

    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("antiaddiction_logs.json");
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<JsonObject>>(){}.getType();

    private static final Object LOCK = new Object();

    public static void save(String userName, boolean minor, String action) {
        synchronized (LOCK) {
            try {
                List<JsonObject> list = readAll();
                JsonObject entry = new JsonObject();
                entry.addProperty("userName", userName);
                entry.addProperty("minor", minor);
                entry.addProperty("action", action);
                entry.addProperty("ts", System.currentTimeMillis() / 1000);
                list.add(entry);
                Files.write(FILE, GSON.toJson(list).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                AntiAddictionMod.LOGGER.warn("[防沉迷] 本地日志缓存写入失败: {}", e.getMessage());
            }
        }
    }

    public static void flush(String backendUrl) {
        synchronized (LOCK) {
            try {
                List<JsonObject> list = readAll();
                if (list.isEmpty()) return;

                String json = GSON.toJson(list);
                URL url = new URL(backendUrl + "/api/report/batch");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    Files.write(FILE, "[]".getBytes(StandardCharsets.UTF_8));
                    AntiAddictionMod.LOGGER.info("[防沉迷] 本地缓存已刷新，共 {} 条记录", list.size());
                } else {
                    AntiAddictionMod.LOGGER.warn("[防沉迷] 缓存刷新失败，HTTP {}", code);
                }
            } catch (Exception e) {
                AntiAddictionMod.LOGGER.warn("[防沉迷] 缓存刷新异常: {}", e.getMessage());
            }
        }
    }

    private static List<JsonObject> readAll() throws IOException {
        if (!Files.exists(FILE)) return new ArrayList<>();
        byte[] data = Files.readAllBytes(FILE);
        if (data.length == 0) return new ArrayList<>();
        List<JsonObject> list = GSON.fromJson(new String(data, StandardCharsets.UTF_8), LIST_TYPE);
        return list != null ? list : new ArrayList<>();
    }
}
