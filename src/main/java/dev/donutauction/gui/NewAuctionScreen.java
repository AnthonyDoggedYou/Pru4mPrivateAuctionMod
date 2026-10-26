package dev.donutauction.gui;

import dev.donutauction.DonutAuctionClient;
import dev.donutauction.core.Announcer;
import dev.donutauction.core.AuctionEngine.Result;
import dev.donutauction.core.Fmt;
import dev.donutauction.core.ItemInfo;
import dev.donutauction.core.Money;
import dev.donutauction.core.Settings;
import dev.donutauction.mc.ItemIcons;
import dev.donutauction.mc.McCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.OptionalLong;

/** Step 2 of a new auction: price, raise, duration, how people bid, and notes. */
public final class NewAuctionScreen extends ModernScreen {
    private final ItemInfo item;
    private final Screen parent;
    private String startText;
    private String incText;
    private String customText = "";
    private String notesText;
    private long selectedMs;
    private boolean payToBid;
    private String error = "";
    private int fx, fw, y0;

    public NewAuctionScreen(ItemInfo item, Screen parent) {
        this(item, parent, 0);
    }

    public NewAuctionScreen(ItemInfo item, Screen parent, long prefillStart) {
        super("New auction");
        this.item = item;
        this.parent = parent;
        Settings cfg = DonutAuctionClient.settings();
        this.startText = prefillStart > 0 ? Money.format(prefillStart) : "";
        this.incText = Money.format(Math.max(1, cfg.defaultMinIncrement));
        this.notesText = item.notes == null ? "" : item.notes;
        this.payToBid = cfg.defaultPayToBid;
        int[] presets = cfg.durationPresetsSec;
        this.selectedMs = presets.length == 0 ? 60_000L : presets[Math.min(1, presets.length - 1)] * 1000L;
    }

    private static String label(int seconds) {
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    @Override
    protected void init() {
        startLayout();
        fw = Math.min(330, pw - 28);
        fx = px + (pw - fw) / 2;
        y0 = py + 12;

        button("< Back", px + 10, py + 10, 52, 18, Kind.GHOST, this::onClose);
        button("Start auction", px + pw - 134, py + 9, 124, 20, Kind.PRIMARY, this::start);

        int half = (fw - 8) / 2;
        field(fx, y0 + 70, half, 20, "e.g. 500k or 2.5m", 20, startText).setResponder(s -> startText = s);
        field(fx + half + 8, y0 + 70, half, 20, "minimum raise", 20, incText).setResponder(s -> incText = s);

        int[] presets = DonutAuctionClient.settings().durationPresetsSec;
        int bw = Math.min(54, (fw - 104) / Math.max(1, presets.length) - 4);
        int x = fx;
        for (int sec : presets) {
            final long ms = sec * 1000L;
            Btn b = button(label(sec), x, y0 + 106, bw, 20, Kind.GHOST, () -> {
                selectedMs = ms;
                customText = "";
                rebuildWidgets();
            });
            b.selected = () -> selectedMs == ms && customText.isBlank();
            x += bw + 4;
        }
        field(fx + fw - 96, y0 + 106, 96, 20, "custom: 90, 2m", 8, customText).setResponder(s -> customText = s);

        Btn chat = button("Chat bids", fx, y0 + 142, (fw - 4) / 2, 20, Kind.GHOST, () -> payToBid = false);
        chat.selected = () -> !payToBid;
        Btn pay = button("Pay-to-bid", fx + (fw - 4) / 2 + 4, y0 + 142, (fw - 4) / 2, 20, Kind.GHOST, () -> payToBid = true);
        pay.selected = () -> payToBid;

        field(fx, y0 + 188, fw, 20, "Optional notes (e.g. maxed, mint condition)", 80, notesText).setResponder(s -> notesText = s);
    }

    private void start() {
        OptionalLong startBid = Money.parse(startText);
        if (startBid.isEmpty()) {
            error = "Starting bid is not a valid amount (try 500k or 2.5m).";
            return;
        }
        OptionalLong inc = Money.parse(incText);
        if (inc.isEmpty()) {
            error = "Minimum raise is not a valid amount.";
            return;
        }
        long durationMs = selectedMs;
        if (!customText.isBlank()) {
            OptionalLong d = Fmt.parseDurationMs(customText);
            if (d.isEmpty()) {
                error = "Custom duration must look like 90, 90s, 2m or 1h.";
                return;
            }
            durationMs = d.getAsLong();
        }
        item.notes = notesText.trim();
        Result r = DonutAuctionClient.engine().createAuction(item, DonutAuctionClient.selfName(), startBid.getAsLong(),
                durationMs, inc.getAsLong(), payToBid);
        if (!r.ok) {
            error = r.message;
            return;
        }
        DonutAuctionClient.say(r.message, 0x55FF55);
        DonutAuctionClient.copyToClipboard(Announcer.start(DonutAuctionClient.engine().byId(r.auctionId)));
        DonutAuctionClient.say("Announcement copied. In chat press Ctrl+V and Enter to post it yourself.", 0xAAAAAA);
        McCompat.show(new AuctionDetailScreen(r.auctionId, new DashboardScreen()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        Ui.text(g, this.font, "New auction", px + 72, py + 11, Ui.TEXT);
        Ui.text(g, this.font, "Step 2 of 2: set the price and how people bid", px + 72, py + 22, Ui.MUTED);

        Ui.roundBorder(g, fx, y0 + 28, fx + fw, y0 + 28 + 32, 6, Ui.CARD, Ui.CARD_BORDER);
        ItemStack icon = ItemIcons.iconFor(item.itemId, item.count);
        if (!icon.isEmpty()) {
            g.item(icon, fx + 8, y0 + 36);
            g.itemDecorations(this.font, icon, fx + 8, y0 + 36);
        }
        Ui.text(g, this.font, Ui.fit(this.font, item.summary(), fw - 40), fx + 32, y0 + 33, Ui.TEXT);
        String detail = item.customName != null ? "named " + item.customName : String.join(", ", item.lines);
        if (detail.isEmpty()) detail = item.itemId;
        Ui.text(g, this.font, Ui.fit(this.font, detail, fw - 40), fx + 32, y0 + 45, item.enchanted ? Ui.AQUA : Ui.MUTED);

        int half = (fw - 8) / 2;
        Ui.text(g, this.font, "Starting bid", fx, y0 + 60, Ui.MUTED);
        Ui.text(g, this.font, "Minimum raise", fx + half + 8, y0 + 60, Ui.MUTED);
        Ui.text(g, this.font, "Duration", fx, y0 + 96, Ui.MUTED);
        Ui.text(g, this.font, "How do people bid?", fx, y0 + 132, Ui.MUTED);
        Ui.text(g, this.font, Ui.fit(this.font, payToBid
                ? "A /pay to you counts as a bid. Highest total wins; losers get a refund list."
                : "Players type: bid #id amount in chat. Payment happens after the auction.", fw), fx, y0 + 166, Ui.FAINT);
        Ui.text(g, this.font, "Notes", fx, y0 + 178, Ui.MUTED);
        if (!error.isEmpty()) Ui.text(g, this.font, Ui.fit(this.font, error, fw), fx, y0 + 213, Ui.RED);
        drawButtons(g, mx, my);
    }

    @Override
    public void onClose() {
        McCompat.show(parent);
    }
}
