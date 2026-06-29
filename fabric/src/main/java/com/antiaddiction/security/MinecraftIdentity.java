package com.antiaddiction.security;

import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MinecraftIdentity {

    private MinecraftIdentity() {}

    public static String name() {
        Object session = MinecraftClient.getInstance().getSession();
        Object value = invoke(session, "getUsername");
        if (value == null) value = invoke(session, "getName");
        return value == null ? "unknown" : String.valueOf(value);
    }

    public static String uuid() {
        Object session = MinecraftClient.getInstance().getSession();
        Object value = invoke(session, "getUuidOrNull");
        if (value == null) value = invoke(session, "getUuid");
        if (value == null) value = invoke(session, "getProfileId");
        if (value instanceof UUID uuid) return uuid.toString();
        if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        return UUID.nameUUIDFromBytes(("offline:" + name()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }
}
