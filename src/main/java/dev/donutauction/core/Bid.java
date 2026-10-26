package dev.donutauction.core;

public final class Bid {
    public int seq;
    public String bidder;
    public long amount;
    public long at;
    public boolean accepted = true;
    public String rejectReason;
    /** The chat line (or "manual") this bid came from, for auditing. */
    public String raw;
}
