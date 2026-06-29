package com.antiaddiction.time;

import com.antiaddiction.data.PlayerDataManager;
import com.antiaddiction.security.TrustedClock;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class PlayTimeChecker {

    private static volatile Map<String, int[]> GAME_DAYS = new HashMap<>();
    private static volatile int DEFAULT_START_HOUR = 20;
    private static volatile int DEFAULT_START_MINUTE = 0;
    private static volatile int DEFAULT_END_HOUR = 21;
    private static volatile int DEFAULT_END_MINUTE = 0;
    private static volatile Set<Integer> DEFAULT_PLAYABLE_WEEKDAYS = new HashSet<>(Arrays.asList(5, 6, 7));
    private static volatile long DEFAULT_MAX_SECONDS = 0L;
    private static long sessionStartMs = 0;
    private static LocalDate sessionDate = null;
    private static volatile long SERVER_PLAYED_TODAY_SECONDS = 0L;
    private static volatile LocalDate SERVER_PLAYED_DATE = null;

    private static LocalDateTime getNow() {
        return TrustedClock.nowShanghai();
    }

    public static boolean isPlayAllowed() {
        if (!PlayerDataManager.getInstance().hasTrustedRules()) return false;

        LocalDateTime now = getNow();
        String today = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        int[] gd = GAME_DAYS.get(today);

        if (gd != null) {
            if (gd[0] == 0) return false;
            if (!isWithinWindow(now, gd[1], gd[2], gd[3], gd[4])) return false;
            return gd[5] <= 0 || getPlayedMinutes() < gd[5];
        }

        if (!isDefaultPlayableWeekday(now.toLocalDate())) return false;
        if (!isWithinWindow(now, DEFAULT_START_HOUR, DEFAULT_START_MINUTE, DEFAULT_END_HOUR, DEFAULT_END_MINUTE)) return false;
        return DEFAULT_MAX_SECONDS <= 0 || getPlayedSeconds() < DEFAULT_MAX_SECONDS;
    }

    public static String getNextAllowedTime() {
        if (!PlayerDataManager.getInstance().hasTrustedRules()) return "需要同步可信规则";

        LocalDateTime now = getNow();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM月dd日（EEE）HH:mm", Locale.CHINESE);

        for (int i = 0; i <= 90; i++) {
            LocalDate day = now.toLocalDate().plusDays(i);
            String dateStr = day.format(DateTimeFormatter.ISO_LOCAL_DATE);
            int[] gd = GAME_DAYS.get(dateStr);

            if (gd != null && gd[0] == 1) {
                LocalDateTime candidate = day.atTime(gd[1], gd[2]);
                if (candidate.isAfter(now)) return candidate.format(fmt);
            } else if (gd == null) {
                if (isDefaultPlayableWeekday(day)) {
                    LocalDateTime candidate = day.atTime(DEFAULT_START_HOUR, DEFAULT_START_MINUTE);
                    if (candidate.isAfter(now)) return candidate.format(fmt);
                }
            }
        }
        return "暂无可玩日";
    }

    public static long getRemainingSeconds() {
        if (!isPlayAllowed()) return 0;
        return getRemainingSecondsForCountdown();
    }

    public static long getRemainingSecondsForCountdown() {
        return getCurrentStatus().remainingSeconds;
    }

    public static void updateGameDays(Map<String, int[]> data) {
        GAME_DAYS = new HashMap<>(data);
    }

    public static void updateDefaultHours(int startHour, int endHour) {
        updateDefaultTime(startHour, 0, endHour, 0);
    }

    public static void updateDefaultTime(int startHour, int startMinute, int endHour, int endMinute) {
        DEFAULT_START_HOUR = startHour;
        DEFAULT_START_MINUTE = startMinute;
        DEFAULT_END_HOUR = endHour;
        DEFAULT_END_MINUTE = endMinute;
    }

    public static void updateDefaultWeekdays(Collection<Integer> weekdays) {
        Set<Integer> clean = new HashSet<>();
        if (weekdays != null) {
            for (Integer day : weekdays) {
                if (day != null && day >= 1 && day <= 7) clean.add(day);
            }
        }
        DEFAULT_PLAYABLE_WEEKDAYS = clean.isEmpty() ? new HashSet<>(Arrays.asList(5, 6, 7)) : clean;
    }

    public static void updateDefaultMaxMinutes(int minutes) {
        updateDefaultMaxSeconds(Math.max(0L, minutes) * 60L);
    }

    public static void updateDefaultMaxSeconds(long seconds) {
        DEFAULT_MAX_SECONDS = Math.max(0L, seconds);
    }

    public static boolean isDefaultPlayableWeekday(LocalDate day) {
        return day != null && DEFAULT_PLAYABLE_WEEKDAYS.contains(day.getDayOfWeek().getValue());
    }

    public static PlayTimeStatus getCurrentStatus() {
        boolean trusted = PlayerDataManager.getInstance().hasTrustedRules();
        long played = getPlayedSeconds();
        if (!trusted) {
            return new PlayTimeStatus(false, false, false, "--:--", "需要同步可信规则", 0, 0, played, 0);
        }

        LocalDateTime now = getNow();
        String today = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        int[] gd = GAME_DAYS.get(today);
        boolean hasOverride = gd != null;
        boolean playableDate = hasOverride ? gd[0] == 1 : isDefaultPlayableWeekday(now.toLocalDate());
        int startHour = hasOverride ? gd[1] : DEFAULT_START_HOUR;
        int startMinute = hasOverride ? gd[2] : DEFAULT_START_MINUTE;
        int endHour = hasOverride ? gd[3] : DEFAULT_END_HOUR;
        int endMinute = hasOverride ? gd[4] : DEFAULT_END_MINUTE;
        long maxSeconds = hasOverride ? gd[5] * 60L : DEFAULT_MAX_SECONDS;
        int maxMinutes = maxSeconds > 0
                ? (int) Math.min(Integer.MAX_VALUE, (maxSeconds + 59L) / 60L)
                : 0;

        boolean inWindow = playableDate && isWithinWindow(now, startHour, startMinute, endHour, endMinute);
        LocalDateTime end = now.toLocalDate().atTime(endHour, endMinute);
        long windowRemaining = Math.max(0, Duration.between(now, end).getSeconds());
        long remaining = inWindow ? windowRemaining : 0;
        boolean limited = maxSeconds > 0;
        if (limited && inWindow) {
            remaining = Math.min(windowRemaining, Math.max(0, maxSeconds - played));
        }

        String current = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        String window = String.format("%02d:%02d-%02d:%02d", startHour, startMinute, endHour, endMinute);
        return new PlayTimeStatus(true, inWindow && remaining > 0, limited, current, window, remaining, windowRemaining, played, maxMinutes);
    }

    public static int getDefaultStartHour() { return DEFAULT_START_HOUR; }
    public static int getDefaultStartMinute() { return DEFAULT_START_MINUTE; }
    public static int getDefaultEndHour() { return DEFAULT_END_HOUR; }
    public static int getDefaultEndMinute() { return DEFAULT_END_MINUTE; }
    public static int getDefaultMaxMinutes() {
        return DEFAULT_MAX_SECONDS > 0
                ? (int) Math.min(Integer.MAX_VALUE, (DEFAULT_MAX_SECONDS + 59L) / 60L)
                : 0;
    }

    public static Map<String, int[]> getGameDays() {
        return new HashMap<>(GAME_DAYS);
    }

    public static void markSessionStart() {
        LocalDate today = getNow().toLocalDate();
        if (sessionStartMs > 0 && today.equals(sessionDate)) return;
        sessionStartMs = System.currentTimeMillis();
        sessionDate = today;
    }

    public static synchronized void updateServerPlayedTodaySeconds(long seconds) {
        LocalDate today = getNow().toLocalDate();
        long current = getPlayedSeconds();
        SERVER_PLAYED_DATE = today;
        SERVER_PLAYED_TODAY_SECONDS = Math.max(Math.max(0L, seconds), current);
        if (sessionStartMs > 0 && today.equals(sessionDate)) {
            sessionStartMs = System.currentTimeMillis();
        }
    }

    public static long getPlayedMinutes() {
        return getPlayedSeconds() / 60;
    }

    public static long getPlayedSeconds() {
        LocalDate today = getNow().toLocalDate();
        long serverBase = today.equals(SERVER_PLAYED_DATE) ? SERVER_PLAYED_TODAY_SECONDS : 0L;
        if (sessionStartMs == 0 || sessionDate == null || !today.equals(sessionDate)) return serverBase;
        long elapsed = Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 1000);
        return serverBase + elapsed;
    }

    private static boolean isWithinWindow(LocalDateTime now, int startHour, int startMinute, int endHour, int endMinute) {
        int minuteOfDay = now.getHour() * 60 + now.getMinute();
        int start = startHour * 60 + startMinute;
        int end = endHour * 60 + endMinute;
        return minuteOfDay >= start && minuteOfDay < end;
    }
}
