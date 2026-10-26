package dev.donutauction.core;

import java.util.ArrayList;
import java.util.List;

/** Everything that is persisted to disk. */
public final class State {
    public int version = 1;
    public int nextAuctionId = 1;
    public int nextPaymentId = 1;
    public int nextBidSeq = 1;
    public List<Auction> auctions = new ArrayList<>();
    public List<PaymentRecord> payments = new ArrayList<>();
    public List<String> blocked = new ArrayList<>();
}
