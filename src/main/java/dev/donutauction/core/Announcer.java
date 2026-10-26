package dev.donutauction.core;

/** Builds chat announcement text. The mod never sends these; the seller pastes and sends them. */
public final class Announcer {
    private static final int CHAT_LIMIT = 250;

    private Announcer() {}

    public static String start(Auction a) {
        String head = "[AUCTION #" + a.id + "] ";
        String tail = " | Start " + Money.format(a.startingBid) + " | Ends in " + Fmt.duration(a.endsAt - a.createdAt)
                + (a.payToBid ? " | To bid: /pay " + a.seller + " <amount> (your total counts, losers refunded)"
                : " | Bid in chat: bid #" + a.id + " <amount>");
        return head + a.item.shortDescription(CHAT_LIMIT - head.length() - tail.length()) + tail;
    }

    public static String update(Auction a, long now) {
        Bid h = a.highest();
        String top = h == null ? "No bids yet (start " + Money.format(a.startingBid) + ")"
                : "Top bid " + Money.format(h.amount) + " by " + h.bidder;
        return Fmt.truncate("[AUCTION #" + a.id + "] " + a.item.summary() + " | " + top + " | "
                + Fmt.duration(a.remainingMs(now)) + " left | "
                + (a.payToBid ? "bid with /pay " + a.seller + " <amount>" : "bid #" + a.id + " <amount>"), CHAT_LIMIT);
    }

    public static String result(Auction a) {
        switch (a.phase) {
            case ENDED:
                if (a.payToBid) {
                    return Fmt.truncate("[AUCTION #" + a.id + "] SOLD: " + a.item.summary() + " to " + a.winner + " for "
                            + Money.format(a.finalBid) + ". Other bidders will be refunded.", CHAT_LIMIT);
                }
                return Fmt.truncate("[AUCTION #" + a.id + "] SOLD: " + a.item.summary() + " to " + a.winner + " for "
                        + Money.format(a.finalBid) + ". Winner: /pay " + a.seller + " " + Money.format(a.finalBid)
                        + " to receive the item.", CHAT_LIMIT);
            case CANCELLED:
                return "[AUCTION #" + a.id + "] Cancelled.";
            case NO_SALE:
                return "[AUCTION #" + a.id + "] Ended with no sale.";
            default:
                return update(a, System.currentTimeMillis());
        }
    }
}
