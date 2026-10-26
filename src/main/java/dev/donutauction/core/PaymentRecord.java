package dev.donutauction.core;

public final class PaymentRecord {
    public enum PayState {
        /** Seen in chat. NOT verified. */
        DETECTED,
        /** The seller explicitly verified it. */
        CONFIRMED,
        /** Seen in chat but no ended auction was waiting on this player. Not verified. */
        UNMATCHED,
        /** The seller dismissed it as invalid. */
        REJECTED
    }

    public int id;
    public String payer;
    public long amount;
    public long detectedAt;
    public String rawLine;
    /** 0 means not linked to any auction. */
    public int auctionId;
    public PayState state = PayState.DETECTED;
    /** CHAT (parsed from a chat line) or MANUAL (typed in by the seller). */
    public String source = "CHAT";
    public long confirmedAt;
    public String note = "";
}
