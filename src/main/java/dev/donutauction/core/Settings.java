package dev.donutauction.core;

import java.util.ArrayList;
import java.util.List;

/** User-editable configuration (config/donutauction.json). Missing fields fall back to these defaults. */
public final class Settings {
    private static final String NAME = "(?<user>\\.?[A-Za-z0-9_]{3,16})";
    private static final String AMOUNT = "\\$?(?<amount>[0-9][0-9.,]*[kKmMbBtT]?)";
    private static final String BID_TAIL = "!?bid\\s+(?:#(?<id>\\d{1,6})\\s+)?" + AMOUNT + "\\s*$";
    private static final String RANK = "^(?:\\[[^\\]]{1,32}\\]\\s?)*";

    /** Bumped when the default chat patterns change; older config files get the new patterns. */
    public int configVersion = 2;

    /** Only process chat while connected to a server whose address contains one of these. Empty = any server (for testing). */
    public List<String> restrictToHosts = new ArrayList<>(List.of("donutsmp"));

    public long defaultMinIncrement = 1;
    public int[] durationPresetsSec = {30, 60, 300, 600};
    public long minDurationSec = 10;
    public long maxDurationSec = 24 * 3600;
    /** If > 0, a bid placed in the last N seconds pushes the end time out to N seconds from that bid. 0 = off. */
    public long antiSnipeSeconds = 0;
    public long maxBid = 1_000_000_000_000_000L;

    public boolean requirePaymentBeforeDelivery = true;
    public boolean autoBlockOnForfeit = true;
    /** Only trust payment lines that arrived as system messages, never ones typed by players. */
    public boolean paymentFromGameChannelOnly = true;
    public boolean showRejectedBids = true;
    public boolean actionBarStatus = true;
    public boolean debug = false;
    /** New auctions start in pay-to-bid mode (a /pay counts as a bid) instead of chat-bid mode. */
    public boolean defaultPayToBid = false;
    /** If the strict patterns miss, try a looser reader for lines like "Name > bid 5m". */
    public boolean smartParsing = true;
    public int[] reminderSeconds = {60, 30, 10, 5};
    public int maxRejectedLogged = 100;

    /**
     * Regexes with named groups: user, amount, and optionally id. Each is matched against the whole
     * (formatting-stripped) chat line. They are anchored at the start on purpose: a player typing
     * "Bob » bid 100m" produces "Mallory » Bob » bid 100m", which must NOT count as a bid by Bob.
     * Calibrate with /dauction debug on and /dauction parse.
     */
    public List<String> bidPatterns = new ArrayList<>(List.of(
            "(?i)" + RANK + NAME + "\\s?(?:\u00bb|>>|:|>)\\s?" + BID_TAIL,
            "(?i)^\\[" + NAME + "\\s*(?:->|\u2192|\u00bb)\\s*(?:me|you)\\]\\s*" + BID_TAIL,
            "(?i)" + RANK + NAME + "\\s+(?:whispers|messages|tells you)(?: to you)?\\s*[:\u00bb]\\s*" + BID_TAIL));

    public List<String> paymentPatterns = new ArrayList<>(List.of(
            "(?i)" + RANK + NAME + "\\s+(?:has\\s+)?(?:paid|payed|sent|gave|transferred)\\s+you\\s+" + AMOUNT + "(?![0-9A-Za-z]).*$",
            "(?i)" + RANK + "You\\s+(?:have\\s+)?(?:received|got)\\s+" + AMOUNT + "\\s+from\\s+" + NAME + "\\b.*$"));
}
