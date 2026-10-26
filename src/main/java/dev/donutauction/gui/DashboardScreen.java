package dev.donutauction.gui;

import dev.donutauction.DonutAuctionClient;
import dev.donutauction.DonutAuctionClient.ChatLogLine;
import dev.donutauction.core.Auction;
import dev.donutauction.core.Auction.PayStatus;
import dev.donutauction.core.AuctionEngine;
import dev.donutauction.core.Bid;
import dev.donutauction.core.Fmt;
import dev.donutauction.core.Money;
import dev.donutauction.core.PaymentRecord;
import dev.donutauction.core.PaymentRecord.PayState;
import dev.donutauction.mc.ItemIcons;
import dev.donutauction.mc.McCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main window: a sidebar of sections and a card grid of auctions, like a modern settings hub. */
public final class DashboardScreen extends ModernScreen {
    private enum Tab {
        ACTIVE("Active"), SETTLE("To settle"), SOLD("Sold"), UNSOLD("Unsold"),
        PAYMENTS("Payments"), HISTORY("History"), CHAT("Chat log");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int CARD_H = 64;
    private static final int GAP = 8;
    private static final int ROW_H = 30;
    private static Tab remembered = Tab.ACTIVE;

    private Tab tab = remembered;
    private int page;
    private final Armer armer = new Armer();
    private List<Auction> auctions = new ArrayList<>();
    private List<PaymentRecord> payments = new ArrayList<>();
    private List<ChatLogLine> chat = new ArrayList<>();
    private int sbW, cx0, cx1, top, bottom, gridTop, gridBottom, cols, cardW, perPage;
    private String sig = "";

    public DashboardScreen() {
        super("Donut Auction");
    }

    // ------------------------------------------------------------------ data

    private void load() {
        AuctionEngine e = DonutAuctionClient.engine();
        auctions = new ArrayList<>();
        payments = new ArrayList<>();
        chat = new ArrayList<>();
        switch (tab) {
            case ACTIVE:
                auctions = e.running();
                auctions.sort((a, b) -> Long.compare(a.endsAt, b.endsAt));
                break;
            case SETTLE: auctions = e.toSettle(); break;
            case SOLD: auctions = e.completed(); break;
            case UNSOLD: auctions = e.unsold(); break;
            case HISTORY: auctions = e.all(); break;
            case PAYMENTS: payments = e.payments(); break;
            default: chat = DonutAuctionClient.chatLog(); break;
        }
    }

    private int total() {
        return tab == Tab.PAYMENTS ? payments.size() : tab == Tab.CHAT ? chat.size() : auctions.size();
    }

    private String badge(Tab t) {
        AuctionEngine e = DonutAuctionClient.engine();
        int n;
        switch (t) {
            case ACTIVE: n = e.running().size(); break;
            case SETTLE: n = e.toSettle().size(); break;
            case SOLD: n = e.completed().size(); break;
            case UNSOLD: n = e.unsold().size(); break;
            case PAYMENTS:
                n = 0;
                for (PaymentRecord p : e.payments()) if (p.state == PayState.DETECTED || p.state == PayState.UNMATCHED) n++;
                break;
            case HISTORY: n = e.all().size(); break;
            default: n = 0;
        }
        return n == 0 ? "" : Integer.toString(n);
    }

    private String signature() {
        AuctionEngine e = DonutAuctionClient.engine();
        StringBuilder sb = new StringBuilder(tab.name()).append(page);
        for (Tab t : Tab.values()) sb.append(badge(t)).append(',');
        if (tab == Tab.PAYMENTS) {
            for (PaymentRecord p : e.payments()) sb.append(p.id).append(p.state);
        } else if (tab != Tab.CHAT) {
            List<Auction> list = tab == Tab.ACTIVE ? e.running() : tab == Tab.SETTLE ? e.toSettle()
                    : tab == Tab.SOLD ? e.completed() : tab == Tab.UNSOLD ? e.unsold() : e.all();
            for (Auction a : list) sb.append(a.id).append(a.phase).append(a.payStatus).append(a.delivered);
        }
        return sb.toString();
    }

    private String modeLabel() {
        return "New bids: " + (DonutAuctionClient.settings().defaultPayToBid ? "PAY" : "CHAT");
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        startLayout();
        sbW = Math.min(122, pw / 4);
        cx0 = px + sbW + 12;
        cx1 = px + pw - 12;
        top = py + 12;
        bottom = py + ph - 10;
        gridTop = top + 36;
        gridBottom = bottom - 24;
        load();

        int y = py + 38;
        button("+  New auction", px + 8, y, sbW - 8, 20, Kind.PRIMARY, () -> McCompat.show(new ItemPickerScreen(this)));
        y += 30;
        for (Tab t : Tab.values()) {
            Btn b = button(t.label, px + 8, y, sbW - 8, 18, t == tab ? Kind.NAV_ACTIVE : Kind.NAV, () -> {
                tab = t;
                remembered = t;
                page = 0;
                rebuildWidgets();
            });
            b.badge = badge(t);
            y += 20;
        }
        button(modeLabel(), px + 8, py + ph - 30, sbW - 8, 20, Kind.GHOST, () -> {
            DonutAuctionClient.settings().defaultPayToBid = !DonutAuctionClient.settings().defaultPayToBid;
            DonutAuctionClient.saveSettings();
            rebuildWidgets();
        });
        button("X", cx1 - 18, top, 18, 16, Kind.GHOST, this::onClose);

        int cw = cx1 - cx0;
        if (tab == Tab.PAYMENTS) {
            perPage = Math.max(1, (gridBottom - gridTop) / (ROW_H + 4));
        } else if (tab == Tab.CHAT) {
            perPage = Math.max(1, (gridBottom - gridTop) / 10);
        } else {
            cols = Math.max(1, (cw + GAP) / (128 + GAP));
            cardW = (cw - GAP * (cols - 1)) / cols;
            int rows = Math.max(1, (gridBottom - gridTop + GAP) / (CARD_H + GAP));
            perPage = cols * rows;
        }
        int total = total();
        int maxPage = Math.max(0, (total - 1) / perPage);
        if (page > maxPage) page = maxPage;
        int from = page * perPage;
        int to = Math.min(total, from + perPage);

        if (tab == Tab.PAYMENTS) {
            for (int r = from; r < to; r++) {
                PaymentRecord p = payments.get(r);
                int ry = gridTop + (r - from) * (ROW_H + 4);
                int bx = cx1 - 58;
                if (p.state == PayState.DETECTED || p.state == PayState.UNMATCHED) {
                    button("Dismiss", bx, ry + 5, 54, 20, Kind.GHOST, () -> {
                        DonutAuctionClient.say(DonutAuctionClient.engine().rejectPayment(p.id).message, 0xAAAAAA);
                        rebuildWidgets();
                    });
                    bx -= 62;
                }
                if (p.state == PayState.DETECTED) {
                    String key = "pay" + p.id;
                    boolean armed = armer.isArmed(key, System.currentTimeMillis());
                    button(armed ? "Sure?" : "Confirm", bx, ry + 5, 58, 20, Kind.SUCCESS, () -> {
                        if (armer.fire(key, System.currentTimeMillis())) {
                            AuctionEngine.Result res = DonutAuctionClient.engine().confirmPayment(p.id);
                            DonutAuctionClient.say(res.message, res.ok ? 0x55FF55 : 0xFF5555);
                        }
                        rebuildWidgets();
                    });
                }
            }
        } else if (tab == Tab.CHAT) {
            button("Copy lines", cx1 - 100, top + 20, 82, 16, Kind.GHOST, () -> {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (ChatLogLine l : chat) {
                    if (n++ >= 40) break;
                    sb.append(l.system ? "[system] " : "[chat] ").append(l.text).append('\n');
                }
                McCompat.copyToClipboard(sb.toString());
                DonutAuctionClient.say("Copied the last " + Math.min(40, chat.size()) + " chat lines.", 0xAAAAAA);
            });
        } else {
            for (int r = from; r < to; r++) {
                Auction a = auctions.get(r);
                int i = r - from;
                int x = cx0 + (i % cols) * (cardW + GAP);
                int cy = gridTop + (i / cols) * (CARD_H + GAP);
                hit(x, cy, cardW, CARD_H, () -> McCompat.show(new AuctionDetailScreen(a.id, this)));
            }
        }
        if (total > perPage && tab != Tab.CHAT) {
            button("<", cx0, bottom - 18, 26, 18, Kind.GHOST, () -> {
                page = Math.max(0, page - 1);
                rebuildWidgets();
            });
            button(">", cx0 + 30, bottom - 18, 26, 18, Kind.GHOST, () -> {
                page++;
                rebuildWidgets();
            });
        }
        sig = signature();
    }

    @Override
    public void tick() {
        if (armer.expire(System.currentTimeMillis()) || !signature().equals(sig)) rebuildWidgets();
        else if (tab == Tab.CHAT && DonutAuctionClient.chatLog().size() != chat.size()) rebuildWidgets();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawChrome(GuiGraphicsExtractor g) {
        super.drawChrome(g);
        Ui.round(g, px + 4, py + 4, px + sbW, py + ph - 4, 6, Ui.SIDEBAR);
    }

    private static final class Strip {
        final String text;
        final int color;

        Strip(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private Strip strip(Auction a, long now) {
        AuctionEngine e = DonutAuctionClient.engine();
        switch (a.phase) {
            case RUNNING:
                long rem = a.remainingMs(now);
                return new Strip("LIVE  " + Fmt.duration(rem), rem < 10_000 ? 0xFFD98A00 : Ui.ACCENT);
            case ENDED:
                if (!e.refundsOwed(a).isEmpty() && (a.isCompleted() || a.payStatus == PayStatus.CONFIRMED)) {
                    return new Strip("REFUNDS OWED", 0xFFB07A00);
                }
                if (a.isCompleted()) return new Strip("COMPLETE", Ui.GREEN_BG);
                if (a.payStatus == PayStatus.CONFIRMED) return new Strip("DELIVER THE ITEM", Ui.GREEN_BG);
                if (a.payStatus == PayStatus.DETECTED) return new Strip("VERIFY PAYMENT", 0xFFB07A00);
                if (a.payStatus == PayStatus.PARTIAL) return new Strip("PAYMENT SHORT", Ui.RED_BG);
                return new Strip("AWAITING PAYMENT", 0xFF39455C);
            case NO_SALE:
                if (a.closeNote.contains("paid")) return new Strip("SOMEONE PAID - OPEN", 0xFFB07A00);
                return new Strip("NO SALE", 0xFF39455C);
            default:
                return e.refundsOwed(a).isEmpty() ? new Strip("CANCELLED", 0xFF39455C) : new Strip("REFUNDS OWED", 0xFFB07A00);
        }
    }

    private String subtitle(Auction a) {
        String mode = a.payToBid ? "  pay" : "";
        switch (a.phase) {
            case RUNNING: {
                Bid h = a.highest();
                return h == null ? "No bids  start " + Money.format(a.startingBid) + mode
                        : Money.format(h.amount) + "  " + h.bidder + mode;
            }
            case ENDED:
                return a.winner + "  " + Money.format(a.finalBid) + mode;
            default:
                return "start " + Money.format(a.startingBid) + mode;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        AuctionEngine e = DonutAuctionClient.engine();
        long now = System.currentTimeMillis();

        Ui.text(g, this.font, "DONUT", px + 12, py + 14, Ui.ACCENT);
        Ui.text(g, this.font, "AUCTION", px + 12 + this.font.width("DONUT "), py + 14, Ui.TEXT);
        Ui.text(g, this.font, "hosting on DonutSMP", px + 12, py + 25, Ui.FAINT);

        Ui.text(g, this.font, tabTitle(), cx0, top + 2, Ui.TEXT);
        Ui.text(g, this.font, Ui.fit(this.font, tabHelp(e), cx1 - cx0 - 30), cx0, top + 14, Ui.MUTED);

        int total = total();
        int from = page * perPage;
        int to = Math.min(total, from + perPage);
        if (total == 0) {
            Ui.roundBorder(g, cx0, gridTop, cx1, gridTop + 44, 6, Ui.CARD, Ui.CARD_BORDER);
            Ui.text(g, this.font, emptyText(), cx0 + 12, gridTop + 17, Ui.MUTED);
        }

        if (tab == Tab.PAYMENTS) {
            drawPayments(g, from, to);
        } else if (tab == Tab.CHAT) {
            drawChat(g, from, to);
        } else {
            for (int r = from; r < to; r++) {
                Auction a = e.byId(auctions.get(r).id);
                if (a == null) continue;
                int i = r - from;
                int x = cx0 + (i % cols) * (cardW + GAP);
                int cy = gridTop + (i / cols) * (CARD_H + GAP);
                drawCard(g, a, x, cy, over(mx, my, x, cy, cardW, CARD_H), now);
            }
        }
        if (total > perPage && tab != Tab.CHAT) {
            Ui.text(g, this.font, "Page " + (page + 1) + " of " + ((total - 1) / perPage + 1), cx0 + 62, bottom - 14, Ui.MUTED);
        }
        drawButtons(g, mx, my);
    }

    private void drawCard(GuiGraphicsExtractor g, Auction a, int x, int y, boolean hover, long now) {
        Ui.roundBorder(g, x, y, x + cardW, y + CARD_H, 6, hover ? Ui.CARD_HI : Ui.CARD, hover ? Ui.ACCENT : Ui.CARD_BORDER);
        Ui.round(g, x + 7, y + 7, x + 35, y + 35, 5, Ui.FIELD);
        ItemStack icon = ItemIcons.iconFor(a.item.itemId, a.item.count);
        if (!icon.isEmpty()) {
            g.item(icon, x + 13, y + 13);
            g.itemDecorations(this.font, icon, x + 13, y + 13);
        }
        int tx = x + 41;
        int tw = cardW - 47;
        Ui.text(g, this.font, Ui.fit(this.font, a.item.displayName, tw), tx, y + 9, Ui.TEXT);
        Ui.text(g, this.font, Ui.fit(this.font, "#" + a.id + "  x" + a.item.count, tw), tx, y + 20, Ui.FAINT);
        Ui.text(g, this.font, Ui.fit(this.font, subtitle(a), cardW - 14), x + 7, y + 38, Ui.MUTED);
        Strip s = strip(a, now);
        Ui.round(g, x + 1, y + CARD_H - 18, x + cardW - 1, y + CARD_H - 1, 5, s.color);
        g.fill(x + 1, y + CARD_H - 18, x + cardW - 1, y + CARD_H - 13, s.color);
        Ui.textCenter(g, this.font, Ui.fit(this.font, s.text, cardW - 8), x, cardW, y + CARD_H - 14, Ui.TEXT);
    }

    private void drawPayments(GuiGraphicsExtractor g, int from, int to) {
        for (int r = from; r < to; r++) {
            PaymentRecord p = payments.get(r);
            int ry = gridTop + (r - from) * (ROW_H + 4);
            int color;
            String state;
            switch (p.state) {
                case CONFIRMED: color = Ui.GREEN; state = "CONFIRMED"; break;
                case DETECTED: color = Ui.AMBER; state = "DETECTED - needs verification"; break;
                case UNMATCHED: color = Ui.AMBER; state = "UNMATCHED - not verified"; break;
                default: color = Ui.FAINT; state = "DISMISSED"; break;
            }
            Ui.roundBorder(g, cx0, ry, cx1, ry + ROW_H, 6, Ui.CARD, Ui.CARD_BORDER);
            g.fill(cx0 + 1, ry + 6, cx0 + 4, ry + ROW_H - 6, color);
            int textW = cx1 - cx0 - 140;
            String linked = p.auctionId > 0 ? "auction #" + p.auctionId : "no auction";
            Ui.text(g, this.font, Ui.fit(this.font, p.payer + "   " + Money.format(p.amount) + "   " + linked
                    + ("MANUAL".equals(p.source) ? "   (manual)" : ""), textW), cx0 + 12, ry + 6, Ui.TEXT);
            Ui.text(g, this.font, Ui.fit(this.font, state + "   " + Fmt.time(p.detectedAt), textW), cx0 + 12, ry + 17, color);
        }
    }

    private void drawChat(GuiGraphicsExtractor g, int from, int to) {
        for (int r = from; r < to; r++) {
            ChatLogLine l = chat.get(r);
            int y = gridTop + (r - from) * 10;
            String lower = l.text.toLowerCase(Locale.ROOT);
            int color = !l.verdict.isEmpty() ? Ui.GREEN
                    : (lower.contains("bid") || lower.contains("paid") || lower.contains("$")) ? Ui.AMBER : Ui.FAINT;
            String line = l.text + (l.verdict.isEmpty() ? "" : "   -> " + l.verdict);
            Ui.text(g, this.font, Ui.fit(this.font, (l.system ? "SYS " : "CHAT ") + line, cx1 - cx0), cx0, y, color);
        }
    }

    private String tabTitle() {
        switch (tab) {
            case ACTIVE: return "Active auctions";
            case SETTLE: return "To settle";
            case SOLD: return "Sold";
            case UNSOLD: return "Unsold and cancelled";
            case PAYMENTS: return "Payments";
            case HISTORY: return "All auctions";
            default: return "Chat log";
        }
    }

    private String tabHelp(AuctionEngine e) {
        switch (tab) {
            case ACTIVE: return e.running().size() + " running  |  confirmed income " + Money.format(e.confirmedIncome());
            case SETTLE: return "Verify the payment, hand over the item, then it counts as sold.";
            case SOLD: return "Paid and delivered.";
            case UNSOLD: return "No bids, cancelled, or the winner never paid.";
            case PAYMENTS: return "Detected payments are NOT confirmed until you verify them.";
            case HISTORY: return "Every auction you have run.";
            default: return "Lines the mod just saw. Green = read as a bid or payment. Amber = looks related but not read.";
        }
    }

    private String emptyText() {
        switch (tab) {
            case ACTIVE: return "Nothing running. Press \"New auction\" to start one.";
            case SETTLE: return "Nothing is waiting for payment, delivery or a refund.";
            case SOLD: return "No completed sales yet.";
            case UNSOLD: return "No unsold or cancelled auctions.";
            case PAYMENTS: return "No payments seen yet.";
            case HISTORY: return "No auctions yet.";
            default: return "No chat lines seen yet. Join the server and talk in chat.";
        }
    }
}
