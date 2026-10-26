package dev.donutauction;

import dev.donutauction.core.*;
import dev.donutauction.core.AuctionEngine.Result;
import dev.donutauction.core.Auction.PayStatus;
import dev.donutauction.core.Auction.Phase;
import dev.donutauction.core.PaymentRecord.PayState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dependency-free tests for everything that does not need Minecraft. Run: ./gradlew coreSelfTest */
public final class CoreSelfTest {
    private static int passed, failed;
    private static final long[] T = {1_000_000L};

    private static void check(boolean cond, String name) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static AuctionEngine newEngine(Settings s) {
        T[0] = 1_000_000L;
        return new AuctionEngine(new State(), s, () -> T[0]);
    }

    private static ItemInfo item() {
        ItemInfo i = new ItemInfo();
        i.itemId = "minecraft:elytra";
        i.displayName = "Elytra";
        i.count = 1;
        i.enchanted = true;
        i.lines.add("Unbreaking III");
        i.lines.add("Mending");
        return i;
    }

    public static void main(String[] args) {
        money();
        parsing();
        bidding();
        simultaneous();
        closing();
        antiSnipe();
        payments();
        deliveryGating();
        forfeit();
        reminders();
        router();
        smartParsing();
        payToBid();
        rescue();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void money() {
        check(Money.parse("500").getAsLong() == 500, "plain");
        check(Money.parse("2.5k").getAsLong() == 2500, "2.5k");
        check(Money.parse("$1.2M").getAsLong() == 1_200_000, "$1.2M");
        check(Money.parse("3b").getAsLong() == 3_000_000_000L, "3b");
        check(Money.parse("1,500").getAsLong() == 1500, "grouped");
        check(Money.parse("1,5m").isEmpty(), "european comma rejected (would be a 10x misread)");
        check(Money.parse("0").isEmpty(), "zero rejected");
        check(Money.parse("-5").isEmpty(), "negative rejected");
        check(Money.parse("1.00001k").isEmpty(), "fractional unit rejected");
        check(Money.parse("abc").isEmpty(), "garbage rejected");
        check(Money.parse("99999999999999999999").isEmpty(), "overflow rejected");
        check(Money.format(2_500_000).equals("2.5m"), "format 2.5m");
        check(Money.format(500).equals("500"), "format 500");
        check(Money.format(2_222_200).equals("2,222,200"), "format lossy falls back to exact");
        check(Fmt.parseDurationMs("90").getAsLong() == 90_000, "dur 90");
        check(Fmt.parseDurationMs("2m").getAsLong() == 120_000, "dur 2m");
        check(Fmt.parseDurationMs("x").isEmpty(), "dur bad");
        check(Fmt.duration(272_000).equals("4:32"), "duration fmt");
    }

    static void parsing() {
        ChatParser p = new ChatParser(new Settings());
        check(p.errors.isEmpty(), "default regexes compile: " + p.errors);
        ChatParser.ParsedBid b = p.parseBid("Steve \u00bb bid 5m");
        check(b != null && b.user.equals("Steve") && b.amount == 5_000_000 && b.auctionId == 0, "basic bid");
        b = p.parseBid("[MVP+] Steve \u00bb !bid #7 2.5k");
        check(b != null && b.user.equals("Steve") && b.amount == 2500 && b.auctionId == 7, "rank + id");
        b = p.parseBid("\u00a7aSteve\u00a7r: bid $1,000");
        check(b != null && b.amount == 1000, "color codes + grouped");
        b = p.parseBid("Steve: bid 1,5m");
        check(b != null && b.amount == -1, "bad amount flagged, not guessed");
        check(p.parseBid("Mallory \u00bb Bob \u00bb bid 100m") == null, "SPOOF: nested name is not a bid by Bob");
        check(p.parseBid("Mallory: hey Bob: bid 100m") == null, "SPOOF: embedded colon");
        check(p.parseBid("Steve \u00bb I would bid 5m if I could") == null, "ordinary chat ignored");
        b = p.parseBid("[Steve -> me] bid 3k");
        check(b != null && b.user.equals("Steve") && b.amount == 3000, "whisper format");
        ChatParser.ParsedPayment pay = p.parsePayment("Steve paid you $2.5m");
        check(pay != null && pay.user.equals("Steve") && pay.amount == 2_500_000, "payment line");
        check(p.parsePayment("Mallory \u00bb Bob paid you $5m") == null, "SPOOF: typed payment line ignored");
        check(p.parsePayment("Steve paid you $2.5m.") != null, "payment with trailing period");
        Settings bad = new Settings();
        bad.bidPatterns.add("([unclosed");
        check(new ChatParser(bad).errors.size() == 1, "bad regex reported, not fatal");
    }

    static void bidding() {
        Settings s = new Settings();
        AuctionEngine e = newEngine(s);
        Result c = e.createAuction(item(), "Seller", 1000, 60_000, 100);
        check(c.ok && c.auctionId == 1, "create");
        check(!e.createAuction(item(), "Seller", 0, 60_000, 1).ok, "start 0 rejected");
        check(!e.createAuction(item(), "Seller", 1000, 1_000, 1).ok, "duration too short rejected");
        check(!e.createAuction(item(), "Seller", 1000, 60_000, 0).ok, "increment 0 rejected");
        check(e.placeBid(0, "Alice", 999, "x").code.equals("BELOW_START"), "below start");
        check(e.placeBid(0, "Alice", 1000, "x").ok, "bid at start accepted");
        check(e.placeBid(0, "Alice", 1000, "x").code.equals("DUPLICATE"), "duplicate");
        check(e.placeBid(0, "Bob", 1000, "x").code.equals("NOT_HIGHER"), "tie with high rejected");
        check(e.placeBid(0, "Bob", 900, "x").code.equals("NOT_HIGHER"), "lower rejected");
        check(e.placeBid(0, "Bob", 1050, "x").code.equals("BELOW_INCREMENT"), "below increment");
        check(e.placeBid(0, "Bob", 1100, "x").ok, "exact increment accepted");
        check(e.placeBid(0, "Seller", 5000, "x").code.equals("SELF_BID"), "self bid");
        check(e.placeBid(0, "bad name!", 5000, "x").code.equals("BAD_NAME"), "bad name");
        check(e.placeBid(0, "Carol", Long.MAX_VALUE, "x").code.equals("TOO_HIGH"), "absurd bid blocked");
        check(e.block("Dave"), "block");
        check(e.placeBid(0, "dave", 9000, "x").code.equals("BLOCKED"), "blocked case-insensitive");
        check(e.byId(1).highest().bidder.equals("Bob"), "highest tracked");
        check(e.byId(1).bids.size() == 2, "only accepted bids in history");
        check(e.byId(1).rejected.size() >= 6, "rejections are logged");
        check(e.placeBid(99, "Carol", 5000, "x").code.equals("NO_AUCTION"), "unknown id");
        e.createAuction(item(), "Seller", 50, 60_000, 1);
        check(e.placeBid(0, "Carol", 5000, "x").code.equals("AMBIGUOUS"), "ambiguous with two running");
        check(e.placeBid(2, "Carol", 60, "x").ok && e.byId(2).highest().bidder.equals("Carol"), "explicit id works");
    }

    static void simultaneous() {
        AuctionEngine e = newEngine(new Settings());
        e.createAuction(item(), "Seller", 100, 60_000, 1);
        Result a = e.placeBid(0, "Alice", 500, "first");
        Result b = e.placeBid(0, "Bob", 500, "second");
        check(a.ok && !b.ok, "same amount, same instant: first received wins");
        check(e.byId(1).highest().bidder.equals("Alice"), "first received holds the lead");
        check(e.placeBid(0, "Bob", 501, "x").ok, "next bid still works");
    }

    static void closing() {
        AuctionEngine e = newEngine(new Settings());
        e.createAuction(item(), "Seller", 100, 30_000, 1);
        e.placeBid(0, "Alice", 200, "x");
        e.placeBid(0, "Bob", 300, "x");
        check(e.tick().isEmpty(), "not closed early");
        T[0] += 29_999;
        check(e.tick().isEmpty(), "not closed 1ms early");
        check(e.placeBid(0, "Carol", 999, "x").ok, "bid in the last ms accepted");
        T[0] += 1;
        check(e.placeBid(0, "Dave", 5000, "x").code.equals("ENDED"), "bid at exactly zero is rejected even before the tick closes it");
        List<Auction> closed = e.tick();
        check(closed.size() == 1, "closes at zero");
        Auction a = e.byId(1);
        check(a.phase == Phase.ENDED && a.winner.equals("Carol") && a.finalBid == 999, "highest valid bid wins");
        check(a.payStatus == PayStatus.AWAITING, "awaiting payment");
        check(e.placeBid(1, "Eve", 5000, "late").code.equals("ENDED"), "late bid rejected");
        check(e.tick().isEmpty(), "no double close");

        e.createAuction(item(), "Seller", 100, 30_000, 1);
        T[0] += 30_000;
        e.tick();
        check(e.byId(2).phase == Phase.NO_SALE, "no bids = no sale");
        check(e.unsold().size() == 1, "unsold list");

        e.createAuction(item(), "Seller", 100, 60_000, 1);
        check(e.cancel(3).ok && e.byId(3).phase == Phase.CANCELLED, "cancel");
        check(!e.cancel(3).ok, "cancel twice rejected");
        e.createAuction(item(), "Seller", 100, 60_000, 1);
        e.placeBid(4, "Alice", 150, "x");
        check(e.endNow(4).ok && e.byId(4).winner.equals("Alice"), "end now picks the winner");
    }

    static void antiSnipe() {
        Settings s = new Settings();
        s.antiSnipeSeconds = 10;
        AuctionEngine e = newEngine(s);
        e.createAuction(item(), "Seller", 100, 60_000, 1);
        T[0] += 55_000;
        long before = e.byId(1).endsAt;
        e.placeBid(0, "Alice", 200, "x");
        check(e.byId(1).endsAt == T[0] + 10_000 && e.byId(1).endsAt > before, "late bid extends timer");
        check(e.byId(1).extensions == 1, "extension counted");
        T[0] += 20_000;
        e.placeBid(0, "Bob", 300, "x");
        check(e.byId(1).extensions == 1, "early bid does not extend");
    }

    static void payments() {
        AuctionEngine e = newEngine(new Settings());
        e.createAuction(item(), "Seller", 1000, 30_000, 1);
        e.placeBid(0, "Alice", 2_000_000, "x");
        T[0] += 30_000;
        e.tick();
        Auction a = e.byId(1);

        Result r = e.onPaymentDetected("Zed", 5000, "Zed paid you $5k");
        check(!r.ok && r.code.equals("UNMATCHED"), "payer who did not win is unmatched");
        check(e.payments().get(0).state == PayState.UNMATCHED, "unmatched state");
        check(a.payStatus == PayStatus.AWAITING, "unmatched payment does not touch the auction");

        r = e.onPaymentDetected("alice", 500_000, "Alice paid you $500k");
        check(r.ok && a.payStatus == PayStatus.PARTIAL, "partial payment detected");
        check(a.confirmedPaid == 0, "nothing confirmed by detection");
        check(!e.confirmDetected(1).message.contains("fully PAID"), "short payment is not 'fully paid'");
        check(a.payStatus == PayStatus.PARTIAL, "still short after confirming the partial");

        r = e.onPaymentDetected("Alice", 1_500_000, "Alice paid you $1.5m");
        check(r.ok && a.payStatus == PayStatus.DETECTED, "full amount seen but UNVERIFIED");
        check(a.confirmedPaid == 500_000, "only the part the seller confirmed counts");
        check(!e.markDelivered(1).ok, "cannot deliver while payment unverified");
        check(!a.isCompleted(), "not complete");

        r = e.confirmDetected(1);
        check(r.ok && a.payStatus == PayStatus.CONFIRMED, "explicit confirmation -> CONFIRMED");
        check(e.confirmedIncome() == 2_000_000, "income only counts confirmed payments (not the unmatched one)");
        check(!a.isCompleted() && a.completedAt == 0, "paid but not delivered is NOT complete");
        check(e.markDelivered(1).ok && a.isCompleted() && a.completedAt > 0, "paid + delivered = complete");
        check(e.completed().size() == 1 && e.toSettle().isEmpty(), "lists updated");
        check(!e.markDelivered(1).ok, "double delivery rejected");

        r = e.onPaymentDetected("Alice", 7, "Alice paid you $7");
        check(!r.ok && r.code.equals("UNMATCHED"), "extra payment after settlement is unmatched");

        // reject + manual
        e.createAuction(item(), "Seller", 1000, 30_000, 1);
        e.placeBid(0, "Bob", 4000, "x");
        T[0] += 30_000;
        e.tick();
        Auction b = e.byId(2);
        e.onPaymentDetected("Bob", 4000, "Bob paid you $4k");
        int pid = e.payments().get(0).id;
        check(e.rejectPayment(pid).ok && b.payStatus == PayStatus.AWAITING, "dismissed payment reverts to awaiting");
        check(!e.confirmDetected(2).ok, "nothing left to confirm");
        check(!e.confirmManually(2, 3999, "short").message.contains("fully PAID"), "manual short amount does not complete");
        check(e.confirmManually(2, 1, "rest").ok && b.payStatus == PayStatus.CONFIRMED, "manual top-up completes it");
        check(e.payments().get(0).source.equals("MANUAL") && e.payments().get(0).state == PayState.CONFIRMED, "manual record flagged");
        check(!e.confirmPayment(e.payments().get(0).id).ok, "cannot re-confirm");

        // delivery-first allowed when the setting is off
        Settings loose = new Settings();
        loose.requirePaymentBeforeDelivery = false;
        AuctionEngine e2 = newEngine(loose);
        e2.createAuction(item(), "Seller", 10, 30_000, 1);
        e2.placeBid(0, "Cyn", 10, "x");
        T[0] += 30_000;
        e2.tick();
        check(e2.markDelivered(1).ok && !e2.byId(1).isCompleted(), "delivered first, still not complete");
        e2.onPaymentDetected("Cyn", 10, "Cyn paid you $10");
        check(!e2.byId(1).isCompleted(), "detected payment alone never completes");
        e2.confirmDetected(1);
        check(e2.byId(1).isCompleted(), "completes the moment payment is confirmed");
    }

    static void deliveryGating() {
        AuctionEngine e = newEngine(new Settings());
        e.createAuction(item(), "Seller", 10, 30_000, 1);
        check(!e.markDelivered(1).ok, "cannot deliver a running auction");
        check(!e.confirmManually(1, 10, "").ok, "cannot confirm payment on a running auction");
        check(!e.forfeitWinner(1).ok, "cannot forfeit a running auction");
    }

    static void forfeit() {
        AuctionEngine e = newEngine(new Settings());
        e.createAuction(item(), "Seller", 100, 30_000, 1);
        e.placeBid(0, "Alice", 200, "x");
        e.placeBid(0, "Bob", 300, "x");
        e.placeBid(0, "Alice", 400, "x");
        T[0] += 30_000;
        e.tick();
        Auction a = e.byId(1);
        check(a.winner.equals("Alice") && a.finalBid == 400, "alice wins");
        e.onPaymentDetected("Alice", 100, "Alice paid you $100");
        Result r = e.forfeitWinner(1);
        check(r.ok && a.winner.equals("Bob") && a.finalBid == 300, "runner-up gets it at THEIR bid");
        check(a.payStatus == PayStatus.AWAITING, "runner-up awaiting payment");
        check(e.isBlocked("Alice"), "defaulter auto-blocked");
        check(e.payments().get(0).state == PayState.UNMATCHED, "forfeited winner's unverified payment is unlinked");
        r = e.forfeitWinner(1);
        check(r.ok && a.phase == Phase.NO_SALE, "no one left -> no sale");

        e.createAuction(item(), "Seller", 100, 30_000, 1);
        e.placeBid(0, "Cyn", 200, "x");
        T[0] += 30_000;
        e.tick();
        e.onPaymentDetected("Cyn", 200, "Cyn paid you $200");
        e.confirmDetected(2);
        check(!e.forfeitWinner(2).ok, "cannot forfeit a winner who has paid");
    }

    static void reminders() {
        AuctionEngine e = newEngine(new Settings());
        int[] th = {60, 30, 10, 5};
        e.createAuction(item(), "Seller", 100, 120_000, 1);
        check(e.dueReminders(th).isEmpty(), "no reminder at start");
        T[0] += 60_000;
        check(e.dueReminders(th).size() == 1, "60s reminder");
        check(e.dueReminders(th).isEmpty(), "fires once");
        T[0] += 31_000;
        check(e.dueReminders(th).size() == 1, "30s reminder");
        T[0] += 20_000;
        check(e.dueReminders(th).size() == 1, "10s reminder");
        T[0] += 4_000;
        List<AuctionEngine.Reminder> r = e.dueReminders(th);
        check(r.size() == 1 && r.get(0).secondsLeft == 5, "5s reminder");
        AuctionEngine e2 = newEngine(new Settings());
        e2.createAuction(item(), "Seller", 100, 30_000, 1);
        check(e2.dueReminders(th).isEmpty(), "short auction does not announce 60s");
    }

    static void router() {
        Settings s = new Settings();
        AuctionEngine e = newEngine(s);
        List<String> log = new ArrayList<>();
        ChatRouter.Notifier n = new ChatRouter.Notifier() {
            public void info(String m) { log.add("I:" + m); }
            public void good(String m) { log.add("G:" + m); }
            public void warn(String m) { log.add("W:" + m); }
        };
        ChatRouter r = new ChatRouter(e, s, new ChatParser(s), n);
        r.setSelfName("Seller");
        check(!r.handle("Alice \u00bb bid 5m", true, T[0]), "no auction running: bids ignored");
        e.createAuction(item(), "Seller", 1_000_000, 60_000, 1);
        check(r.handle("Alice \u00bb bid 5m", false, T[0]), "bid recognised");
        check(e.byId(1).highest().amount == 5_000_000, "bid applied");
        check(!r.handle("Alice \u00bb bid 5m", false, T[0] + 10), "identical line within 250ms is a duplicate delivery");
        T[0] += 1000;
        check(r.handle("Bob \u00bb bid 4m", false, T[0]) && e.byId(1).bids.size() == 1, "low bid rejected via chat");
        check(r.handle("Bob \u00bb bid 1,5m", false, T[0]) && e.byId(1).bids.size() == 1, "ambiguous number never applied");
        check(!r.handle("Seller \u00bb bid 99m", false, T[0]) , "seller's own lines ignored");

        T[0] += 60_000;
        e.tick();
        check(r.handle("Alice paid you $5m", true, T[0]) && e.byId(1).payStatus == PayStatus.DETECTED, "payment via system message detected");
        check(e.byId(1).payStatus != PayStatus.CONFIRMED, "and NOT confirmed");
        e.createAuction(item(), "Seller", 10, 60_000, 1);
        check(!r.handle("Mallory \u00bb Bob paid you $5m", false, T[0]), "typed payment line ignored");
        boolean sawWarn = false;
        for (String l : log) if (l.startsWith("W:")) sawWarn = true;
        check(sawWarn, "payment warning shown to the seller");
    }

    static void smartParsing() {
        ChatParser p = new ChatParser(new Settings());
        ChatParser.ParsedBid b = p.parseBid("Elite Steve \u00bb bid 1m");
        check(b != null && b.user.equals("Steve") && b.amount == 1_000_000, "smart: rank word before name");
        b = p.parseBid("[12:30] [VIP] Alex > bid 250k");
        check(b != null && b.user.equals("Alex") && b.amount == 250_000, "smart: timestamp + rank tags + > separator");
        b = p.parseBid("Steve \u203a !bid #3 2.5m");
        check(b != null && b.auctionId == 3 && b.amount == 2_500_000, "smart: unusual separator and id");
        b = p.parseBid("<Steve> bid 5k");
        check(b != null && b.user.equals("Steve"), "smart: angle-bracket name");
        check(p.parseBid("Mallory | Bob | bid 100m") == null, "SPOOF: nested name rejected by smart reader");
        check(p.parseBid("Mallory > I will bid 5m later") == null, "smart reader needs the message to START with bid");
        check(p.parseBid("Steve > anyone want to bid") == null, "smart: plain chat ignored");
        Settings off = new Settings();
        off.smartParsing = false;
        check(new ChatParser(off).parseBid("Elite Steve \u00bb bid 1m") == null, "smart reader can be switched off");
        ChatParser.ParsedPayment pay = p.parsePayment("Steve sent you $1M");
        check(pay != null && pay.amount == 1_000_000, "payment: sent you");
        pay = p.parsePayment("You received $1,000,000 from Steve");
        check(pay != null && pay.user.equals("Steve") && pay.amount == 1_000_000, "payment: you received ... from");
        pay = p.parsePayment("[$] Steve has paid you $2.5m!");
        check(pay != null && pay.user.equals("Steve") && pay.amount == 2_500_000, "payment: tag + has paid you");
    }

    static void payToBid() {
        AuctionEngine e = newEngine(new Settings());
        e.createAuction(item(), "Seller", 500_000, 60_000, 1, true);
        check(e.placeBid(0, "Alice", 1_000_000, "x").code.equals("PAY_ONLY"), "chat bids refused in pay-to-bid");
        Result r = e.onPaymentDetected("Alice", 600_000, "Alice paid you $600k");
        check(r.ok && e.byId(1).highest().bidder.equals("Alice") && e.byId(1).highest().amount == 600_000, "payment becomes a bid");
        r = e.onPaymentDetected("Bob", 400_000, "Bob paid you $400k");
        check(!r.ok && e.byId(1).highest().bidder.equals("Alice"), "payment below start does not lead");
        r = e.onPaymentDetected("Bob", 300_000, "Bob paid you $300k");
        check(r.ok && e.byId(1).highest().bidder.equals("Bob") && e.byId(1).highest().amount == 700_000, "top-ups add up");
        r = e.onPaymentDetected("Alice", 200_000, "Alice paid you $200k");
        check(r.ok && e.byId(1).highest().bidder.equals("Alice") && e.byId(1).highest().amount == 800_000, "outbid by topping up");
        check(e.onPaymentDetected("Seller", 9_000_000, "x").code.equals("SELF"), "seller payment ignored");
        T[0] += 60_000;
        e.tick();
        Auction a = e.byId(1);
        check(a.phase == Phase.ENDED && a.winner.equals("Alice") && a.finalBid == 800_000, "highest total wins");
        check(a.payStatus == PayStatus.DETECTED, "winner already paid: detected, still UNVERIFIED");
        Map<String, Long> owed = e.refundsOwed(a);
        check(owed.size() == 1 && owed.get("Bob") == 700_000L, "loser is owed their total back");
        check(e.toSettle().size() == 1, "stays in Settle while refunds or confirmation are pending");
        check(!e.markDelivered(1).ok, "cannot deliver before confirming");
        check(e.confirmDetected(1).ok && e.markDelivered(1).ok, "confirm then deliver");
        check(a.isCompleted() && !e.completed().contains(a), "paid and delivered, but not listed as sold while a refund is owed");
        check(e.markRefunded(1, "bob").ok && e.refundsOwed(a).isEmpty(), "refund recorded");
        check(e.completed().size() == 1 && e.toSettle().isEmpty(), "complete once paid, delivered and refunded");
        check(!e.markRefunded(1, "Bob").ok, "cannot refund twice");
        check(!e.forfeitWinner(1).ok, "forfeit not used in pay-to-bid");

        AuctionEngine e2 = newEngine(new Settings());
        e2.createAuction(item(), "Seller", 100, 60_000, 1, true);
        e2.onPaymentDetected("Cyn", 500, "x");
        e2.cancel(1);
        check(e2.refundsOwed(e2.byId(1)).get("Cyn") == 500L, "cancelled pay-to-bid owes everyone");
        check(e2.toSettle().size() == 1 && e2.unsold().isEmpty(), "cancelled with money held shows under Settle");
        e2.createAuction(item(), "Seller", 100, 60_000, 1, true);
        e2.createAuction(item(), "Seller", 100, 60_000, 1, true);
        check(e2.onPaymentDetected("Dan", 500, "x").code.equals("AMBIGUOUS"), "two pay-to-bid auctions: payment not guessed");
    }

    static void rescue() {
        // The reported case: chat-bid auction ends with no bids, then a friend's 1m payment is seen.
        AuctionEngine e = newEngine(new Settings());
        e.createAuction(item(), "Seller", 500_000, 30_000, 1);
        Result r = e.onPaymentDetected("Friend", 1_000_000, "Friend paid you $1m");
        check(!r.ok && r.message.contains("CHAT bids"), "mid-auction payment explains why it was not a bid");
        T[0] += 30_000;
        List<Auction> closed = e.tick();
        Auction a = closed.get(0);
        check(a.phase == Phase.NO_SALE && a.closeNote.contains("Friend paid"), "no-bid auction notes the payment");
        check(AuctionEngine.describeClosed(a).contains("Friend paid"), "close message mentions the payment");
        List<PaymentRecord> cands = e.unlinkedPaymentsSince(a.createdAt);
        check(cands.size() == 1, "payment offered as a candidate");
        Result aw = e.awardToPayment(1, cands.get(0).id);
        check(aw.ok && a.phase == Phase.ENDED && a.winner.equals("Friend") && a.finalBid == 1_000_000, "award makes the payer the winner");
        check(a.payStatus == PayStatus.DETECTED, "awarded payment is still unverified");
        check(e.confirmDetected(1).ok && e.markDelivered(1).ok && a.isCompleted(), "then it completes normally");
        check(!e.awardToPayment(1, cands.get(0).id).ok, "cannot award twice");
    }
}
