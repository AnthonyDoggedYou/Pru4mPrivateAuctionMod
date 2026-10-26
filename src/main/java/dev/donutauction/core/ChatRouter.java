package dev.donutauction.core;

import dev.donutauction.core.AuctionEngine.Result;

/**
 * Turns incoming chat lines into engine calls. Pure Java so the whole pipeline can be tested
 * without Minecraft. It only ever READS chat; it never sends anything.
 */
public final class ChatRouter {
    public interface Notifier {
        void info(String message);

        void good(String message);

        void warn(String message);
    }

    private static final long DEDUPE_WINDOW_MS = 250;

    private final AuctionEngine engine;
    private final Settings cfg;
    private final ChatParser parser;
    private final Notifier out;
    private String selfName = "";
    private String lastLine = "";
    private long lastLineAt;

    public ChatRouter(AuctionEngine engine, Settings cfg, ChatParser parser, Notifier out) {
        this.engine = engine;
        this.cfg = cfg;
        this.parser = parser;
        this.out = out;
    }

    public void setSelfName(String name) {
        this.selfName = name == null ? "" : name;
    }

    /**
     * @param viaGameChannel true if this arrived as a server/system message rather than signed player chat
     * @return true if the line was recognised as a bid or payment
     */
    public boolean handle(String rawLine, boolean viaGameChannel, long nowMs) {
        String line = ChatParser.normalize(rawLine);
        if (line.isEmpty()) return false;
        // The same line delivered twice in the same instant must not be processed twice.
        // Only lines that were actually acted on are remembered.
        boolean duplicate = line.equals(lastLine) && nowMs - lastLineAt < DEDUPE_WINDOW_MS;

        if (viaGameChannel || !cfg.paymentFromGameChannelOnly) {
            ChatParser.ParsedPayment pay = parser.parsePayment(line);
            if (pay != null) {
                if (pay.user.equalsIgnoreCase(selfName) || duplicate) return false;
                remember(line, nowMs);
                if (pay.amount <= 0) {
                    out.warn("Saw a payment line from " + pay.user + " but could not read the amount '" + pay.amountText + "'.");
                    return true;
                }
                Result r = engine.onPaymentDetected(pay.user, pay.amount, line);
                if (r.ok) out.warn(r.message); else out.info(r.message);
                return true;
            }
        }

        if (!engine.hasRunning()) return false;
        ChatParser.ParsedBid bid = parser.parseBid(line);
        if (bid == null) return false;
        if (bid.user.equalsIgnoreCase(selfName) || duplicate) return false;
        remember(line, nowMs);
        if (bid.amount <= 0) {
            if (cfg.showRejectedBids) {
                out.info("Ignored bid from " + bid.user + ": could not read amount '" + bid.amountText + "'.");
            }
            return true;
        }
        Result r = engine.placeBid(bid.auctionId, bid.user, bid.amount, line);
        if (r.ok) {
            out.good(r.message);
        } else if (cfg.showRejectedBids) {
            out.info("Rejected bid from " + bid.user + " (" + Money.format(bid.amount) + "): " + r.message);
        }
        return true;
    }

    private void remember(String line, long nowMs) {
        lastLine = line;
        lastLineAt = nowMs;
    }
}
