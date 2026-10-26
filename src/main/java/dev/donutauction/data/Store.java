package dev.donutauction.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.donutauction.core.Auction;
import dev.donutauction.core.ItemInfo;
import dev.donutauction.core.Settings;
import dev.donutauction.core.State;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * JSON persistence in config/donutauction/. Writes go to a temp file first and are then moved into
 * place, so a crash mid-save cannot leave a half-written data file. A file that fails to parse is
 * renamed (never deleted) so your records can be recovered by hand.
 */
public final class Store {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;

    public Store(Path dir) {
        this.dir = dir;
    }

    public Settings loadSettings() {
        Path file = dir.resolve("settings.json");
        Settings s = null;
        if (Files.exists(file)) {
            try {
                s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
            } catch (IOException | JsonParseException e) {
                quarantine(file);
            }
        }
        boolean fresh = s == null;
        if (fresh) s = new Settings();
        Settings defaults = new Settings();
        if (s.restrictToHosts == null) s.restrictToHosts = defaults.restrictToHosts;
        if (s.bidPatterns == null || s.bidPatterns.isEmpty()) s.bidPatterns = defaults.bidPatterns;
        if (s.paymentPatterns == null || s.paymentPatterns.isEmpty()) s.paymentPatterns = defaults.paymentPatterns;
        if (s.durationPresetsSec == null || s.durationPresetsSec.length == 0) s.durationPresetsSec = defaults.durationPresetsSec;
        if (s.reminderSeconds == null) s.reminderSeconds = new int[0];
        boolean upgraded = false;
        if (!fresh && s.configVersion < defaults.configVersion) {
            // Older config files carry the old default chat patterns; give them the improved ones.
            s.bidPatterns = defaults.bidPatterns;
            s.paymentPatterns = defaults.paymentPatterns;
            s.configVersion = defaults.configVersion;
            upgraded = true;
        }
        if (fresh || upgraded) {
            try {
                saveSettings(s);
            } catch (IOException ignored) {
                // Non-fatal: defaults are still in effect.
            }
        }
        return s;
    }

    public void saveSettings(Settings s) throws IOException {
        write(dir.resolve("settings.json"), GSON.toJson(s));
    }

    public State loadState() {
        Path file = dir.resolve("data.json");
        State st = null;
        if (Files.exists(file)) {
            try {
                st = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
            } catch (IOException | JsonParseException e) {
                quarantine(file);
            }
        }
        if (st == null) st = new State();
        sanitize(st);
        return st;
    }

    public void saveState(State st) throws IOException {
        write(dir.resolve("data.json"), GSON.toJson(st));
    }

    private static void sanitize(State st) {
        if (st.auctions == null) st.auctions = new ArrayList<>();
        if (st.payments == null) st.payments = new ArrayList<>();
        if (st.blocked == null) st.blocked = new ArrayList<>();
        for (Auction a : st.auctions) {
            if (a.bids == null) a.bids = new ArrayList<>();
            if (a.rejected == null) a.rejected = new ArrayList<>();
            if (a.forfeitedWinners == null) a.forfeitedWinners = new ArrayList<>();
            if (a.item == null) a.item = new ItemInfo();
            if (a.item.lines == null) a.item.lines = new ArrayList<>();
            if (a.closeNote == null) a.closeNote = "";
            if (a.refunded == null) a.refunded = new HashMap<>();
        }
    }

    private void write(Path target, String json) throws IOException {
        Files.createDirectories(dir);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Nothing more we can do; a fresh file will be written on the next save.
        }
    }
}
