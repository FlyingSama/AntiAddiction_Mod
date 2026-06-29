package com.antiaddiction.time;

public final class PlayTimeStatus {
    public final boolean trustedRules;
    public final boolean allowed;
    public final boolean limited;
    public final String currentTimeText;
    public final String windowText;
    public final long remainingSeconds;
    public final long windowRemainingSeconds;
    public final long playedTodaySeconds;
    public final int maxMinutes;

    public PlayTimeStatus(boolean trustedRules, boolean allowed, boolean limited,
                          String currentTimeText, String windowText,
                          long remainingSeconds, long windowRemainingSeconds,
                          long playedTodaySeconds, int maxMinutes) {
        this.trustedRules = trustedRules;
        this.allowed = allowed;
        this.limited = limited;
        this.currentTimeText = currentTimeText;
        this.windowText = windowText;
        this.remainingSeconds = remainingSeconds;
        this.windowRemainingSeconds = windowRemainingSeconds;
        this.playedTodaySeconds = playedTodaySeconds;
        this.maxMinutes = maxMinutes;
    }
}
