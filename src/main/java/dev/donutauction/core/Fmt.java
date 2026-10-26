package dev.donutauction.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Fmt {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault());
    private static final Pattern DUR = Pattern.compile("^(\\d+)\\s*([smh]?)$");

    private Fmt() {}

    /** 4:32 or 1:02:03. */
    public static String duration(long ms) {
        long s = (Math.max(0, ms) + 999) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    public static String time(long epochMs) {
        return epochMs <= 0 ? "-" : TIME.format(Instant.ofEpochMilli(epochMs));
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return max <= 1 ? s.substring(0, Math.max(0, max)) : s.substring(0, max - 1) + "\u2026";
    }

    /** Accepts "90", "90s", "2m", "1h". Returns milliseconds, or empty if invalid. */
    public static OptionalLong parseDurationMs(String text) {
        if (text == null) return OptionalLong.empty();
        Matcher m = DUR.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return OptionalLong.empty();
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        long mult = switch (m.group(2)) {
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            default -> 1_000L;
        };
        if (n <= 0 || n > Long.MAX_VALUE / mult) return OptionalLong.empty();
        return OptionalLong.of(n * mult);
    }
}
