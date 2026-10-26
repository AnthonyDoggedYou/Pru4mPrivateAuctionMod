package dev.donutauction.core;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ChatParser {
    public static final class ParsedBid {
        public final String user;
        /** 0 = no auction id given. */
        public final int auctionId;
        public final String amountText;
        /** Parsed amount, or -1 when the text was not a valid amount. */
        public final long amount;

        ParsedBid(String user, int auctionId, String amountText, long amount) {
            this.user = user;
            this.auctionId = auctionId;
            this.amountText = amountText;
            this.amount = amount;
        }
    }

    public static final class ParsedPayment {
        public final String user;
        public final String amountText;
        public final long amount;

        ParsedPayment(String user, String amountText, long amount) {
            this.user = user;
            this.amountText = amountText;
            this.amount = amount;
        }
    }

    private static final String SEPS = "\u00bb:>\u203a|\u2192\u27a4\u279c\u226b\u27e9\u2502\u2503";
    private static final Pattern SMART_BODY = Pattern.compile(
            "(?i)^[\\s" + SEPS + "]*!?bid\\s+(?:#(\\d{1,6})\\s+)?\\$?([0-9][0-9.,]*[kKmMbBtT]?)\\s*$");
    private static final Pattern NAME_TOKEN = Pattern.compile("\\.?[A-Za-z0-9_]{3,16}");

    private final boolean smart;
    private final List<Pattern> bidPatterns = new ArrayList<>();
    private final List<Pattern> paymentPatterns = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    public ChatParser(Settings s) {
        this.smart = s.smartParsing;
        compile(s.bidPatterns, bidPatterns);
        compile(s.paymentPatterns, paymentPatterns);
    }

    private void compile(List<String> src, List<Pattern> out) {
        for (String p : src) {
            try {
                out.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                errors.add("Bad regex skipped: " + p + " (" + e.getDescription() + ")");
            }
        }
    }

    /** Strips formatting codes, non-breaking spaces and repeated whitespace. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\u00a0', ' ').replaceAll("\u00a7.", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    public ParsedBid parseBid(String line) {
        String s = normalize(line);
        for (Pattern p : bidPatterns) {
            Matcher m = p.matcher(s);
            if (!m.find()) continue;
            String user = group(m, "user");
            String amountText = group(m, "amount");
            if (user == null || amountText == null) continue;
            String idText = group(m, "id");
            int id = 0;
            if (idText != null) {
                try {
                    id = Integer.parseInt(idText);
                } catch (NumberFormatException ignored) {
                    id = 0;
                }
            }
            OptionalLong amt = Money.parse(clean(amountText));
            return new ParsedBid(user, id, amountText, amt.isPresent() ? amt.getAsLong() : -1);
        }
        return smart ? parseBidSmart(s) : null;
    }

    /**
     * Looser reader for when the server's chat layout is not the one the strict patterns expect.
     * The sender is whatever name sits just before the FIRST separator character, and the message that
     * follows must BEGIN with "bid <amount>". Because only the first separator counts, a player typing
     * "Bob > bid 100m" shows up as "Mallory > Bob > bid 100m" and is read as Mallory (body "Bob > bid..."
     * does not start with bid), so nobody can bid in someone else's name.
     */
    private ParsedBid parseBidSmart(String line) {
        String s = line.replaceAll("\\[[^\\]]*\\]\\s*", "").trim();
        int idx = -1;
        for (int i = 0; i < s.length(); i++) {
            if (SEPS.indexOf(s.charAt(i)) >= 0) {
                idx = i;
                break;
            }
        }
        String head;
        String body;
        if (idx >= 0) {
            head = s.substring(0, idx);
            body = s.substring(idx + 1);
        } else {
            int sp = s.indexOf(' ');
            if (sp < 0) return null;
            head = s.substring(0, sp);
            body = s.substring(sp + 1);
        }
        head = head.replace("<", " ").replace(">", " ").trim();
        if (head.isEmpty()) return null;
        String[] toks = head.split("\\s+");
        String user = toks[toks.length - 1].replaceAll("^[^.A-Za-z0-9_]+|[^A-Za-z0-9_]+$", "");
        if (!NAME_TOKEN.matcher(user).matches() || user.equalsIgnoreCase("bid")) return null;
        Matcher m = SMART_BODY.matcher(body.trim());
        if (!m.find()) return null;
        int id = 0;
        if (m.group(1) != null) {
            try {
                id = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                id = 0;
            }
        }
        OptionalLong amt = Money.parse(clean(m.group(2)));
        return new ParsedBid(user, id, m.group(2), amt.isPresent() ? amt.getAsLong() : -1);
    }

    public ParsedPayment parsePayment(String line) {
        String s = normalize(line);
        for (Pattern p : paymentPatterns) {
            Matcher m = p.matcher(s);
            if (!m.find()) continue;
            String user = group(m, "user");
            String amountText = group(m, "amount");
            if (user == null || amountText == null) continue;
            OptionalLong amt = Money.parse(clean(amountText));
            return new ParsedPayment(user, amountText, amt.isPresent() ? amt.getAsLong() : -1);
        }
        return null;
    }

    private static String clean(String amountText) {
        return amountText.replaceAll("[.,]+$", "");
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
