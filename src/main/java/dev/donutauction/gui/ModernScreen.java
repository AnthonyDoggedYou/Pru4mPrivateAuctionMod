package dev.donutauction.gui;

import dev.donutauction.mc.McCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Shared look for every screen. Clickable things are invisible vanilla buttons (alpha 0) that only catch
 * clicks; what you see is drawn by this class. That gives rounded, modern controls without overriding any
 * mouse or keyboard handlers.
 */
abstract class ModernScreen extends Screen {
    enum Kind { PRIMARY, GHOST, DANGER, SUCCESS, NAV, NAV_ACTIVE }

    static final class Btn {
        int x, y, w, h;
        String label = "";
        String badge = "";
        Kind kind = Kind.GHOST;
        boolean enabled = true;
        BooleanSupplier selected;
        Button hit;
    }

    static final class Field {
        final EditBox box;
        final int x, y, w, h;

        Field(EditBox box, int x, int y, int w, int h) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    protected final List<Btn> btns = new ArrayList<>();
    protected final List<Field> fields = new ArrayList<>();
    protected int px, py, pw, ph;

    protected ModernScreen(String title) {
        super(Component.literal(title));
    }

    /** Call first in init(). */
    protected void startLayout() {
        btns.clear();
        fields.clear();
        px = 8;
        py = 8;
        pw = this.width - 16;
        ph = this.height - 16;
    }

    protected Btn button(String label, int x, int y, int w, int h, Kind kind, Runnable action) {
        Btn b = new Btn();
        b.x = x;
        b.y = y;
        b.w = w;
        b.h = h;
        b.label = label;
        b.kind = kind;
        b.hit = hit(x, y, w, h, action);
        btns.add(b);
        return b;
    }

    /** An invisible click area with no drawing of its own. */
    protected Button hit(int x, int y, int w, int h, Runnable action) {
        Button hit = Button.builder(Component.empty(), bt -> action.run()).bounds(x, y, w, h).build();
        hit.setAlpha(0.0f);
        return addRenderableWidget(hit);
    }

    protected void enable(Btn b, boolean on) {
        b.enabled = on;
        b.hit.active = on;
    }

    protected EditBox field(int x, int y, int w, int h, String hint, int maxLength, String value) {
        EditBox box = new EditBox(this.font, x + 6, y + (h - 10) / 2, w - 12, 12, Component.literal(hint));
        box.setBordered(false);
        box.setHint(Component.literal(hint));
        box.setMaxLength(maxLength);
        if (value != null) box.setValue(value);
        addRenderableWidget(box);
        fields.add(new Field(box, x, y, w, h));
        return box;
    }

    protected static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Panel background and text-field backgrounds. Drawn behind the widgets. */
    protected void drawChrome(GuiGraphicsExtractor g) {
        Ui.roundBorder(g, px, py, px + pw, py + ph, 8, Ui.BG, Ui.BORDER);
        for (Field f : fields) {
            Ui.roundBorder(g, f.x, f.y, f.x + f.w, f.y + f.h, 4, Ui.FIELD, f.box.isFocused() ? Ui.ACCENT : Ui.CARD_BORDER);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractBackground(g, mx, my, delta);
        drawChrome(g);
    }

    protected void drawButtons(GuiGraphicsExtractor g, int mx, int my) {
        for (Btn b : btns) {
            boolean hover = b.enabled && over(mx, my, b.x, b.y, b.w, b.h);
            Kind kind = b.selected != null && b.selected.getAsBoolean() ? Kind.NAV_ACTIVE : b.kind;
            int bg;
            int fg = Ui.TEXT;
            int border = 0;
            switch (kind) {
                case PRIMARY:
                case NAV_ACTIVE:
                    bg = hover ? Ui.ACCENT_HI : Ui.ACCENT;
                    break;
                case SUCCESS:
                    bg = hover ? 0xFF22A862 : Ui.GREEN_BG;
                    break;
                case DANGER:
                    bg = hover ? 0xFFD04848 : Ui.RED_BG;
                    break;
                case NAV:
                    bg = hover ? Ui.CARD : 0x00000000;
                    fg = hover ? Ui.TEXT : Ui.MUTED;
                    break;
                default:
                    bg = hover ? Ui.CARD_HI : Ui.CARD;
                    border = Ui.CARD_BORDER;
                    break;
            }
            if (!b.enabled) {
                bg = Ui.DISABLED_BG;
                fg = Ui.DISABLED_FG;
                border = 0;
            }
            if (border != 0) Ui.roundBorder(g, b.x, b.y, b.x + b.w, b.y + b.h, 4, bg, border);
            else if ((bg >>> 24) != 0) Ui.round(g, b.x, b.y, b.x + b.w, b.y + b.h, 4, bg);
            int ty = b.y + (b.h - 8) / 2;
            boolean nav = kind == Kind.NAV || kind == Kind.NAV_ACTIVE;
            if (nav) {
                Ui.text(g, this.font, Ui.fit(this.font, b.label, b.w - 16 - this.font.width(b.badge)), b.x + 8, ty, fg);
                if (!b.badge.isEmpty()) Ui.textRight(g, this.font, b.badge, b.x + b.w - 7, ty, kind == Kind.NAV_ACTIVE ? Ui.TEXT : Ui.FAINT);
            } else {
                String label = Ui.fit(this.font, b.label, b.w - 8);
                Ui.textCenter(g, this.font, label, b.x, b.w, ty, fg);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
