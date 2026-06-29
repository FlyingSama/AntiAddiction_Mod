package com.antiaddiction.security;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class TrustedClock {

    private static final long MAX_NTP_SKEW_MS = 5 * 60 * 1000L;
    private static final long MAX_ROLLBACK_MS = 5 * 60 * 1000L;
    private static final ZoneId CHINA_TZ = ZoneId.of("Asia/Shanghai");

    private static boolean trusted;
    private static boolean lastNtpAvailable;
    private static long trustedEpochAtSync;
    private static long nanoAtSync;

    private TrustedClock() {}

    public static synchronized void restore(long lastTrustedTime) {
        if (lastTrustedTime <= 0 || System.currentTimeMillis() + MAX_ROLLBACK_MS < lastTrustedTime) {
            trusted = false;
            return;
        }
        trusted = true;
        trustedEpochAtSync = lastTrustedTime;
        nanoAtSync = System.nanoTime();
    }

    public static synchronized void acceptSignedServerTime(long serverEpochMillis) throws Exception {
        long ntpMillis = fetchNtpTimeMillis();
        lastNtpAvailable = ntpMillis > 0;
        if (ntpMillis > 0 && Math.abs(ntpMillis - serverEpochMillis) > MAX_NTP_SKEW_MS) {
            throw new SecurityException("NTP 与后端签名时间偏差过大");
        }

        long currentTrusted = nowMillis();
        if (trusted && serverEpochMillis + MAX_ROLLBACK_MS < currentTrusted) {
            throw new SecurityException("检测到可信时间回拨");
        }
        trusted = true;
        trustedEpochAtSync = serverEpochMillis;
        nanoAtSync = System.nanoTime();
    }

    public static synchronized boolean hasTrustedTime() {
        return trusted;
    }

    public static synchronized boolean wasLastNtpAvailable() {
        return lastNtpAvailable;
    }

    public static synchronized long nowMillis() {
        if (!trusted) return System.currentTimeMillis();
        long elapsedMs = Math.max(0L, (System.nanoTime() - nanoAtSync) / 1_000_000L);
        return trustedEpochAtSync + elapsedMs;
    }

    public static LocalDateTime nowShanghai() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis()), CHINA_TZ);
    }

    public static boolean isWallClockRollback(long lastTrustedLocalTime) {
        return lastTrustedLocalTime > 0 && System.currentTimeMillis() + MAX_ROLLBACK_MS < lastTrustedLocalTime;
    }

    private static long fetchNtpTimeMillis() {
        String[] hosts = {"time.cloudflare.com", "pool.ntp.org"};
        for (String host : hosts) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1500);
                byte[] data = new byte[48];
                data[0] = 0x1B;
                InetAddress address = InetAddress.getByName(host);
                socket.send(new DatagramPacket(data, data.length, address, 123));
                DatagramPacket response = new DatagramPacket(data, data.length);
                socket.receive(response);
                long seconds = ((data[40] & 0xffL) << 24) | ((data[41] & 0xffL) << 16)
                        | ((data[42] & 0xffL) << 8) | (data[43] & 0xffL);
                long fraction = ((data[44] & 0xffL) << 24) | ((data[45] & 0xffL) << 16)
                        | ((data[46] & 0xffL) << 8) | (data[47] & 0xffL);
                return (seconds - 2_208_988_800L) * 1000L + (fraction * 1000L / 0x1_0000_0000L);
            } catch (Exception ignored) {
            }
        }
        return -1L;
    }
}
