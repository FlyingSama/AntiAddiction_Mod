package com.antiaddiction.data;

import com.antiaddiction.AntiAddictionMod;
import com.antiaddiction.network.ApiClient;
import com.antiaddiction.security.MinecraftIdentity;
import com.antiaddiction.security.SignedPayloadVerifier;
import com.antiaddiction.security.TrustedClock;
import com.antiaddiction.storage.StorageKeyManager;
import com.antiaddiction.time.PlayTimeChecker;
import com.google.gson.*;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class PlayerDataManager {

    private static PlayerDataManager instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String credentialBytes = "";
    private String credentialSignature = "";
    private String rulesBytes = "";
    private String rulesSignature = "";
    private String minecraftUuid = "";
    private String minecraftName = "";
    private String sessionDisplayName = "";
    private String sub = "";
    private String userDigest = "";
    private String idDigest = "";
    private int age = -1;
    private boolean minor = false;
    private boolean verified = false;
    private long credentialIssuedAt = 0L;
    private long credentialExpiresAt = 0L;
    private long lastTrustedTime = 0L;
    private long lastTrustedLocalTime = 0L;
    private long lastRulesVersion = 0L;
    private long lastRulesIssuedAt = 0L;
    private long rulesExpiresAt = 0L;

    private PlayerDataManager() {
        load();
    }

    public static PlayerDataManager getInstance() {
        if (instance == null) instance = new PlayerDataManager();
        return instance;
    }

    public void reload() {
        resetMemory();
        load();
    }

    private Path getDataPath() {
        return FMLPaths.GAMEDIR.get().resolve("antiaddiction_data_" + ApiClient.getEnvironmentName() + ".json");
    }

    private void load() {
        File file = getDataPath().toFile();
        if (!file.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            SaveData saved = GSON.fromJson(reader, SaveData.class);
            if (saved == null || !saved.verified) return;

            JsonObject credential = SignedPayloadVerifier.verify(
                    saved.credentialBytes, saved.credentialSignature,
                    ApiClient.isDevelopmentMode(), ApiClient.getDevelopmentPublicKeyOverride());
            applyCredential(saved.credentialBytes, saved.credentialSignature, credential);
            this.lastTrustedTime = saved.lastTrustedTime;
            this.lastTrustedLocalTime = saved.lastTrustedLocalTime;
            TrustedClock.restore(this.lastTrustedTime);

            if (!hasUsableCredentialForCurrentUser()) return;

            if (saved.rulesBytes != null && !saved.rulesBytes.isBlank()) {
                JsonObject rules = SignedPayloadVerifier.verify(
                        saved.rulesBytes, saved.rulesSignature,
                        ApiClient.isDevelopmentMode(), ApiClient.getDevelopmentPublicKeyOverride());
                applyRules(saved.rulesBytes, saved.rulesSignature, rules, false);
            }
            AntiAddictionMod.LOGGER.info("[防沉迷] 已加载签名认证状态: {}", minecraftName);
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] 本地认证状态无效，已清除: {}", e.getMessage());
            clearVerification();
        }
    }

    public void saveVerifiedCredential(String bytes, String signature, JsonObject credential, long trustedTime) {
        saveVerifiedCredential(bytes, signature, credential, trustedTime, "");
    }

    public void saveVerifiedCredential(String bytes, String signature, JsonObject credential, long trustedTime, String verifiedDisplayName) {
        applyCredential(bytes, signature, credential);
        this.sessionDisplayName = normalizeDisplayName(verifiedDisplayName);
        this.verified = true;
        updateTrustedTime(trustedTime);
        save();
    }

    public boolean canAcceptRules(JsonObject rules) {
        long version = SignedPayloadVerifier.getLong(rules, "rulesVersion");
        long issuedAt = SignedPayloadVerifier.getLong(rules, "issuedAt");
        return (lastRulesVersion <= 0 || version >= lastRulesVersion)
                && (lastRulesIssuedAt <= 0 || issuedAt >= lastRulesIssuedAt);
    }

    public void saveRules(String bytes, String signature, JsonObject rules, long trustedTime) {
        applyRules(bytes, signature, rules, true);
        updateTrustedTime(trustedTime);
        save();
    }

    private void applyCredential(String bytes, String signature, JsonObject credential) {
        this.credentialBytes = bytes == null ? "" : bytes;
        this.credentialSignature = signature == null ? "" : signature;
        this.minecraftUuid = SignedPayloadVerifier.getString(credential, "minecraftUuid");
        this.minecraftName = SignedPayloadVerifier.getString(credential, "minecraftName");
        this.sub = SignedPayloadVerifier.getString(credential, "sub");
        this.userDigest = SignedPayloadVerifier.getString(credential, "userDigest");
        this.idDigest = SignedPayloadVerifier.getString(credential, "idDigest");
        this.age = SignedPayloadVerifier.getInt(credential, "age");
        this.minor = SignedPayloadVerifier.getBoolean(credential, "minor");
        this.credentialIssuedAt = SignedPayloadVerifier.getLong(credential, "issuedAt");
        this.credentialExpiresAt = SignedPayloadVerifier.getLong(credential, "expiresAt");
        this.verified = true;
    }

    private void applyRules(String bytes, String signature, JsonObject rules, boolean enforceReplay) {
        if (enforceReplay && !canAcceptRules(rules)) {
            throw new SecurityException("检测到旧规则回放");
        }
        this.rulesBytes = bytes == null ? "" : bytes;
        this.rulesSignature = signature == null ? "" : signature;
        this.lastRulesVersion = SignedPayloadVerifier.getLong(rules, "rulesVersion");
        this.lastRulesIssuedAt = SignedPayloadVerifier.getLong(rules, "issuedAt");
        this.rulesExpiresAt = SignedPayloadVerifier.getLong(rules, "rulesExpiresAt");

        int[] defaultStart = getTimeCompat(rules, "defaultStartTime", "default_start_hour", "default_start_minute");
        int[] defaultEnd = getTimeCompat(rules, "defaultEndTime", "default_end_hour", "default_end_minute");
        int defStartHour = defaultStart[0];
        int defStartMinute = defaultStart[1];
        int defEndHour = defaultEnd[0];
        int defEndMinute = defaultEnd[1];
        PlayTimeChecker.updateDefaultTime(defStartHour, defStartMinute, defEndHour, defEndMinute);

        long defMaxSeconds = getDefaultMaxSeconds(rules);
        PlayTimeChecker.updateDefaultMaxSeconds(defMaxSeconds);

        java.util.List<Integer> weekdays = getDefaultWeekdays(rules);
        PlayTimeChecker.updateDefaultWeekdays(weekdays);
        PlayTimeChecker.updateServerPlayedTodaySeconds(SignedPayloadVerifier.getLong(rules, "played_today_seconds"));

        Map<String, int[]> gameDays = new HashMap<>();
        JsonArray days = rules.has("days") && rules.get("days").isJsonArray() ? rules.getAsJsonArray("days") : new JsonArray();
        for (JsonElement el : days) {
            JsonObject gd = el.getAsJsonObject();
            gameDays.put(SignedPayloadVerifier.getString(gd, "date"), new int[]{
                    SignedPayloadVerifier.getInt(gd, "playable"),
                    SignedPayloadVerifier.getInt(gd, "start_hour"),
                    SignedPayloadVerifier.getInt(gd, "start_minute"),
                    SignedPayloadVerifier.getInt(gd, "end_hour"),
                    SignedPayloadVerifier.getInt(gd, "end_minute"),
                    SignedPayloadVerifier.getInt(gd, "max_minutes"),
                    SignedPayloadVerifier.getInt(gd, "is_workday_override")
            });
        }
        PlayTimeChecker.updateGameDays(gameDays);
    }

    private static int[] getTimeCompat(JsonObject rules, String camelKey, String hourKey, String minuteKey) {
        String time = SignedPayloadVerifier.getString(rules, camelKey);
        if (!time.isBlank()) {
            String[] parts = time.split(":");
            if (parts.length >= 2) {
                try {
                    int hour = clamp(Integer.parseInt(parts[0]), 0, 23);
                    int minute = clamp(Integer.parseInt(parts[1]), 0, 59);
                    return new int[]{hour, minute};
                } catch (Exception ignored) {
                }
            }
        }
        return new int[]{
                SignedPayloadVerifier.getInt(rules, hourKey),
                SignedPayloadVerifier.getInt(rules, minuteKey)
        };
    }

    private static long getDefaultMaxSeconds(JsonObject rules) {
        if (rules != null && rules.has("defaultMaxSeconds") && !rules.get("defaultMaxSeconds").isJsonNull()) {
            return Math.max(0L, SignedPayloadVerifier.getLong(rules, "defaultMaxSeconds"));
        }
        return Math.max(0L, SignedPayloadVerifier.getInt(rules, "default_max_minutes")) * 60L;
    }

    private static java.util.List<Integer> getDefaultWeekdays(JsonObject rules) {
        java.util.List<Integer> weekdays = new java.util.ArrayList<>();
        JsonElement enabled = rules != null && rules.has("defaultWeekdayEnabled")
                ? rules.get("defaultWeekdayEnabled") : JsonNull.INSTANCE;
        if (enabled.isJsonArray()) {
            addWeekdaysFromArray(weekdays, enabled.getAsJsonArray());
            return weekdays;
        }
        if (enabled.isJsonObject()) {
            JsonObject obj = enabled.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                try {
                    if (!entry.getValue().isJsonNull() && entry.getValue().getAsBoolean()) {
                        int day = parseWeekdayKey(entry.getKey());
                        if (day >= 1 && day <= 7) weekdays.add(day);
                    }
                } catch (Exception ignored) {
                }
            }
            return weekdays;
        }

        JsonArray weekdayArray = rules != null && rules.has("default_playable_weekdays") && rules.get("default_playable_weekdays").isJsonArray()
                ? rules.getAsJsonArray("default_playable_weekdays") : new JsonArray();
        addWeekdaysFromArray(weekdays, weekdayArray);
        return weekdays;
    }

    private static void addWeekdaysFromArray(java.util.List<Integer> weekdays, JsonArray array) {
        for (JsonElement el : array) {
            try {
                weekdays.add(el.getAsInt());
            } catch (Exception ignored) {
            }
        }
    }

    private static int parseWeekdayKey(String key) {
        try {
            return Integer.parseInt(key);
        } catch (Exception ignored) {
        }
        return switch (String.valueOf(key).toLowerCase(java.util.Locale.ROOT)) {
            case "monday", "mon" -> 1;
            case "tuesday", "tue" -> 2;
            case "wednesday", "wed" -> 3;
            case "thursday", "thu" -> 4;
            case "friday", "fri" -> 5;
            case "saturday", "sat" -> 6;
            case "sunday", "sun" -> 7;
            default -> 0;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateTrustedTime(long trustedTime) {
        this.lastTrustedTime = Math.max(this.lastTrustedTime, trustedTime);
        this.lastTrustedLocalTime = System.currentTimeMillis();
        TrustedClock.restore(this.lastTrustedTime);
    }

    private void save() {
        SaveData data = new SaveData();
        data.verified = verified;
        data.credentialBytes = credentialBytes;
        data.credentialSignature = credentialSignature;
        data.rulesBytes = rulesBytes;
        data.rulesSignature = rulesSignature;
        data.minecraftUuid = minecraftUuid;
        data.minecraftName = minecraftName;
        data.sub = sub;
        data.userDigest = userDigest;
        data.idDigest = idDigest;
        data.age = age;
        data.minor = minor;
        data.credentialIssuedAt = credentialIssuedAt;
        data.credentialExpiresAt = credentialExpiresAt;
        data.lastTrustedTime = lastTrustedTime;
        data.lastTrustedLocalTime = lastTrustedLocalTime;
        data.lastRulesVersion = lastRulesVersion;
        data.lastRulesIssuedAt = lastRulesIssuedAt;
        data.rulesExpiresAt = rulesExpiresAt;

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(getDataPath().toFile()), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            AntiAddictionMod.LOGGER.error("[防沉迷] 保存认证状态失败: {}", e.getMessage());
        }
    }

    public void clearVerification() {
        resetMemory();
        StorageKeyManager.INSTANCE.clearAll();
        File file = getDataPath().toFile();
        if (file.exists() && !file.delete()) {
            AntiAddictionMod.LOGGER.warn("[防沉迷] 无法删除本地认证状态文件: {}", file);
        }
    }

    private void resetMemory() {
        credentialBytes = "";
        credentialSignature = "";
        rulesBytes = "";
        rulesSignature = "";
        minecraftUuid = "";
        minecraftName = "";
        sessionDisplayName = "";
        sub = "";
        userDigest = "";
        idDigest = "";
        age = -1;
        minor = false;
        verified = false;
        credentialIssuedAt = 0L;
        credentialExpiresAt = 0L;
        lastTrustedTime = 0L;
        lastTrustedLocalTime = 0L;
        lastRulesVersion = 0L;
        lastRulesIssuedAt = 0L;
        rulesExpiresAt = 0L;
    }

    public boolean hasUsableCredentialForCurrentUser() {
        if (!verified || credentialBytes.isBlank() || credentialSignature.isBlank()) return false;
        if (TrustedClock.isWallClockRollback(lastTrustedLocalTime)) {
            clearVerification();
            return false;
        }
        if (!TrustedClock.hasTrustedTime()) {
            clearVerification();
            return false;
        }
        if (!minecraftUuid.equals(MinecraftIdentity.uuid()) || !minecraftName.equals(MinecraftIdentity.name())) {
            clearVerification();
            return false;
        }
        if (TrustedClock.nowMillis() >= credentialExpiresAt) {
            clearVerification();
            return false;
        }
        return true;
    }

    public boolean hasTrustedRules() {
        return !rulesBytes.isBlank()
                && !rulesSignature.isBlank()
                && TrustedClock.hasTrustedTime()
                && !TrustedClock.isWallClockRollback(lastTrustedLocalTime)
                && TrustedClock.nowMillis() < rulesExpiresAt;
    }

    public boolean isVerified() { return hasUsableCredentialForCurrentUser(); }
    public boolean isMinor() { return minor; }
    public String getUserName() { return sessionDisplayName.isBlank() ? "已认证用户" : sessionDisplayName; }
    public int getAge() { return age; }
    public String getCredentialBytes() { return credentialBytes; }
    public String getCredentialSignature() { return credentialSignature; }
    public long getLastRulesVersion() { return lastRulesVersion; }
    public long getLastRulesIssuedAt() { return lastRulesIssuedAt; }

    private static String normalizeDisplayName(String value) {
        return value == null ? "" : value.trim();
    }

    private static class SaveData {
        boolean verified;
        String credentialBytes;
        String credentialSignature;
        String rulesBytes;
        String rulesSignature;
        String minecraftUuid;
        String minecraftName;
        String sub;
        String userDigest;
        String idDigest;
        int age;
        boolean minor;
        long credentialIssuedAt;
        long credentialExpiresAt;
        long lastTrustedTime;
        long lastTrustedLocalTime;
        long lastRulesVersion;
        long lastRulesIssuedAt;
        long rulesExpiresAt;
    }
}
