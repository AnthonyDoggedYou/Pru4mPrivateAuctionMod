package dev.donutauction.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Palette and drawing helpers for the dark, card-based look. Colours are ARGB. */
final class Ui {
    static final int BG = 0xF2111822;
    static final int BORDER = 0xFF243044;
    static final int SIDEBAR = 0xFF0C121B;
    static final int CARD = 0xFF172131;
    static final int CARD_HI = 0xFF1F2D44;
    static final int CARD_BORDER = 0xFF2A3A55;
    static final int FIELD = 0xFF0E151F;
    static final int ACCENT = 0xFF3B5BFF;
    static final int ACCENT_HI = 0xFF5873FF;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFF8B98B0;
    static final int FAINT = 0xFF5A6780;
    static final int GREEN = 0xFF3DDC84;
    static final int GREEN_BG = 0xFF1B8A52;
    static final int AMBER = 0xFFFFB020;
    static final int RED = 0xFFFF5C5C;
    static final int RED_BG = 0xFFB83A3A;
    static final int AQUA = 0xFF55E1FF;
    static final int DISABLED_BG = 0xFF141C28;
    static final int DISABLED_FG = 0xFF4A5568;

    private Ui() {}

    static String fit(Font font, String s, int maxWidth) {
        if (s == null) return "";
        if (maxWidth <= 0) return "";
        if (font.width(s) <= maxWidth) return s;
        String t = s;
        while (t.length() > 1 && font.width(t + "\u2026") > maxWidth) t = t.substring(0, t.length() - 1);
        return t + "\u2026";
    }

    static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        g.text(font, s, x, y, color);
    }

    static void textRight(GuiGraphicsExtractor g, Font font, String s, int right, int y, int color) {
        g.text(font, s, right - font.width(s), y, color);
    }

    static void textCenter(GuiGraphicsExtractor g, Font font, String s, int x, int w, int y, int color) {
        g.text(font, s, x + (w - font.width(s)) / 2, y, color);
    }

    /** Greedy word wrap by pixel width. */
    static List<String> wrap(Font font, String s, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(candidate) > maxWidth && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** A filled rectangle with truly rounded corners (pixel-accurate circle arcs). */
    static void round(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
        if (r == 0) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        g.fill(x1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            double d = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * (double) r - d * d));
            g.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            g.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }
    }

    static void roundBorder(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, int fill, int border) {
        round(g, x1, y1, x2, y2, r, border);
        round(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(0, r - 1), fill);
    }

    static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** A small status label. Returns its width. */
    static int pill(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        int w = font.width(text) + 10;
        round(g, x, y, x + w, y + 12, 5, withAlpha(color, 0x40));
        g.text(font, text, x + 5, y + 2, color);
        return w;
    }
}
