package dev.donutauction.core;

import java.util.ArrayList;
import java.util.List;

/** A plain-data snapshot of an item at listing time (name, count, enchantments and other tooltip attributes). */
public final class ItemInfo {
    public String itemId = "";
    public int count = 1;
    public String displayName = "";
    /** The anvil/custom name if the item has one, otherwise null. */
    public String customName;
    public boolean enchanted;
    public int damage;
    public int maxDamage;
    /** Tooltip lines after the name: enchantments, lore, attribute modifiers, etc. */
    public List<String> lines = new ArrayList<>();
    /** Free text the seller adds ("maxed", "from the end city"...). */
    public String notes = "";

    public String summary() {
        return count + "x " + displayName;
    }

    /** One-line description for chat announcements, hard-limited to maxChars. */
    public String shortDescription(int maxChars) {
        StringBuilder sb = new StringBuilder(summary());
        if (customName != null && !customName.equals(displayName)) sb.append(" \"").append(customName).append('"');
        int shown = 0;
        for (String l : lines) {
            if (shown >= 4) break;
            String t = l.trim();
            if (t.isEmpty()) continue;
            sb.append(shown == 0 ? " (" : ", ").append(t);
            shown++;
        }
        if (shown > 0) sb.append(')');
        if (notes != null && !notes.isBlank()) sb.append(" - ").append(notes.trim());
        return Fmt.truncate(sb.toString(), maxChars);
    }
}
