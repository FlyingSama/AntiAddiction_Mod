package com.antiaddiction.network;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.data.VerificationResult;
import com.antiaddiction.security.MinecraftIdentity;
import com.antiaddiction.security.SignedPayloadVerifier;
import com.antiaddiction.security.TrustedClock;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

public class ApiClient {

    private static final int TIMEOUT_MS = 5000;

    private static volatile String backendUrl = "";
    private static volatile String environmentName = "production";
    private static volatile boolean developmentMode = false;
    private static volatile String developmentPublicKeyOverride = "";
    private static volatile String configError = "";
    private static volatile long lastRefreshMs = 0L;
    private static volatile long lastSuccessfulRulesSyncMs = 0L;
    private static volatile long syncFailureSinceMs = 0L;
    private static volatile long lastPlaySessionPreflightWarnMs = 0L;
    private static volatile boolean periodicStarted = false;

    public static void loadConfigAndFetchHolidays() {
        loadConfig();
        PlayerDataManager.getInstance().reload();
        if (!isConfigured()) {
            markRulesSyncFailure();
            return;
        }
        Thread t = new Thread(() -> {
            fetchRulesBlocking();
            startPeriodicSync();
        }, "AntiAddiction-Fetch");
        t.setDaemon(true);
        t.start();
    }

    private static void loadConfig() {
        try {
            Path configPath = FabricLoader.getInstance().getConfigDir().resolve("antiaddiction.properties");
            File configFile = configPath.toFile();
            if (!configFile.exists()) {
                try (PrintWriter w = new PrintWriter(configFile, StandardCharsets.UTF_8)) {
                    w.println("# 防沉迷安全配置");
                    w.println("# production 为默认模式；临时允许 HTTP，拿到证书后建议改回 HTTPS");
                    w.println("environment=production");
                    w.println("backend_url=");
                    w.println("# development 仅允许 http://localhost 或 http://127.0.0.1，可配置 development_public_key_base64");
                    w.println("development_public_key_base64=");
                }
                configError = "未配置 backend_url";
                return;
            }

            Properties props = new Properties();
            try (Reader r = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                props.load(r);
            }

            environmentName = props.getProperty("environment", "production").trim().toLowerCase();
            developmentMode = "development".equals(environmentName);
            if (!developmentMode) environmentName = "production";
            backendUrl = props.getProperty("backend_url", "").trim();
            // 容错：仪表盘复制的接入配置自带 "backend_url=" 前缀，用户整行粘成值时去除重复前缀
            while (backendUrl.regionMatches(true, 0, "backend_url=", 0, 12)) {
                backendUrl = backendUrl.substring(12).trim();
            }
            developmentPublicKeyOverride = developmentMode
                    ? props.getProperty("development_public_key_base64", "").trim()
                    : "";

            configError = validateBackendUrl(backendUrl);
            if (!configError.isEmpty()) {
                AntiAddictionMod.LOGGER.warn("[防沉迷] 后端配置无效: {}", configError);
                backendUrl = "";
            } else {
                AntiAddictionMod.LOGGER.info("[防沉迷] 安全模式: {}, 后端: {}", environmentName, backendUrl);
            }
        } catch (Exception e) {
            configError = e.getMessage();
            AntiAddictionMod.LOGGER.warn("[防沉迷] 加载配置失败: {}", e.getMessage());
        }
    }

    public static boolean isConfigured() {
        return backendUrl != null && !backendUrl.isBlank() && configError.isEmpty();
    }

    public static boolean isDevelopmentMode() {
        return developmentMode;
    }

    public static String getEnvironmentName() {
        return developmentMode ? "development" : "production";
    }

    public static String getDevelopmentPublicKeyOverride() {
        return developmentPublicKeyOverride;
    }

    public static VerificationResult verifyIdentity(String name, String idCard) {
        if (!isConfigured()) {
            return VerificationResult.invalid(configError.isEmpty() ? "未配置可信后端，无法实名认证" : configError);
        }

        try {
            JsonObject body = new JsonObject();
            body.addProperty("name", name);
            body.addProperty("idCard", idCard);
            body.addProperty("minecraftUuid", MinecraftIdentity.uuid());
            body.addProperty("minecraftName", MinecraftIdentity.name());
            body.addProperty("clientVersion", SignedPayloadVerifier.CLIENT_VERSION);

            JsonObject resp = JsonParser.parseString(post(backendUrl + "/api/verify", body.toString(), "")).getAsJsonObject();
            if (!resp.has("credentialBytes") || !resp.has("signature")) {
                return VerificationResult.invalid(SignedPayloadVerifier.getString(resp, "error"));
            }

            String credentialBytes = SignedPayloadVerifier.getString(resp, "credentialBytes");
            String signature = SignedPayloadVerifier.getString(resp, "signature");
            JsonObject credential = SignedPayloadVerifier.verify(
                    credentialBytes, signature, developmentMode, developmentPublicKeyOverride);

            if (!MinecraftIdentity.uuid().equals(SignedPayloadVerifier.getString(credential, "minecraftUuid"))
                    || !MinecraftIdentity.name().equals(SignedPayloadVerifier.getString(credential, "minecraftName"))) {
                return VerificationResult.invalid("credential 与当前 Minecraft 身份不匹配");
            }
            long issuedAt = SignedPayloadVerifier.getLong(credential, "issuedAt");
            long expiresAt = SignedPayloadVerifier.getLong(credential, "expiresAt");
            if (expiresAt <= issuedAt) return VerificationResult.invalid("credential 时间字段无效");

            TrustedClock.acceptSignedServerTime(issuedAt);
            if (!TrustedClock.wasLastNtpAvailable()) {
                AntiAddictionMod.LOGGER.warn("[防沉迷] NTP 校验不可用，本次使用后端签名时间");
            }
            PlayerDataManager.getInstance().saveVerifiedCredential(credentialBytes, signature, credential, issuedAt, name);

            boolean minor = SignedPayloadVerifier.getBoolean(credential, "minor");
            if (minor && !fetchRulesBlocking()) {
                PlayerDataManager.getInstance().clearVerification();
                return VerificationResult.invalid("无法获取可信防沉迷规则");
            }

            return VerificationResult.valid(SignedPayloadVerifier.getInt(credential, "age"), minor);
        } catch (Exception e) {
            markRulesSyncFailure();
            AntiAddictionMod.LOGGER.warn("[防沉迷] 实名认证失败: {}", e.getMessage());
            return VerificationResult.invalid("后端认证失败: " + e.getMessage());
        }
    }

    public static boolean fetchRulesBlocking() {
        if (!isConfigured()) {
            markRulesSyncFailure();
            return false;
        }
        try {
            PlayerDataManager pdm = PlayerDataManager.getInstance();
            String credentialToken = pdm.getCredentialBytes().isBlank()
                    ? ""
                    : pdm.getCredentialBytes() + "." + pdm.getCredentialSignature();
            JsonObject resp = JsonParser.parseString(get(backendUrl + "/api/rules", credentialToken)).getAsJsonObject();
            String rulesBytes = SignedPayloadVerifier.getString(resp, "rulesBytes");
            String signature = SignedPayloadVerifier.getString(resp, "signature");
            JsonObject rules = SignedPayloadVerifier.verify(rulesBytes, signature, developmentMode, developmentPublicKeyOverride);

            if (SignedPayloadVerifier.compareVersions(SignedPayloadVerifier.CLIENT_VERSION,
                    SignedPayloadVerifier.getString(rules, "minClientVersion")) < 0) {
                throw new SecurityException("客户端版本低于规则要求");
            }
            long serverTime = SignedPayloadVerifier.getLong(rules, "serverTimeEpochMillis");
            if (SignedPayloadVerifier.getLong(rules, "rulesExpiresAt") <= serverTime) {
                throw new SecurityException("规则已过期");
            }
            if (!PlayerDataManager.getInstance().canAcceptRules(rules)) {
                throw new SecurityException("检测到旧规则回放");
            }

            TrustedClock.acceptSignedServerTime(serverTime);
            if (!TrustedClock.wasLastNtpAvailable()) {
                AntiAddictionMod.LOGGER.warn("[防沉迷] NTP 校验不可用，本次使用后端签名时间");
            }
            PlayerDataManager.getInstance().saveRules(rulesBytes, signature, rules, serverTime);
            lastSuccessfulRulesSyncMs = System.currentTimeMillis();
            syncFailureSinceMs = 0L;
            AntiAddictionMod.LOGGER.info("[防沉迷] 已同步签名规则 version={}", SignedPayloadVerifier.getLong(rules, "rulesVersion"));
            return true;
        } catch (Exception e) {
            markRulesSyncFailure();
            AntiAddictionMod.LOGGER.warn("[防沉迷] 规则同步失败: {}", e.getMessage());
            return false;
        }
    }

    public static void refreshRules() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 3000) return;
        lastRefreshMs = now;
        Thread t = new Thread(ApiClient::fetchRulesBlocking, "AntiAddiction-Refresh");
        t.setDaemon(true);
        t.start();
    }

    public static boolean refreshRulesBlocking() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 1000) return PlayerDataManager.getInstance().hasTrustedRules();
        lastRefreshMs = now;
        return fetchRulesBlocking();
    }

    public static boolean isMinorSyncFailureExceeded() {
        if (!PlayerDataManager.getInstance().isMinor()) return false;
        if (!isConfigured()) return true;
        if (!PlayerDataManager.getInstance().hasTrustedRules()) return true;
        return syncFailureSinceMs > 0 && System.currentTimeMillis() - syncFailureSinceMs > 60_000L;
    }

    public static void reportSessionStart(String userName, boolean minor) {
        report("session_start");
    }

    public static void reportGameStart() {
        if (PlayerDataManager.getInstance().hasUsableCredentialForCurrentUser()) {
            report("game_start");
        }
    }

    public static String currentPlaySessionCredentialToken() {
        PlayerDataManager pdm = PlayerDataManager.getInstance();
        if (!isConfigured()) {
            warnPlaySessionPreflightFailure("后端未配置");
            return "";
        }
        if (pdm.getCredentialBytes().isBlank() || pdm.getCredentialSignature().isBlank()) {
            warnPlaySessionPreflightFailure("credential 不完整");
            return "";
        }
        return pdm.getCredentialBytes() + "." + pdm.getCredentialSignature();
    }

    public static boolean postPlaySessionEvent(JsonObject body) {
        String credentialToken = currentPlaySessionCredentialToken();
        if (credentialToken.isBlank()) return false;
        return postPlaySessionEvent(body, credentialToken);
    }

    public static boolean postPlaySessionEvent(JsonObject body, String credentialToken) {
        if (!isConfigured()) {
            warnPlaySessionPreflightFailure("后端未配置");
            return false;
        }
        if (credentialToken == null || credentialToken.isBlank()) {
            warnPlaySessionPreflightFailure("credential token 为空");
            return false;
        }
        try {
            post(backendUrl + "/api/play-session/event", body.toString(), credentialToken);
            return true;
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] 游玩日志上报失败: {}", e.getMessage());
            return false;
        }
    }

    private static void report(String action) {
        PlayerDataManager pdm = PlayerDataManager.getInstance();
        if (!isConfigured() || pdm.getCredentialBytes().isBlank()) return;
        Thread t = new Thread(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("action", action);
                post(backendUrl + "/api/report", body.toString(),
                        pdm.getCredentialBytes() + "." + pdm.getCredentialSignature());
            } catch (Exception e) {
                AntiAddictionMod.LOGGER.warn("[防沉迷] 审计日志上报失败: {}", e.getMessage());
            }
        }, "AntiAddiction-Report");
        t.setDaemon(true);
        t.start();
    }

    private static void startPeriodicSync() {
        if (periodicStarted) return;
        periodicStarted = true;
        Thread t = new Thread(() -> {
            while (isConfigured()) {
                try { Thread.sleep(30_000L); }
                catch (InterruptedException e) { break; }
                fetchRulesBlocking();
            }
        }, "AntiAddiction-Periodic");
        t.setDaemon(true);
        t.start();
    }

    private static void markRulesSyncFailure() {
        if (syncFailureSinceMs == 0L) syncFailureSinceMs = System.currentTimeMillis();
    }

    private static void warnPlaySessionPreflightFailure(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastPlaySessionPreflightWarnMs < 60_000L) return;
        lastPlaySessionPreflightWarnMs = now;
        AntiAddictionMod.LOGGER.warn("[防沉迷] 游玩日志上报失败: {}", reason);
    }

    private static String validateBackendUrl(String url) {
        if (url == null || url.isBlank()) return "未配置 backend_url";
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (developmentMode) {
                if ("https".equals(scheme)) return "";
                if ("http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host))) return "";
                return "development 仅允许 HTTPS 或 http://localhost/http://127.0.0.1";
            }
            if (!"https".equals(scheme) && !"http".equals(scheme)) return "production backend_url 必须使用 HTTP 或 HTTPS";
            return "";
        } catch (Exception e) {
            return "backend_url 格式错误";
        }
    }

    private static String get(String urlStr) throws IOException {
        return get(urlStr, "");
    }

    private static String get(String urlStr, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        return readResponse(conn);
    }

    private static String post(String urlStr, String jsonBody, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (bearerToken != null && !bearerToken.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    public record StorageKeyResult(byte[] key, String kid) {}

    public static StorageKeyResult fetchStorageKey(String saveName) throws IOException {
        PlayerDataManager pdm = PlayerDataManager.getInstance();
        if (!isConfigured() || pdm.getCredentialBytes().isBlank()) {
            markRulesSyncFailure();
            throw new IOException("未配置后端或未完成实名认证");
        }
        JsonObject body = new JsonObject();
        body.addProperty("saveName", saveName);
        String resp = post(backendUrl + "/api/storage-key", body.toString(),
                pdm.getCredentialBytes() + "." + pdm.getCredentialSignature());
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
        if (!json.has("keyBytes") || !json.has("kid")) {
            throw new IOException("storage-key 响应格式错误: " + resp);
        }
        byte[] keyBytes = Base64.getUrlDecoder().decode(json.get("keyBytes").getAsString());
        String kid = json.get("kid").getAsString();
        if (keyBytes.length != 32) throw new IOException("storage-key 长度非法: " + keyBytes.length);
        return new StorageKeyResult(keyBytes, kid);
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " " + sb);
        return sb.toString();
    }
}
