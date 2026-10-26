package dev.donutauction.gui;

import dev.donutauction.DonutAuctionClient;
import dev.donutauction.core.Announcer;
import dev.donutauction.core.Auction;
import dev.donutauction.core.Auction.PayStatus;
import dev.donutauction.core.Auction.Phase;
import dev.donutauction.core.AuctionEngine;
import dev.donutauction.core.AuctionEngine.Result;
import dev.donutauction.core.Bid;
import dev.donutauction.core.Fmt;
import dev.donutauction.core.ItemInfo;
import dev.donutauction.core.Money;
import dev.donutauction.core.PaymentRecord;
import dev.donutauction.core.PaymentRecord.PayState;
import dev.donutauction.mc.ItemIcons;
import dev.donutauction.mc.McCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * One auction in full. Anything that cannot be undone (end, cancel, confirm payment, mark delivered,
 * forfeit) needs two clicks: the first click only arms the button.
 */
public final class AuctionDetailScreen extends ModernScreen {
    private static final class Spec {
        String label = "";
        int w;
        Kind kind = Kind.GHOST;
        boolean enabled = true;
        boolean field;
        Runnable action = () -> { };
    }

    private static final class Line {
        final String text;
        final int color;

        Line(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private final int auctionId;
    private final Screen parent;
    private final Armer armer = new Armer();
    private boolean showRejected;
    private String manualText;
    private String sig = "";
    private int lx, lw, rx, rw, ctop, cbottom, cardAH;

    public AuctionDetailScreen(int auctionId, Screen parent) {
        super("Auction #" + auctionId);
        this.auctionId = auctionId;
        this.parent = parent;
    }

    private Auction auction() {
        return DonutAuctionClient.engine().byId(auctionId);
    }

    private static void say(Result r) {
        DonutAuctionClient.say(r.message, r.ok ? 0x55FF55 : 0xFF5555);
    }

    private String signature() {
        Auction a = auction();
        if (a == null) return "gone";
        AuctionEngine e = DonutAuctionClient.engine();
        int paymentsHere = 0;
        for (PaymentRecord p : e.payments()) if (p.auctionId == a.id) paymentsHere += 1 + p.state.ordinal() * 7;
        return a.phase + "|" + a.payStatus + "|" + a.delivered + "|" + a.winner + "|" + a.forfeitedWinners.size() + "|"
                + a.detectedPaid + "|" + a.confirmedPaid + "|" + a.bids.size() + "|" + (showRejected ? a.rejected.size() : -1)
                + "|" + paymentsHere + "|" + e.refundsOwed(a).size() + "|" + e.unlinkedPaymentsSince(a.createdAt).size();
    }

    // ------------------------------------------------------------------ buttons

    private Spec spec(String label, Kind kind, boolean enabled, Runnable action) {
        Spec s = new Spec();
        s.label = label;
        s.kind = kind;
        s.enabled = enabled;
        s.action = action;
        s.w = Math.max(72, this.font.width(label) + 20);
        return s;
    }

    /** A two-click button: the first click arms it, the second (within 4 seconds) runs it. */
    private Spec armed(String key, String label, Kind kind, boolean enabled, Runnable action) {
        boolean isArmed = armer.isArmed(key, System.currentTimeMillis());
        return spec(isArmed ? "Sure? Click again" : label, kind, enabled, () -> {
            if (armer.fire(key, System.currentTimeMillis())) action.run();
            rebuildWidgets();
        });
    }

    private List<Spec> specsFor(Auction a) {
        AuctionEngine engine = DonutAuctionClient.engine();
        List<Spec> out = new ArrayList<>();
        switch (a.phase) {
            case RUNNING:
                out.add(spec("Copy announcement", Kind.GHOST, true, () -> {
                    DonutAuctionClient.copyToClipboard(Announcer.start(a));
                    DonutAuctionClient.say("Start announcement copied. Paste it in chat yourself (Ctrl+V, Enter).", 0xAAAAAA);
                }));
                out.add(spec("Copy bid update", Kind.GHOST, true, () -> {
                    DonutAuctionClient.copyToClipboard(Announcer.update(a, System.currentTimeMillis()));
                    DonutAuctionClient.say("Bid update copied. Paste it in chat yourself.", 0xAAAAAA);
                }));
                out.add(armed("end", "End now", Kind.DANGER, true, () -> say(engine.endNow(auctionId))));
                out.add(armed("cancel", "Cancel auction", Kind.DANGER, true, () -> say(engine.cancel(auctionId))));
                break;
            case ENDED: {
                boolean paid = a.payStatus == PayStatus.CONFIRMED;
                out.add(spec("Copy result message", Kind.GHOST, true, () -> {
                    DonutAuctionClient.copyToClipboard(Announcer.result(a));
                    DonutAuctionClient.say("Result message copied. Paste it in chat yourself.", 0xAAAAAA);
                }));
                if (manualText == null) manualText = Money.format(Math.max(1, a.finalBid - a.confirmedPaid));
                Spec f = new Spec();
                f.field = true;
                f.w = 78;
                f.enabled = !paid;
                out.add(f);
                out.add(armed("manual", "Confirm manually", Kind.GHOST, !paid, () -> {
                    OptionalLong amt = Money.parse(manualText);
                    if (amt.isEmpty()) {
                        DonutAuctionClient.say("Type the amount you actually received (for example 2.5m).", 0xFF5555);
                    } else {
                        say(engine.confirmManually(auctionId, amt.getAsLong(), "Verified manually by seller"));
                    }
                }));
                out.add(armed("confirm", "Confirm detected payment", Kind.SUCCESS, !paid && a.detectedPaid > 0,
                        () -> say(engine.confirmDetected(auctionId))));
                boolean canDeliver = !a.delivered && (!DonutAuctionClient.settings().requirePaymentBeforeDelivery || paid);
                out.add(armed("deliver", "Mark delivered", Kind.PRIMARY, canDeliver, () -> say(engine.markDelivered(auctionId))));
                out.add(armed("forfeit", "Winner did not pay", Kind.DANGER,
                        a.confirmedPaid == 0 && !a.delivered && !a.payToBid, () -> say(engine.forfeitWinner(auctionId))));
                break;
            }
            default:
                out.add(spec("Relist this item", Kind.PRIMARY, true, () -> {
                    ItemInfo copy = new ItemInfo();
                    copy.itemId = a.item.itemId;
                    copy.count = a.item.count;
                    copy.displayName = a.item.displayName;
                    copy.customName = a.item.customName;
                    copy.enchanted = a.item.enchanted;
                    copy.damage = a.item.damage;
                    copy.maxDamage = a.item.maxDamage;
                    copy.lines = new ArrayList<>(a.item.lines);
                    copy.notes = a.item.notes;
                    McCompat.show(new NewAuctionScreen(copy, this, a.startingBid));
                }));
                out.add(spec("Copy result message", Kind.GHOST, true, () -> {
                    DonutAuctionClient.copyToClipboard(Announcer.result(a));
                    DonutAuctionClient.say("Result message copied. Paste it in chat yourself.", 0xAAAAAA);
                }));
                break;
        }
        out.add(spec(showRejected ? "Hide rejected bids" : "Show rejected bids", Kind.GHOST, true, () -> {
            showRejected = !showRejected;
            rebuildWidgets();
        }));
        return out;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        startLayout();
        button("< Back", px + 10, py + 10, 52, 18, Kind.GHOST, this::onClose);
        Auction a = auction();
        if (a == null) return;

        lx = px + 12;
        int inner = pw - 24;
        lw = inner * 46 / 100;
        rx = lx + lw + 10;
        rw = px + pw - 12 - rx;
        ctop = py + 38;

        List<Spec> specs = specsFor(a);
        int availW = pw - 24;
        int rows = 1;
        int cur = 0;
        for (Spec s : specs) {
            if (cur > 0 && cur + s.w > availW) {
                rows++;
                cur = 0;
            }
            cur += s.w + 6;
        }
        int rowH = 24;
        int ys = py + ph - 10 - rows * rowH;
        cbottom = ys - 6;
        int x = lx;
        int y = ys;
        for (Spec s : specs) {
            if (x > lx && x + s.w > lx + availW) {
                x = lx;
                y += rowH;
            }
            if (s.field) {
                var box = field(x, y, s.w, 20, "amount", 20, manualText);
                box.setResponder(t -> manualText = t);
                box.active = s.enabled;
            } else {
                Btn b = button(s.label, x, y, s.w, 20, s.kind, s.action);
                enable(b, s.enabled);
            }
            x += s.w + 6;
        }
        cardAH = Math.max(76, Math.min(108, (cbottom - ctop) * 45 / 100));
        rightPane(a, null);
        sig = signature();
    }

    @Override
    public void tick() {
        if (armer.expire(System.currentTimeMillis()) || !signature().equals(sig)) rebuildWidgets();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        Auction a = auction();
        if (a == null) {
            Ui.text(g, this.font, "This auction no longer exists.", px + 74, py + 14, Ui.RED);
            drawButtons(g, mx, my);
            return;
        }
        long now = System.currentTimeMillis();
        Ui.text(g, this.font, "Auction #" + a.id, px + 72, py + 14, Ui.TEXT);
        String mode = a.payToBid ? "PAY-TO-BID" : "CHAT BIDS";
        int pillX = px + pw - 12 - (this.font.width(phaseLabel(a)) + 10);
        Ui.pill(g, this.font, phaseLabel(a), pillX, py + 12, phaseColor(a));
        Ui.pill(g, this.font, mode, pillX - this.font.width(mode) - 14, py + 12, Ui.MUTED);

        // item card
        Ui.roundBorder(g, lx, ctop, lx + lw, ctop + cardAH, 6, Ui.CARD, Ui.CARD_BORDER);
        Ui.round(g, lx + 8, ctop + 8, lx + 36, ctop + 36, 5, Ui.FIELD);
        ItemStack icon = ItemIcons.iconFor(a.item.itemId, a.item.count);
        if (!icon.isEmpty()) {
            g.item(icon, lx + 14, ctop + 14);
            g.itemDecorations(this.font, icon, lx + 14, ctop + 14);
        }
        Ui.text(g, this.font, Ui.fit(this.font, a.item.displayName, lw - 54), lx + 44, ctop + 10, Ui.TEXT);
        String sub = "x" + a.item.count + (a.item.customName != null ? "   named " + a.item.customName : "");
        Ui.text(g, this.font, Ui.fit(this.font, sub, lw - 54), lx + 44, ctop + 22, Ui.AQUA);
        int y = ctop + 42;
        int shown = 0;
        for (String line : a.item.lines) {
            if (y > ctop + cardAH - 12) break;
            if (shown++ >= 6) break;
            Ui.text(g, this.font, Ui.fit(this.font, line, lw - 20), lx + 10, y, a.item.enchanted ? Ui.AQUA : Ui.MUTED);
            y += 10;
        }
        if (a.item.notes != null && !a.item.notes.isBlank() && y <= ctop + cardAH - 12) {
            Ui.text(g, this.font, Ui.fit(this.font, "Notes: " + a.item.notes, lw - 20), lx + 10, y, Ui.FAINT);
        }

        // status card
        int sy = ctop + cardAH + 8;
        Ui.roundBorder(g, lx, sy, lx + lw, cbottom, 6, Ui.CARD, Ui.CARD_BORDER);
        int ty = sy + 9;
        for (Line l : statusLines(a, now)) {
            for (String part : Ui.wrap(this.font, l.text, lw - 20)) {
                if (ty > cbottom - 12) break;
                Ui.text(g, this.font, part, lx + 10, ty, l.color);
                ty += 10;
            }
            ty += 2;
        }

        // right card
        Ui.roundBorder(g, rx, ctop, rx + rw, cbottom, 6, Ui.CARD, Ui.CARD_BORDER);
        rightPane(a, g);
        drawButtons(g, mx, my);
    }

    private String phaseLabel(Auction a) {
        switch (a.phase) {
            case RUNNING: return "LIVE " + Fmt.duration(a.remainingMs(System.currentTimeMillis()));
            case ENDED: return a.isCompleted() ? "COMPLETE" : "ENDED";
            case NO_SALE: return "NO SALE";
            default: return "CANCELLED";
        }
    }

    private int phaseColor(Auction a) {
        switch (a.phase) {
            case RUNNING: return a.remainingMs(System.currentTimeMillis()) < 10_000 ? Ui.AMBER : Ui.AQUA;
            case ENDED: return a.isCompleted() ? Ui.GREEN : Ui.AMBER;
            default: return Ui.MUTED;
        }
    }

    /** Draws the bid/payment/refund column, or (g == null) just creates its buttons. */
    private void rightPane(Auction a, GuiGraphicsExtractor g) {
        AuctionEngine engine = DonutAuctionClient.engine();
        boolean draw = g != null;
        int x = rx + 10;
        int w = rw - 20;
        int y = ctop + 10;
        int lim = cbottom - 6;

        if (draw) Ui.text(g, this.font, "Bids (" + a.bids.size() + ")", x, y, Ui.MUTED);
        y += 13;
        if (a.bids.isEmpty()) {
            if (draw) Ui.text(g, this.font, "No bids yet", x, y, Ui.FAINT);
            y += 11;
        } else {
            int count = 0;
            for (int i = a.bids.size() - 1; i >= 0 && count < 6; i--, count++) {
                if (y + 10 > lim) return;
                Bid b = a.bids.get(i);
                if (draw) {
                    Ui.text(g, this.font, Ui.fit(this.font, "#" + b.seq + " " + b.bidder + "  " + Money.format(b.amount) + "  "
                            + Fmt.time(b.at), w), x, y, i == a.bids.size() - 1 ? Ui.GREEN : Ui.TEXT);
                }
                y += 10;
            }
        }
        if (showRejected) {
            y += 5;
            if (y + 10 > lim) return;
            if (draw) Ui.text(g, this.font, "Rejected (" + a.rejected.size() + ")", x, y, Ui.MUTED);
            y += 12;
            int count = 0;
            for (int i = a.rejected.size() - 1; i >= 0 && count < 4; i--, count++) {
                if (y + 10 > lim) return;
                Bid b = a.rejected.get(i);
                if (draw) Ui.text(g, this.font, Ui.fit(this.font, b.bidder + " " + Money.format(b.amount) + "  " + b.rejectReason, w),
                        x, y, Ui.FAINT);
                y += 10;
            }
        }

        List<PaymentRecord> mine = new ArrayList<>();
        for (PaymentRecord p : engine.payments()) if (p.auctionId == a.id) mine.add(p);
        if (!mine.isEmpty()) {
            y += 6;
            if (y + 10 > lim) return;
            if (draw) Ui.text(g, this.font, "Payments", x, y, Ui.MUTED);
            y += 12;
            int count = 0;
            for (PaymentRecord p : mine) {
                if (count++ >= 4 || y + 10 > lim) break;
                int color = p.state == PayState.CONFIRMED ? Ui.GREEN : p.state == PayState.DETECTED ? Ui.AMBER : Ui.FAINT;
                String state = p.state == PayState.DETECTED ? "unverified" : p.state.name().toLowerCase();
                if (draw) Ui.text(g, this.font, Ui.fit(this.font, p.payer + "  " + Money.format(p.amount) + "  " + state, w), x, y, color);
                y += 10;
            }
        }

        Map<String, Long> owed = engine.refundsOwed(a);
        if (!owed.isEmpty()) {
            y += 6;
            if (y + 10 > lim) return;
            if (draw) Ui.text(g, this.font, "Refunds owed (send with /pay, then mark)", x, y, Ui.AMBER);
            y += 13;
            int count = 0;
            for (Map.Entry<String, Long> e : owed.entrySet()) {
                if (count++ >= 3 || y + 14 > lim) break;
                String payer = e.getKey();
                if (draw) {
                    Ui.text(g, this.font, Ui.fit(this.font, payer + "  " + Money.format(e.getValue()), w - 92), x, y + 3, Ui.TEXT);
                } else {
                    Btn b = button("Mark refunded", x + w - 88, y - 1, 88, 15, Kind.SUCCESS, () -> {
                        say(engine.markRefunded(auctionId, payer));
                        rebuildWidgets();
                    });
                    enable(b, true);
                }
                y += 18;
            }
        }

        if (a.phase == Phase.NO_SALE) {
            List<PaymentRecord> cands = engine.unlinkedPaymentsSince(a.createdAt);
            if (!cands.isEmpty()) {
                y += 6;
                if (y + 10 > lim) return;
                if (draw) Ui.text(g, this.font, "Payments seen but not matched to a bid", x, y, Ui.AMBER);
                y += 13;
                int count = 0;
                for (PaymentRecord p : cands) {
                    if (count++ >= 3 || y + 14 > lim) break;
                    String label = "Award to " + p.payer;
                    int bw = Math.min(w - 40, this.font.width(label) + 16);
                    if (draw) {
                        Ui.text(g, this.font, Ui.fit(this.font, p.payer + "  " + Money.format(p.amount), w - bw - 6), x, y + 3, Ui.TEXT);
                    } else {
                        Btn b = button(label, x + w - bw, y - 1, bw, 15, Kind.PRIMARY, () -> {
                            say(engine.awardToPayment(auctionId, p.id));
                            rebuildWidgets();
                        });
                        enable(b, true);
                    }
                    y += 18;
                }
            }
        }
    }

    private List<Line> statusLines(Auction a, long now) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(a.payToBid ? "Bids: pay-to-bid. A /pay to you counts as a bid (their total for this auction)."
                : "Bids: chat. Bidders type: bid #" + a.id + " <amount>", Ui.FAINT));
        switch (a.phase) {
            case RUNNING: {
                Bid h = a.highest();
                out.add(new Line(h == null ? "No bids yet. Starting bid " + Money.both(a.startingBid)
                        : "Highest: " + Money.both(h.amount) + " by " + h.bidder, h == null ? Ui.MUTED : Ui.TEXT));
                out.add(new Line("Minimum raise " + Money.format(a.minIncrement)
                        + (a.extensions > 0 ? "   (timer extended x" + a.extensions + ")" : ""), Ui.MUTED));
                out.add(new Line("Next: post the announcement and wait for "
                        + (a.payToBid ? "payments." : "bids."), Ui.FAINT));
                return out;
            }
            case NO_SALE:
                out.add(new Line("Ended with no sale. " + a.closeNote, a.closeNote.contains("paid") ? Ui.AMBER : Ui.MUTED));
                return out;
            case CANCELLED:
                out.add(new Line("Cancelled. " + a.closeNote, Ui.MUTED));
                return out;
            default:
                break;
        }
        out.add(new Line("Winner " + a.winner + " at " + Money.both(a.finalBid) + "  (" + Fmt.time(a.endedAt) + ")", Ui.TEXT));
        if (!a.forfeitedWinners.isEmpty()) out.add(new Line("Did not pay: " + String.join(", ", a.forfeitedWinners), Ui.RED));
        switch (a.payStatus) {
            case AWAITING:
                out.add(new Line("Payment: waiting. " + a.winner + " should /pay " + a.seller + " " + Money.format(a.finalBid), Ui.AMBER));
                break;
            case PARTIAL:
                out.add(new Line("Payment: SHORT. Seen " + Money.format(a.detectedPaid + a.confirmedPaid) + " of "
                        + Money.format(a.finalBid) + ", not fully verified.", Ui.AMBER));
                break;
            case DETECTED:
                out.add(new Line("Payment: seen in chat but NOT VERIFIED. Check your balance, then confirm.", Ui.AMBER));
                break;
            case CONFIRMED:
                out.add(new Line("Payment: CONFIRMED " + Money.both(a.confirmedPaid), Ui.GREEN));
                break;
            default:
                break;
        }
        out.add(a.delivered ? new Line("Delivery: handed over " + Fmt.time(a.deliveredAt), Ui.GREEN)
                : new Line("Delivery: not delivered yet", Ui.MUTED));
        if (a.isCompleted()) out.add(new Line("COMPLETE: paid and delivered.", Ui.GREEN));
        else if (a.payStatus == PayStatus.CONFIRMED) out.add(new Line("Next: hand the item over, then Mark delivered.", Ui.FAINT));
        else out.add(new Line("Next: 1) winner pays  2) verify your balance, confirm  3) hand over the item, mark delivered.", Ui.FAINT));
        return out;
    }

    @Override
    public void onClose() {
        McCompat.show(parent);
    }
}
