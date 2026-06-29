package com.antiaddiction.security;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MinecraftIdentity {

    private MinecraftIdentity() {}

    public static String name() {
        Object user = Minecraft.getInstance().getUser();
        Object value = invoke(user, "getName");
        if (value == null) value = invoke(user, "getUsername");
        return value == null ? "unknown" : String.valueOf(value);
    }

    public static String uuid() {
        Object user = Minecraft.getInstance().getUser();
        Object value = invoke(user, "getProfileId");
        if (value == null) value = invoke(user, "getUuid");
        if (value == null) value = invoke(user, "getUuidOrNull");
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
