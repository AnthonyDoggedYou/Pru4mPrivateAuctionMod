package dev.donutauction.mc;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry-wide item list for the "All items" browser, plus id -> icon lookup for saved auctions. */
public final class ItemIcons {
    public static final class Entry {
        public final String id;
        public final String name;
        public final ItemStack stack;
        private final String key;

        Entry(String id, ItemStack stack) {
            this.id = id;
            this.stack = stack;
            this.name = stack.getHoverName().getString();
            this.key = (name + " " + id).toLowerCase(Locale.ROOT);
        }

        public boolean matches(String query) {
            return key.contains(query);
        }
    }

    private static List<Entry> all;
    private static Map<String, Entry> byId;

    private ItemIcons() {}

    public static synchronized List<Entry> all() {
        if (all == null) build();
        return all;
    }

    /** A fresh stack for the icon, or ItemStack.EMPTY if the id is unknown. */
    public static synchronized ItemStack iconFor(String itemId, int count) {
        if (all == null) build();
        Entry e = byId.get(itemId);
        if (e == null) return ItemStack.EMPTY;
        ItemStack s = e.stack.copy();
        s.setCount(Math.max(1, Math.min(count, 99)));
        return s;
    }

    private static void build() {
        List<Entry> list = new ArrayList<>();
        Map<String, Entry> map = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            Entry e = new Entry(id, new ItemStack(item));
            list.add(e);
            map.put(id, e);
        }
        list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        all = list;
        byId = map;
    }
}
