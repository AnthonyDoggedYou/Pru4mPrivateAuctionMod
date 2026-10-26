package dev.donutauction.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Auction {
    public enum Phase { RUNNING, ENDED, NO_SALE, CANCELLED }

    public enum PayStatus { NONE, AWAITING, PARTIAL, DETECTED, CONFIRMED }

    public int id;
    public ItemInfo item;
    public String seller;
    public long startingBid;
    public long minIncrement = 1;
    public long createdAt;
    public long durationMs;
    public long endsAt;
    public long endedAt;
    public int extensions;
    public Phase phase = Phase.RUNNING;

    /** If true, a /pay received during the auction counts as a bid (your total payments for this auction). */
    public boolean payToBid;
    /** Pay-to-bid only: lower-case payer name -> amount already refunded to them. */
    public Map<String, Long> refunded = new HashMap<>();

    /** Accepted bids only, strictly increasing in amount. */
    public List<Bid> bids = new ArrayList<>();
    /** Rejected bid attempts, newest last, capped. */
    public List<Bid> rejected = new ArrayList<>();

    public String winner;
    public long finalBid;
    public String closeNote = "";
    public List<String> forfeitedWinners = new ArrayList<>();

    public PayStatus payStatus = PayStatus.NONE;
    public long detectedPaid;
    public long confirmedPaid;
    public long paymentConfirmedAt;
    public boolean delivered;
    public long deliveredAt;
    public long completedAt;

    /** Runtime-only, not saved. */
    public transient int lastReminder = Integer.MAX_VALUE;

    public Bid highest() {
        return bids.isEmpty() ? null : bids.get(bids.size() - 1);
    }

    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    public boolean isCompleted() {
        return phase == Phase.ENDED && payStatus == PayStatus.CONFIRMED && delivered;
    }

    public long remainingMs(long now) {
        return Math.max(0, endsAt - now);
    }

    public long currentPrice() {
        Bid h = highest();
        return h == null ? startingBid : h.amount;
    }
}
