package dev.donutauction.core;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats DonutSMP-style money amounts such as 500, 2.5k, 1.2m, 3b. */
public final class Money {
    private static final Pattern PLAIN = Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmbt]?)$");
    private static final Pattern GROUPED = Pattern.compile("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?[kmbt]?$");

    private Money() {}

    /**
     * Returns the whole-number amount, or empty if the text is not a clean positive amount.
     * Commas are accepted ONLY as strict thousands separators ("1,500"), because "1,5m"
     * would otherwise silently become 15m, a ten-fold misread.
     */
    public static OptionalLong parse(String input) {
        if (input == null || input.length() > 40) return OptionalLong.empty();
        String s = input.trim().toLowerCase(Locale.ROOT).replace("$", "").replace(" ", "");
        if (s.isEmpty()) return OptionalLong.empty();
        if (s.indexOf(',') >= 0) {
            if (!GROUPED.matcher(s).matches()) return OptionalLong.empty();
            s = s.replace(",", "");
        }
        Matcher m = PLAIN.matcher(s);
        if (!m.matches()) return OptionalLong.empty();
        BigDecimal v = new BigDecimal(m.group(1)).multiply(unit(m.group(2)));
        if (v.signum() <= 0) return OptionalLong.empty();
        if (v.stripTrailingZeros().scale() > 0) return OptionalLong.empty(); // fractional units
        try {
            return OptionalLong.of(v.longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    private static BigDecimal unit(String suffix) {
        switch (suffix) {
            case "k": return BigDecimal.valueOf(1_000L);
            case "m": return BigDecimal.valueOf(1_000_000L);
            case "b": return BigDecimal.valueOf(1_000_000_000L);
            case "t": return BigDecimal.valueOf(1_000_000_000_000L);
            default: return BigDecimal.ONE;
        }
    }

    /** Short exact form (2.5m) when it is lossless within 3 decimals, otherwise grouped digits. */
    public static String format(long v) {
        if (v <= 0) return Long.toString(v);
        String[] suffix = {"t", "b", "m", "k"};
        long[] unit = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
        for (int i = 0; i < unit.length; i++) {
            if (v >= unit[i]) {
                BigDecimal q = BigDecimal.valueOf(v).divide(BigDecimal.valueOf(unit[i])).stripTrailingZeros();
                if (q.scale() <= 3) return q.toPlainString() + suffix[i];
                break;
            }
        }
        return grouped(v);
    }

    public static String grouped(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    /** "2.5m ($2,500,000)" or just "500" when both forms are identical. */
    public static String both(long v) {
        String s = format(v);
        String g = grouped(v);
        return s.equals(g) || s.equals(Long.toString(v)) ? "$" + g : s + " ($" + g + ")";
    }
}
