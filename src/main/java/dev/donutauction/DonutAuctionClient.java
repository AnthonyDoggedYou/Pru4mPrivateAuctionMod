package dev.donutauction;

import dev.donutauction.core.Announcer;
import dev.donutauction.core.Auction;
import dev.donutauction.core.AuctionEngine;
import dev.donutauction.core.Bid;
import dev.donutauction.core.ChatParser;
import dev.donutauction.core.ChatRouter;
import dev.donutauction.core.Fmt;
import dev.donutauction.core.Money;
import dev.donutauction.core.Settings;
import dev.donutauction.core.State;
import dev.donutauction.data.Store;
import dev.donutauction.gui.DashboardScreen;
import dev.donutauction.mc.McCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Client-only entry point. Everything here is read-only with respect to the game: the mod listens to
 * chat, reads your inventory when you pick an item, and shows local messages. It never sends chat or
 * commands, never clicks, and never moves items.
 */
public final class DonutAuctionClient implements ClientModInitializer {
    public static final String MOD_ID = "donutauction";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static Settings settings;
    private static AuctionEngine engine;
    private static ChatParser parser;
    private static ChatRouter router;
    private static Store store;

    /** One received chat line and how the mod classified it. Kept in memory only, never saved or sent anywhere. */
    public static final class ChatLogLine {
        public final long at;
        public final boolean system;
        public final String text;
        public final String verdict;

        ChatLogLine(long at, boolean system, String text, String verdict) {
            this.at = at;
            this.system = system;
            this.text = text;
            this.verdict = verdict;
        }
    }

    private static final int CHAT_LOG_MAX = 150;
    private static final ArrayDeque<ChatLogLine> CHAT_LOG = new ArrayDeque<>();

    private static Supplier<Screen> pendingScreen;
    private static int pendingDelay;
    private static boolean needSave;
    private static long lastSave;
    private static long lastStatus;
    private static boolean saveErrorShown;

    public static Settings settings() { return settings; }

    public static AuctionEngine engine() { return engine; }

    public static ChatParser parser() { return parser; }

    public static ChatRouter router() { return router; }

    public static Store store() { return store; }

    @Override
    public void onInitializeClient() {
        store = new Store(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID));
        settings = store.loadSettings();
        State state = store.loadState();
        engine = new AuctionEngine(state, settings, System::currentTimeMillis);
        parser = new ChatParser(settings);
        for (String err : parser.errors) LOG.warn(err);
        router = new ChatRouter(engine, settings, parser, new ChatRouter.Notifier() {
            @Override public void info(String m) { say(m, 0xAAAAAA); }
            @Override public void good(String m) { say(m, 0x55FF55); }
            @Override public void warn(String m) { say(m, 0xFFAA00); }
        });

        // System messages (most server chat formats) and signed player chat both feed the same router.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onLine(message.getString(), true);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
                onLine(message.getString(), false));

        ClientTickEvents.END_CLIENT_TICK.register(DonutAuctionClient::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveNow());
        Keybinds.register();
        LOG.info("Donut Auction loaded: {} auctions, {} payment records", state.auctions.size(), state.payments.size());
    }

    // ------------------------------------------------------------------ chat

    public static boolean hostAllowed() {
        List<String> hosts = settings.restrictToHosts;
        if (hosts == null || hosts.isEmpty()) return true;
        ServerData sd = Minecraft.getInstance().getCurrentServer();
        if (sd == null || sd.ip == null) return false;
        String ip = sd.ip.toLowerCase(Locale.ROOT);
        for (String h : hosts) if (ip.contains(h.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void onLine(String raw, boolean game) {
        if (!hostAllowed()) return;
        router.setSelfName(selfName());
        logLine(raw, game);
        if (settings.debug) debugEcho(raw, game);
        router.handle(raw, game, System.currentTimeMillis());
    }

    private static void logLine(String raw, boolean game) {
        String text = ChatParser.normalize(raw);
        if (text.isEmpty()) return;
        String verdict = "";
        ChatParser.ParsedPayment pay = parser.parsePayment(text);
        if (pay != null) {
            verdict = "PAYMENT " + pay.user + " " + pay.amountText;
        } else {
            ChatParser.ParsedBid bid = parser.parseBid(text);
            if (bid != null) verdict = "BID " + bid.user + " " + bid.amountText;
        }
        synchronized (CHAT_LOG) {
            CHAT_LOG.addFirst(new ChatLogLine(System.currentTimeMillis(), game, text, verdict));
            while (CHAT_LOG.size() > CHAT_LOG_MAX) CHAT_LOG.removeLast();
        }
    }

    /** Newest first. */
    public static List<ChatLogLine> chatLog() {
        synchronized (CHAT_LOG) {
            return new ArrayList<>(CHAT_LOG);
        }
    }

    public static void saveSettings() {
        try {
            store.saveSettings(settings);
        } catch (IOException e) {
            LOG.error("Could not save settings", e);
        }
    }

    /** Lets you calibrate the regexes: shows how bid/payment-looking lines were classified. */
    private static void debugEcho(String raw, boolean game) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.contains("bid") && !lower.contains("paid")) return;
        ChatParser.ParsedBid b = parser.parseBid(raw);
        ChatParser.ParsedPayment p = parser.parsePayment(raw);
        say("[debug " + (game ? "system" : "chat") + "] bid=" + (b == null ? "no match" : b.user + " " + b.amountText)
                + " payment=" + (p == null ? "no match" : p.user + " " + p.amountText), 0x777777);
    }

    /** Feeds a line through the full pipeline, as if it had arrived from the server (for testing). */
    public static void simulate(String line) {
        router.setSelfName(selfName());
        logLine(line, true);
        boolean recognised = router.handle(line, true, System.currentTimeMillis());
        if (!recognised) say("Not recognised as a bid or payment (or no auction is running): " + line, 0x777777);
    }

    // ------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        if (pendingScreen != null && --pendingDelay <= 0) {
            Supplier<Screen> s = pendingScreen;
            pendingScreen = null;
            McCompat.show(s.get());
        }
        Keybinds.tick();

        long now = System.currentTimeMillis();
        for (Auction a : engine.tick()) {
            say(AuctionEngine.describeClosed(a), 0xFFAA00);
            if (a.phase == Auction.Phase.ENDED) say("Open /dauction to copy the result message and settle.", 0xAAAAAA);
        }
        for (AuctionEngine.Reminder r : engine.dueReminders(settings.reminderSeconds)) {
            Bid h = r.auction.highest();
            say("Auction #" + r.auction.id + ": " + r.secondsLeft + "s left. "
                    + (h == null ? "No bids yet." : "Top bid " + Money.format(h.amount) + " by " + h.bidder + "."), 0xFFAA00);
        }

        if (settings.actionBarStatus && mc.player != null && McCompat.current() == null && now - lastStatus >= 1000) {
            lastStatus = now;
            Auction soonest = null;
            for (Auction a : engine.running()) if (soonest == null || a.endsAt < soonest.endsAt) soonest = a;
            if (soonest != null) {
                Bid h = soonest.highest();
                McCompat.overlay(Component.literal("Auction #" + soonest.id + "  " + Fmt.duration(soonest.remainingMs(now))
                        + "  " + (h == null ? "no bids" : Money.format(h.amount) + " by " + h.bidder)));
            }
        }

        if (engine.consumeDirty()) needSave = true;
        if (needSave && now - lastSave >= 1000) saveNow();
    }

    private static void saveNow() {
        if (engine == null || store == null) return;
        lastSave = System.currentTimeMillis();
        if (engine.consumeDirty()) needSave = true;
        if (!needSave) return;
        try {
            synchronized (engine) {
                store.saveState(engine.state());
            }
            needSave = false;
            saveErrorShown = false;
        } catch (IOException e) {
            LOG.error("Could not save auction data", e);
            if (!saveErrorShown) {
                saveErrorShown = true;
                say("WARNING: could not save auction data (" + e.getMessage() + "). Records are still in memory.", 0xFF5555);
            }
        }
    }

    // ------------------------------------------------------------------ helpers for screens and commands

    public static String selfName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "Seller" : mc.player.getName().getString();
    }

    /** Local-only chat line. Nothing is sent to the server. */
    public static void say(String text, int rgb) {
        if (Minecraft.getInstance().gui == null) return;
        McCompat.localChat(Component.literal("[Auction] " + text).withStyle(style -> style.withColor(rgb)));
    }

    public static void copyToClipboard(String text) {
        McCompat.copyToClipboard(text);
    }

    /** Opens a screen on a later tick so it is not immediately closed by the chat screen shutting. */
    public static void openLater(Supplier<Screen> screen) {
        pendingScreen = screen;
        pendingDelay = 2;
    }

    public static void openDashboard() {
        openLater(DashboardScreen::new);
    }
}
