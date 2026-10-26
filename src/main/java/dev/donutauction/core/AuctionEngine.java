package dev.donutauction.core;

import dev.donutauction.core.Auction.PayStatus;
import dev.donutauction.core.Auction.Phase;
import dev.donutauction.core.PaymentRecord.PayState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * All auction rules live here and nothing in this class touches Minecraft, so it is unit-testable.
 * Every public method is synchronized and runs one at a time, which is what makes simultaneous bids
 * fair: bids are decided strictly in the order the client received them, and on equal amounts the
 * first one received wins.
 */
public final class AuctionEngine {
    public interface Clock {
        long now();
    }

    public static final class Result {
        public final boolean ok;
        public final String code;
        public final String message;
        public final int auctionId;

        Result(boolean ok, String code, String message, int auctionId) {
            this.ok = ok;
            this.code = code;
            this.message = message;
            this.auctionId = auctionId;
        }

        static Result ok(String message, int id) {
            return new Result(true, "OK", message, id);
        }

        static Result fail(String code, String message, int id) {
            return new Result(false, code, message, id);
        }
    }

    public static final class Reminder {
        public final Auction auction;
        public final int secondsLeft;

        Reminder(Auction auction, int secondsLeft) {
            this.auction = auction;
            this.secondsLeft = secondsLeft;
        }
    }

    private static final Pattern NAME = Pattern.compile("^\\.?[A-Za-z0-9_]{3,16}$");

    private final State state;
    private final Settings cfg;
    private final Clock clock;
    private boolean dirty;

    public AuctionEngine(State state, Settings cfg, Clock clock) {
        this.state = state;
        this.cfg = cfg;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    public synchronized State state() {
        return state;
    }

    public synchronized boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    private void touch() {
        dirty = true;
    }

    public synchronized Auction byId(int id) {
        for (Auction a : state.auctions) if (a.id == id) return a;
        return null;
    }

    public synchronized List<Auction> running() {
        List<Auction> out = new ArrayList<>();
        for (Auction a : state.auctions) if (a.isRunning()) out.add(a);
        return out;
    }

    public synchronized boolean hasRunning() {
        for (Auction a : state.auctions) if (a.isRunning()) return true;
        return false;
    }

    /** Newest first. */
    public synchronized List<Auction> all() {
        List<Auction> out = new ArrayList<>(state.auctions);
        out.sort(Comparator.comparingInt((Auction a) -> a.id).reversed());
        return out;
    }

    /** Ended with a winner but not yet fully paid AND delivered. */
    public synchronized List<Auction> toSettle() {
        List<Auction> out = new ArrayList<>();
        for (Auction a : all()) {
            boolean unfinished = a.phase == Phase.ENDED && !a.isCompleted();
            if (unfinished || (a.phase != Phase.RUNNING && !refundsOwed(a).isEmpty())) out.add(a);
        }
        return out;
    }

    public synchronized List<Auction> completed() {
        List<Auction> out = new ArrayList<>();
        for (Auction a : all()) if (a.isCompleted() && refundsOwed(a).isEmpty()) out.add(a);
        return out;
    }

    /** Auctions that did not result in a sale: no bids, cancelled, or the winner defaulted with no fallback. */
    public synchronized List<Auction> unsold() {
        List<Auction> out = new ArrayList<>();
        for (Auction a : all()) {
            if ((a.phase == Phase.NO_SALE || a.phase == Phase.CANCELLED) && refundsOwed(a).isEmpty()) out.add(a);
        }
        return out;
    }

    /** Newest first. */
    public synchronized List<PaymentRecord> payments() {
        List<PaymentRecord> out = new ArrayList<>(state.payments);
        out.sort(Comparator.comparingInt((PaymentRecord p) -> p.id).reversed());
        return out;
    }

    /** Sum of payments the seller has explicitly confirmed. */
    public synchronized long confirmedIncome() {
        long total = 0;
        for (PaymentRecord p : state.payments) if (p.state == PayState.CONFIRMED) total = sat(total, p.amount);
        return total;
    }

    // ------------------------------------------------------------------ blocklist

    public synchronized boolean isBlocked(String name) {
        for (String b : state.blocked) if (b.equalsIgnoreCase(name)) return true;
        return false;
    }

    public synchronized boolean block(String name) {
        if (name == null || !NAME.matcher(name).matches() || isBlocked(name)) return false;
        state.blocked.add(name);
        touch();
        return true;
    }

    public synchronized boolean unblock(String name) {
        boolean removed = state.blocked.removeIf(b -> b.equalsIgnoreCase(name));
        if (removed) touch();
        return removed;
    }

    public synchronized List<String> blockedList() {
        return new ArrayList<>(state.blocked);
    }

    // ------------------------------------------------------------------ create / close

    public synchronized Result createAuction(ItemInfo item, String seller, long startingBid, long durationMs,
                                             long minIncrement) {
        return createAuction(item, seller, startingBid, durationMs, minIncrement, false);
    }

    public synchronized Result createAuction(ItemInfo item, String seller, long startingBid, long durationMs,
                                             long minIncrement, boolean payToBid) {
        if (item == null) return Result.fail("BAD_ITEM", "No item selected.", 0);
        if (seller == null || seller.isBlank()) return Result.fail("BAD_SELLER", "Unknown seller name.", 0);
        if (startingBid <= 0) return Result.fail("BAD_START", "Starting bid must be greater than 0.", 0);
        if (startingBid > cfg.maxBid) {
            return Result.fail("BAD_START", "Starting bid is above the safety limit (" + Money.format(cfg.maxBid) + ").", 0);
        }
        if (minIncrement < 1) return Result.fail("BAD_INCREMENT", "Minimum increment must be at least 1.", 0);
        if (durationMs < cfg.minDurationSec * 1000 || durationMs > cfg.maxDurationSec * 1000) {
            return Result.fail("BAD_DURATION", "Duration must be between " + cfg.minDurationSec + "s and "
                    + Fmt.duration(cfg.maxDurationSec * 1000) + ".", 0);
        }
        long now = clock.now();
        Auction a = new Auction();
        a.id = state.nextAuctionId++;
        a.item = item;
        a.seller = seller;
        a.startingBid = startingBid;
        a.minIncrement = minIncrement;
        a.createdAt = now;
        a.durationMs = durationMs;
        a.endsAt = now + durationMs;
        a.payToBid = payToBid;
        state.auctions.add(a);
        touch();
        return Result.ok("Auction #" + a.id + " started: " + item.summary() + ", ends in " + Fmt.duration(durationMs), a.id);
    }

    /** Closes every auction whose timer has run out. Returns the ones closed by this call. */
    public synchronized List<Auction> tick() {
        long now = clock.now();
        List<Auction> closed = null;
        for (Auction a : state.auctions) {
            if (a.isRunning() && now >= a.endsAt) {
                close(a, now);
                if (closed == null) closed = new ArrayList<>();
                closed.add(a);
            }
        }
        return closed == null ? List.of() : closed;
    }

    public synchronized Result endNow(int id) {
        Auction a = byId(id);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + id, id);
        if (!a.isRunning()) return Result.fail("NOT_RUNNING", "Auction #" + id + " is not running.", id);
        close(a, clock.now());
        return Result.ok("Auction #" + id + " ended early. " + describeOutcome(a), id);
    }

    public synchronized Result cancel(int id) {
        Auction a = byId(id);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + id, id);
        if (!a.isRunning()) return Result.fail("NOT_RUNNING", "Only running auctions can be cancelled.", id);
        a.phase = Phase.CANCELLED;
        a.endedAt = clock.now();
        a.closeNote = "Cancelled by seller" + (a.bids.isEmpty() ? "" : " with " + a.bids.size() + " bid(s) placed");
        touch();
        return Result.ok("Auction #" + id + " cancelled.", id);
    }

    private void close(Auction a, long now) {
        a.endedAt = now;
        Bid top = a.highest();
        if (top == null) {
            a.phase = Phase.NO_SALE;
            a.closeNote = "No bids";
            a.payStatus = PayStatus.NONE;
            for (PaymentRecord p : state.payments) {
                if (p.auctionId == 0 && p.state == PayState.UNMATCHED && p.detectedAt >= a.createdAt) {
                    a.closeNote = "No bids, but " + p.payer + " paid " + Money.format(p.amount)
                            + ". Open this auction to award it to them.";
                    break;
                }
            }
        } else {
            a.phase = Phase.ENDED;
            a.winner = top.bidder;
            a.finalBid = top.amount;
            a.payStatus = PayStatus.AWAITING;
            recompute(a, now);
        }
        touch();
    }

    private String describeOutcome(Auction a) {
        return a.phase == Phase.ENDED
                ? "Winner: " + a.winner + " at " + Money.format(a.finalBid) + "."
                : "No bids, no sale.";
    }

    public static String describeClosed(Auction a) {
        return a.phase == Phase.ENDED
                ? "Auction #" + a.id + " ended. Winner: " + a.winner + " with " + Money.format(a.finalBid)
                + ". Waiting for payment."
                : "Auction #" + a.id + " ended with no bids." + (a.closeNote.contains("paid") ? " " + a.closeNote.substring(a.closeNote.indexOf(',') + 2) : "");
    }

    // ------------------------------------------------------------------ bidding

    /**
     * @param auctionId 0 = not specified (allowed only when exactly one auction is running)
     */
    public synchronized Result placeBid(int auctionId, String bidder, long amount, String raw) {
        long now = clock.now();
        if (bidder == null || !NAME.matcher(bidder).matches()) {
            return Result.fail("BAD_NAME", "Ignored bid with invalid player name.", auctionId);
        }
        Auction a;
        if (auctionId > 0) {
            a = byId(auctionId);
            if (a == null) return Result.fail("NO_AUCTION", "No auction #" + auctionId + ".", auctionId);
        } else {
            List<Auction> running = running();
            if (running.isEmpty()) return Result.fail("NO_AUCTION", "No auction is running.", 0);
            if (running.size() > 1) {
                return Result.fail("AMBIGUOUS", bidder + " did not say which auction (" + running.size()
                        + " are running). They must use: bid #<id> <amount>", 0);
            }
            a = running.get(0);
        }
        if (a.payToBid && a.isRunning()) {
            return reject(a, bidder, amount, raw, now, "PAY_ONLY", "Auction #" + a.id
                    + " is pay-to-bid: " + bidder + " must send money with /pay " + a.seller + " instead.");
        }
        return tryBid(a, bidder, amount, raw, now);
    }

    private Result tryBid(Auction a, String bidder, long amount, String raw, long now) {
        if (!a.isRunning() || now >= a.endsAt) {
            return reject(a, bidder, amount, raw, now, "ENDED", "Auction #" + a.id + " has ended.");
        }
        if (bidder.equalsIgnoreCase(a.seller)) {
            return reject(a, bidder, amount, raw, now, "SELF_BID", "The seller cannot bid on their own auction.");
        }
        if (isBlocked(bidder)) {
            return reject(a, bidder, amount, raw, now, "BLOCKED", bidder + " is on your blocklist.");
        }
        if (amount <= 0) return reject(a, bidder, amount, raw, now, "BAD_AMOUNT", "Invalid amount.");
        if (amount > cfg.maxBid) {
            return reject(a, bidder, amount, raw, now, "TOO_HIGH",
                    "Bid is above the safety limit (" + Money.format(cfg.maxBid) + ").");
        }
        for (Bid b : a.bids) {
            if (b.bidder.equalsIgnoreCase(bidder) && b.amount == amount) {
                return reject(a, bidder, amount, raw, now, "DUPLICATE", "Duplicate of an existing bid; ignored.");
            }
        }
        Bid hi = a.highest();
        if (hi == null) {
            if (amount < a.startingBid) {
                return reject(a, bidder, amount, raw, now, "BELOW_START",
                        "Below the starting bid of " + Money.format(a.startingBid) + ".");
            }
        } else {
            if (amount <= hi.amount) {
                return reject(a, bidder, amount, raw, now, "NOT_HIGHER",
                        "Must beat the current high bid of " + Money.format(hi.amount) + " by " + hi.bidder + ".");
            }
            if (amount < hi.amount + a.minIncrement) {
                return reject(a, bidder, amount, raw, now, "BELOW_INCREMENT",
                        "Must raise by at least " + Money.format(a.minIncrement) + " (minimum "
                                + Money.format(hi.amount + a.minIncrement) + ").");
            }
        }
        Bid bid = new Bid();
        bid.seq = state.nextBidSeq++;
        bid.bidder = bidder;
        bid.amount = amount;
        bid.at = now;
        bid.raw = raw;
        a.bids.add(bid);

        String extended = "";
        if (cfg.antiSnipeSeconds > 0 && a.endsAt - now < cfg.antiSnipeSeconds * 1000) {
            a.endsAt = now + cfg.antiSnipeSeconds * 1000;
            a.extensions++;
            extended = " Timer extended to " + cfg.antiSnipeSeconds + "s.";
        }
        touch();
        return Result.ok("New high bid on #" + a.id + ": " + bidder + " bid " + Money.format(amount) + "." + extended, a.id);
    }

    private Result reject(Auction a, String bidder, long amount, String raw, long now, String code, String msg) {
        Bid b = new Bid();
        b.seq = state.nextBidSeq++;
        b.bidder = bidder;
        b.amount = amount;
        b.at = now;
        b.accepted = false;
        b.rejectReason = code;
        b.raw = raw;
        a.rejected.add(b);
        while (a.rejected.size() > cfg.maxRejectedLogged) a.rejected.remove(0);
        touch();
        return Result.fail(code, msg, a.id);
    }

    // ------------------------------------------------------------------ payments

    /**
     * Records a payment seen in chat. This NEVER confirms anything: the record starts as DETECTED
     * (or UNMATCHED) and only {@link #confirmDetected} / {@link #confirmManually} can verify it.
     */
    public synchronized Result onPaymentDetected(String payer, long amount, String raw) {
        long now = clock.now();
        PaymentRecord p = new PaymentRecord();
        p.id = state.nextPaymentId++;
        p.payer = payer;
        p.amount = amount;
        p.detectedAt = now;
        p.rawLine = raw;
        state.payments.add(p);
        touch();

        // 1) Pay-to-bid: while such an auction runs, a payment IS a bid (the payer's total for the auction).
        List<Auction> payToBid = new ArrayList<>();
        for (Auction a : state.auctions) if (a.isRunning() && a.payToBid) payToBid.add(a);
        if (payToBid.size() > 1) {
            p.state = PayState.UNMATCHED;
            p.note = "Several pay-to-bid auctions are running; this payment could not be assigned.";
            return Result.fail("AMBIGUOUS", payer + " paid " + Money.format(amount)
                    + " but several pay-to-bid auctions are running, so it was left UNMATCHED. Open the right auction to award it.", 0);
        }
        if (payToBid.size() == 1) {
            Auction a = payToBid.get(0);
            if (payer.equalsIgnoreCase(a.seller)) {
                p.state = PayState.UNMATCHED;
                p.note = "Payment from the seller.";
                return Result.fail("SELF", "Ignored a payment from yourself.", a.id);
            }
            p.state = PayState.DETECTED;
            p.auctionId = a.id;
            p.note = "Pay-to-bid";
            long total = paidTotal(a.id, payer);
            Result r = tryBid(a, payer, total, "payment #" + p.id + ": " + raw, now);
            if (r.ok) {
                return Result.ok("Pay-to-bid: " + payer + " paid " + Money.format(amount) + " (their total " + Money.format(total)
                        + ") and leads auction #" + a.id + ". NOT confirmed: verify your balance.", a.id);
            }
            return Result.fail(r.code, payer + " paid " + Money.format(amount) + " for #" + a.id
                    + " but it did not become the top bid (" + r.message + ") A refund will be owed if they lose.", a.id);
        }

        // 2) An ended auction waiting for this winner's payment.
        Auction target = null;
        for (Auction a : state.auctions) { // ascending id: oldest unpaid auction for this winner first
            if (a.phase == Phase.ENDED && a.payStatus != PayStatus.CONFIRMED && a.winner != null
                    && a.winner.equalsIgnoreCase(payer)) {
                target = a;
                break;
            }
        }
        if (target == null) {
            p.state = PayState.UNMATCHED;
            p.note = "No ended auction was waiting for a payment from this player.";
            boolean chatMode = false;
            for (Auction a : state.auctions) if (a.isRunning() && !a.payToBid) chatMode = true;
            return Result.fail("UNMATCHED", payer + " paid " + Money.format(amount)
                    + " but no auction is waiting on them. Logged as UNMATCHED (not verified)."
                    + (chatMode ? " Your running auction takes CHAT bids, so this was not counted as a bid. Use pay-to-bid mode if payments should count." : ""), 0);
        }
        p.state = PayState.DETECTED;
        p.auctionId = target.id;
        recompute(target, now);
        String detail;
        long totalSeen = sat(target.detectedPaid, target.confirmedPaid);
        if (totalSeen >= target.finalBid) {
            detail = totalSeen > target.finalBid ? "covers the bid (over by " + Money.format(totalSeen - target.finalBid) + ")"
                    : "matches the final bid";
        } else {
            detail = "is SHORT by " + Money.format(target.finalBid - totalSeen);
        }
        return Result.ok("Payment detected: " + payer + " paid " + Money.format(amount) + " for auction #" + target.id
                + " (" + detail + "). NOT confirmed - verify your balance, then confirm it.", target.id);
    }

    private long paidTotal(int auctionId, String payer) {
        long total = 0;
        for (PaymentRecord p : state.payments) {
            if (p.auctionId == auctionId && p.payer.equalsIgnoreCase(payer)
                    && (p.state == PayState.DETECTED || p.state == PayState.CONFIRMED)) total = sat(total, p.amount);
        }
        return total;
    }

    /** Pay-to-bid only: money each non-winning payer is still owed back (winners are owed only any overpayment). */
    public synchronized Map<String, Long> refundsOwed(Auction a) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (!a.payToBid || a.phase == Phase.RUNNING) return out;
        Map<String, Long> paid = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (PaymentRecord p : state.payments) {
            if (p.auctionId != a.id || (p.state != PayState.DETECTED && p.state != PayState.CONFIRMED)) continue;
            String key = p.payer.toLowerCase(Locale.ROOT);
            paid.merge(key, p.amount, AuctionEngine::sat);
            display.putIfAbsent(key, p.payer);
        }
        for (Map.Entry<String, Long> e : paid.entrySet()) {
            boolean isWinner = a.phase == Phase.ENDED && a.winner != null && a.winner.equalsIgnoreCase(e.getKey());
            long keep = isWinner ? a.finalBid : 0;
            Long done = a.refunded.get(e.getKey());
            long owed = e.getValue() - keep - (done == null ? 0 : done);
            if (owed > 0) out.put(display.get(e.getKey()), owed);
        }
        return out;
    }

    /** You sent the refund yourself with /pay; this records it so the auction can close out. */
    public synchronized Result markRefunded(int auctionId, String payer) {
        Auction a = byId(auctionId);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + auctionId, auctionId);
        for (Map.Entry<String, Long> e : refundsOwed(a).entrySet()) {
            if (e.getKey().equalsIgnoreCase(payer)) {
                a.refunded.merge(payer.toLowerCase(Locale.ROOT), e.getValue(), AuctionEngine::sat);
                touch();
                return Result.ok("Recorded: refunded " + Money.format(e.getValue()) + " to " + e.getKey() + ".", auctionId);
            }
        }
        return Result.fail("NOTHING_OWED", "Nothing is owed to " + payer + ".", auctionId);
    }

    /** Payments that arrived but were not linked to any auction (candidates for "award to payer"). */
    public synchronized List<PaymentRecord> unlinkedPaymentsSince(long since) {
        List<PaymentRecord> out = new ArrayList<>();
        for (PaymentRecord p : state.payments) {
            if (p.auctionId == 0 && p.state == PayState.UNMATCHED && p.detectedAt >= since) out.add(p);
        }
        return out;
    }

    /**
     * Rescue for an auction that closed with no bids although someone paid: makes that payer the winner at
     * the amount they paid. The payment stays DETECTED (unverified) until you confirm it.
     */
    public synchronized Result awardToPayment(int auctionId, int paymentId) {
        Auction a = byId(auctionId);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + auctionId, auctionId);
        if (a.phase != Phase.NO_SALE) return Result.fail("NOT_NO_SALE", "Only auctions that ended with no sale can be awarded.", auctionId);
        PaymentRecord p = paymentById(paymentId);
        if (p == null || p.auctionId != 0 || p.state != PayState.UNMATCHED) {
            return Result.fail("BAD_PAYMENT", "That payment is not available to award.", auctionId);
        }
        long now = clock.now();
        Bid b = new Bid();
        b.seq = state.nextBidSeq++;
        b.bidder = p.payer;
        b.amount = p.amount;
        b.at = p.detectedAt;
        b.raw = "awarded from payment #" + p.id;
        a.bids.add(b);
        a.phase = Phase.ENDED;
        a.winner = p.payer;
        a.finalBid = p.amount;
        a.closeNote = "Awarded to " + p.payer + " from a detected payment";
        a.payStatus = PayStatus.AWAITING;
        p.auctionId = a.id;
        p.state = PayState.DETECTED;
        p.note = "Awarded manually";
        recompute(a, now);
        touch();
        return Result.ok("Auction #" + a.id + " awarded to " + p.payer + " at " + Money.format(p.amount)
                + ". Verify your balance, then confirm the payment.", a.id);
    }

    /** Confirms every DETECTED payment linked to this auction. Only the seller's explicit action calls this. */
    public synchronized Result confirmDetected(int auctionId) {
        Auction a = byId(auctionId);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + auctionId, auctionId);
        if (a.phase != Phase.ENDED) return Result.fail("NOT_ENDED", "Auction #" + auctionId + " has no winner to pay.", auctionId);
        long now = clock.now();
        int n = 0;
        for (PaymentRecord p : state.payments) {
            if (p.auctionId == auctionId && p.state == PayState.DETECTED) {
                p.state = PayState.CONFIRMED;
                p.confirmedAt = now;
                n++;
            }
        }
        if (n == 0) return Result.fail("NOTHING_TO_CONFIRM", "No detected payments to confirm for #" + auctionId
                + ". If you were paid another way, use manual confirmation.", auctionId);
        recompute(a, now);
        touch();
        return paymentSummary(a, n + " payment(s) confirmed.");
    }

    public synchronized Result confirmPayment(int paymentId) {
        PaymentRecord p = paymentById(paymentId);
        if (p == null) return Result.fail("NOT_FOUND", "No payment #" + paymentId, 0);
        if (p.state != PayState.DETECTED) {
            return Result.fail("NOT_CONFIRMABLE", p.state == PayState.UNMATCHED
                    ? "Unmatched payments are not linked to an auction; confirm manually on the right auction instead."
                    : "Payment #" + paymentId + " is " + p.state + ".", p.auctionId);
        }
        long now = clock.now();
        p.state = PayState.CONFIRMED;
        p.confirmedAt = now;
        Auction a = byId(p.auctionId);
        if (a != null) recompute(a, now);
        touch();
        return a == null ? Result.ok("Payment confirmed.", 0) : paymentSummary(a, "Payment confirmed.");
    }

    public synchronized Result rejectPayment(int paymentId) {
        PaymentRecord p = paymentById(paymentId);
        if (p == null) return Result.fail("NOT_FOUND", "No payment #" + paymentId, 0);
        if (p.state != PayState.DETECTED && p.state != PayState.UNMATCHED) {
            return Result.fail("NOT_REJECTABLE", "Only unverified payments can be dismissed.", p.auctionId);
        }
        p.state = PayState.REJECTED;
        Auction a = byId(p.auctionId);
        if (a != null) recompute(a, clock.now());
        touch();
        return Result.ok("Payment #" + paymentId + " dismissed.", p.auctionId);
    }

    /**
     * For when you verified the money some other way (balance check, different payment method that the
     * chat parser did not see). Creates a CONFIRMED MANUAL record for exactly the amount you state.
     */
    public synchronized Result confirmManually(int auctionId, long amount, String note) {
        Auction a = byId(auctionId);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + auctionId, auctionId);
        if (a.phase != Phase.ENDED) return Result.fail("NOT_ENDED", "Auction #" + auctionId + " has no winner to pay.", auctionId);
        if (a.payStatus == PayStatus.CONFIRMED) return Result.fail("ALREADY", "Payment is already confirmed.", auctionId);
        if (amount <= 0) return Result.fail("BAD_AMOUNT", "Enter the amount you actually received.", auctionId);
        long now = clock.now();
        PaymentRecord p = new PaymentRecord();
        p.id = state.nextPaymentId++;
        p.payer = a.winner;
        p.amount = amount;
        p.detectedAt = now;
        p.confirmedAt = now;
        p.rawLine = "manual";
        p.source = "MANUAL";
        p.auctionId = auctionId;
        p.state = PayState.CONFIRMED;
        p.note = note == null ? "" : note;
        state.payments.add(p);
        recompute(a, now);
        touch();
        return paymentSummary(a, "Manual payment of " + Money.format(amount) + " recorded.");
    }

    private Result paymentSummary(Auction a, String lead) {
        if (a.payStatus == PayStatus.CONFIRMED) {
            return Result.ok(lead + " Auction #" + a.id + " is fully PAID (" + Money.format(a.confirmedPaid) + ").", a.id);
        }
        long short_ = Math.max(0, a.finalBid - a.confirmedPaid);
        return Result.ok(lead + " Still unverified/short by " + Money.format(short_) + " on #" + a.id + ".", a.id);
    }

    private PaymentRecord paymentById(int id) {
        for (PaymentRecord p : state.payments) if (p.id == id) return p;
        return null;
    }

    private void recompute(Auction a, long now) {
        if (a.phase != Phase.ENDED) {
            a.payStatus = PayStatus.NONE;
            return;
        }
        long confirmed = 0, detected = 0;
        for (PaymentRecord p : state.payments) {
            if (p.auctionId != a.id) continue;
            if (p.state == PayState.CONFIRMED) confirmed = sat(confirmed, p.amount);
            else if (p.state == PayState.DETECTED) detected = sat(detected, p.amount);
        }
        a.confirmedPaid = confirmed;
        a.detectedPaid = detected;
        PayStatus ns;
        if (confirmed >= a.finalBid) ns = PayStatus.CONFIRMED;
        else if (sat(confirmed, detected) >= a.finalBid) ns = PayStatus.DETECTED;
        else if (sat(confirmed, detected) > 0) ns = PayStatus.PARTIAL;
        else ns = PayStatus.AWAITING;
        if (ns == PayStatus.CONFIRMED && a.paymentConfirmedAt == 0) a.paymentConfirmedAt = now;
        a.payStatus = ns;
        checkCompleted(a, now);
    }

    private static void checkCompleted(Auction a, long now) {
        if (a.isCompleted() && a.completedAt == 0) a.completedAt = now;
    }

    private static long sat(long x, long y) {
        long r = x + y;
        return (((x ^ r) & (y ^ r)) < 0) ? Long.MAX_VALUE : r;
    }

    // ------------------------------------------------------------------ delivery / forfeit

    public synchronized Result markDelivered(int auctionId) {
        Auction a = byId(auctionId);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + auctionId, auctionId);
        if (a.phase != Phase.ENDED) return Result.fail("NOT_ENDED", "Auction #" + auctionId + " has no winner.", auctionId);
        if (a.delivered) return Result.fail("ALREADY", "Already marked as delivered.", auctionId);
        if (cfg.requirePaymentBeforeDelivery && a.payStatus != PayStatus.CONFIRMED) {
            return Result.fail("PAY_FIRST", "Confirm the payment before handing over the item "
                    + "(setting requirePaymentBeforeDelivery).", auctionId);
        }
        long now = clock.now();
        a.delivered = true;
        a.deliveredAt = now;
        checkCompleted(a, now);
        touch();
        return Result.ok(a.isCompleted()
                ? "Auction #" + auctionId + " is COMPLETE (paid and delivered)."
                : "Delivery recorded. Auction #" + auctionId + " stays open until payment is confirmed.", auctionId);
    }

    /**
     * The winner did not pay. Offers the item to the next-highest bidder at THEIR bid, or ends the
     * auction with no sale if there is nobody else. Refused once any payment has been confirmed.
     */
    public synchronized Result forfeitWinner(int auctionId) {
        Auction a = byId(auctionId);
        if (a == null) return Result.fail("NOT_FOUND", "No auction #" + auctionId, auctionId);
        if (a.phase != Phase.ENDED) return Result.fail("NOT_ENDED", "Auction #" + auctionId + " has no winner.", auctionId);
        if (a.payToBid) return Result.fail("PAY_TO_BID", "In pay-to-bid the winner already paid; refund losing bidders instead.", auctionId);
        if (a.confirmedPaid > 0 || a.payStatus == PayStatus.CONFIRMED) {
            return Result.fail("PAID", "A payment is already confirmed for this auction.", auctionId);
        }
        if (a.delivered) return Result.fail("DELIVERED", "The item was already delivered.", auctionId);
        long now = clock.now();
        String loser = a.winner;
        a.forfeitedWinners.add(loser);
        if (cfg.autoBlockOnForfeit) block(loser);
        for (PaymentRecord p : state.payments) {
            if (p.auctionId == a.id && p.state == PayState.DETECTED) {
                p.auctionId = 0;
                p.state = PayState.UNMATCHED;
                p.note = "Winner forfeited auction #" + a.id + "; unverified.";
            }
        }
        Bid next = null;
        for (int i = a.bids.size() - 1; i >= 0; i--) {
            Bid b = a.bids.get(i);
            if (!isForfeited(a, b.bidder)) {
                next = b;
                break;
            }
        }
        touch();
        if (next == null) {
            a.phase = Phase.NO_SALE;
            a.payStatus = PayStatus.NONE;
            a.closeNote = loser + " did not pay and there were no other bidders";
            return Result.ok("No other bidders. Auction #" + a.id + " closed with no sale.", a.id);
        }
        a.winner = next.bidder;
        a.finalBid = next.amount;
        a.payStatus = PayStatus.AWAITING;
        a.closeNote = "Offered to runner-up after " + loser + " did not pay";
        recompute(a, now);
        return Result.ok(loser + " forfeited. Item offered to " + next.bidder + " at " + Money.format(next.amount) + ".", a.id);
    }

    private static boolean isForfeited(Auction a, String name) {
        for (String f : a.forfeitedWinners) if (f.equalsIgnoreCase(name)) return true;
        return false;
    }

    // ------------------------------------------------------------------ reminders

    /** Countdown reminders (e.g. 60/30/10/5 seconds). Each threshold fires once per auction. */
    public synchronized List<Reminder> dueReminders(int[] thresholdsSec) {
        long now = clock.now();
        List<Reminder> out = new ArrayList<>();
        for (Auction a : state.auctions) {
            if (!a.isRunning()) continue;
            int rem = (int) Math.min(Integer.MAX_VALUE - 1, (a.remainingMs(now) + 999) / 1000);
            if (a.lastReminder != Integer.MAX_VALUE && rem > a.lastReminder) a.lastReminder = Integer.MAX_VALUE; // extended
            if (a.lastReminder == Integer.MAX_VALUE) {
                int init = Integer.MAX_VALUE - 1;
                for (int t : thresholdsSec) if (t >= rem && t < init) init = t;
                a.lastReminder = init;
                continue;
            }
            int fire = Integer.MAX_VALUE;
            for (int t : thresholdsSec) if (rem <= t && t < a.lastReminder && t < fire) fire = t;
            if (fire != Integer.MAX_VALUE && fire > 0) {
                a.lastReminder = fire;
                out.add(new Reminder(a, rem));
            }
        }
        return out;
    }
}
