package com.antiaddiction.security;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class SignedPayloadVerifier {

    public static final String CLIENT_VERSION = "1.0.0";
    public static final String DEV_PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAUyYyGcOyGKtDo7mCtQW0R9Y6qZWPcoxWnfRNbR53pQ8=";
    public static final String PROD_PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAnBJ1R4ONRVIlD8GYbMYCsU/d9y3cwz/Dfctm3N4jbX8=";

    private SignedPayloadVerifier() {}

    public static JsonObject verify(String payloadBytesB64, String signatureB64, boolean development,
                                    String developmentPublicKeyOverride) throws Exception {
        byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadBytesB64);
        byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureB64);
        String keyB64 = development && developmentPublicKeyOverride != null && !developmentPublicKeyOverride.isBlank()
                ? developmentPublicKeyOverride.trim()
                : (development ? DEV_PUBLIC_KEY_B64 : PROD_PUBLIC_KEY_B64);

        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(keyB64)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payloadBytes);
        if (!verifier.verify(signatureBytes)) {
            throw new SecurityException("签名验证失败");
        }

        JsonObject payload = JsonParser.parseString(new String(payloadBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        String expectedIssuer = development ? "antiaddiction-backend-dev" : "antiaddiction-backend";
        String expectedAudience = development ? "antiaddiction-mod-dev" : "antiaddiction-mod";
        if (!expectedIssuer.equals(getString(payload, "iss")) || !expectedAudience.equals(getString(payload, "aud"))) {
            throw new SecurityException("签名 payload 的 issuer/audience 不匹配");
        }
        return payload;
    }

    public static int compareVersions(String a, String b) {
        String[] left = String.valueOf(a).split("\\.");
        String[] right = String.valueOf(b).split("\\.");
        int len = Math.max(left.length, right.length);
        for (int i = 0; i < len; i++) {
            int lv = i < left.length ? parsePart(left[i]) : 0;
            int rv = i < right.length ? parsePart(right[i]) : 0;
            if (lv != rv) return lv - rv;
        }
        return 0;
    }

    public static String getString(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    public static long getLong(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
    }

    public static int getInt(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
    }

    public static boolean getBoolean(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
    }

    private static int parsePart(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
