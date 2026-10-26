package dev.donutauction.gui;

import dev.donutauction.core.ItemInfo;
import dev.donutauction.mc.ItemCapture;
import dev.donutauction.mc.ItemIcons;
import dev.donutauction.mc.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 1 of a new auction. "My inventory" shows what you carry (read-only: nothing is moved or clicked in
 * your inventory). "All items" lists every item in the game, for things you do not have on you right now.
 */
public final class ItemPickerScreen extends ModernScreen {
    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int CELL = 24;
    private static final int PAGE = COLS * ROWS;

    private final Screen parent;
    private boolean inventoryMode = true;
    private int page;
    private String searchText = "";
    private String countText = "1";
    private String message = "";

    private final List<ItemStack> invStacks = new ArrayList<>();
    private final List<ItemInfo> invInfos = new ArrayList<>();
    private List<ItemIcons.Entry> filtered = new ArrayList<>();
    private final ItemStack[] cellStack = new ItemStack[PAGE];
    private final int[] cellRef = new int[PAGE];
    private ItemStack selStack = ItemStack.EMPTY;
    private ItemInfo selInfo;
    private int selCell = -1;
    private int gridX, gridY, detailX;

    public ItemPickerScreen(Screen parent) {
        super("Choose an item");
        this.parent = parent;
        for (int i = 0; i < PAGE; i++) {
            cellStack[i] = ItemStack.EMPTY;
            cellRef[i] = -1;
        }
    }

    @Override
    protected void init() {
        startLayout();
        loadInventory();
        gridX = px + 14;
        gridY = py + 66;
        detailX = gridX + COLS * CELL + 12;

        button("< Back", px + 10, py + 10, 52, 18, Kind.GHOST, this::onClose);
        Btn tabInv = button("My inventory", gridX, py + 38, 96, 18, Kind.GHOST, () -> setMode(true));
        tabInv.selected = () -> inventoryMode;
        Btn tabAll = button("All items", gridX + 100, py + 38, 80, 18, Kind.GHOST, () -> setMode(false));
        tabAll.selected = () -> !inventoryMode;

        if (!inventoryMode) {
            EditBox search = field(gridX + 186, py + 38, COLS * CELL - 186, 18, "Search: elytra, dragon, spawner", 40, searchText);
            search.setResponder(s -> {
                searchText = s;
                page = 0;
                applyFilter();
                refreshCells();
            });
        }
        for (int i = 0; i < PAGE; i++) {
            final int idx = i;
            hit(gridX + (i % COLS) * CELL, gridY + (i / COLS) * CELL, CELL - 2, CELL - 2, () -> select(idx));
        }
        int footY = gridY + ROWS * CELL + 6;
        button("<", gridX, footY, 26, 18, Kind.GHOST, () -> {
            page = Math.max(0, page - 1);
            refreshCells();
        });
        button(">", gridX + 30, footY, 26, 18, Kind.GHOST, () -> {
            page++;
            refreshCells();
        });
        if (!inventoryMode) {
            EditBox count = field(gridX + 130, footY, 60, 18, "count", 6, countText);
            count.setResponder(s -> countText = s);
        }
        button("Use this item", gridX, footY + 26, 130, 20, Kind.PRIMARY, this::useItem);
        applyFilter();
        refreshCells();
    }

    private void loadInventory() {
        invStacks.clear();
        invInfos.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            invStacks.add(s.copy());
            invInfos.add(ItemCapture.capture(s, ""));
        }
    }

    private void setMode(boolean inventory) {
        inventoryMode = inventory;
        page = 0;
        selCell = -1;
        selStack = ItemStack.EMPTY;
        selInfo = null;
        message = "";
        rebuildWidgets();
    }

    private void applyFilter() {
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        List<ItemIcons.Entry> out = new ArrayList<>();
        if (!inventoryMode) for (ItemIcons.Entry e : ItemIcons.all()) if (q.isEmpty() || e.matches(q)) out.add(e);
        filtered = out;
    }

    private int totalItems() {
        return inventoryMode ? invStacks.size() : filtered.size();
    }

    private void refreshCells() {
        int total = totalItems();
        int maxPage = Math.max(0, (total - 1) / PAGE);
        if (page > maxPage) page = maxPage;
        for (int i = 0; i < PAGE; i++) {
            int ref = page * PAGE + i;
            if (ref < total) {
                cellRef[i] = ref;
                cellStack[i] = inventoryMode ? invStacks.get(ref) : filtered.get(ref).stack;
            } else {
                cellRef[i] = -1;
                cellStack[i] = ItemStack.EMPTY;
            }
        }
        if (selCell >= 0 && cellRef[selCell] < 0) selCell = -1;
    }

    private void select(int cell) {
        if (cellRef[cell] < 0) return;
        selCell = cell;
        selStack = cellStack[cell];
        selInfo = inventoryMode ? invInfos.get(cellRef[cell]) : ItemCapture.capture(selStack, "");
        message = "";
    }

    private void useItem() {
        if (selInfo == null) {
            message = "Click an item first.";
            return;
        }
        ItemInfo info = ItemCapture.capture(selStack, "");
        if (!inventoryMode) {
            int count;
            try {
                count = Integer.parseInt(countText.trim());
            } catch (NumberFormatException e) {
                message = "Count must be a whole number.";
                return;
            }
            if (count < 1 || count > 100000) {
                message = "Count must be between 1 and 100000.";
                return;
            }
            info.count = count;
        }
        McCompat.show(new NewAuctionScreen(info, this));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        Ui.text(g, this.font, "New auction", px + 72, py + 11, Ui.TEXT);
        Ui.text(g, this.font, "Step 1 of 2: choose the item to sell", px + 72, py + 22, Ui.MUTED);

        Ui.round(g, gridX - 4, gridY - 4, gridX + COLS * CELL + 2, gridY + ROWS * CELL + 2, 6, Ui.SIDEBAR);
        for (int i = 0; i < PAGE; i++) {
            int x = gridX + (i % COLS) * CELL;
            int y = gridY + (i / COLS) * CELL;
            boolean has = cellRef[i] >= 0;
            boolean hover = has && over(mx, my, x, y, CELL - 2, CELL - 2);
            Ui.round(g, x, y, x + CELL - 2, y + CELL - 2, 4, i == selCell ? Ui.ACCENT : hover ? Ui.CARD_HI : Ui.CARD);
            if (has && !cellStack[i].isEmpty()) {
                g.item(cellStack[i], x + 3, y + 3);
                g.itemDecorations(this.font, cellStack[i], x + 3, y + 3);
            }
        }
        int footY = gridY + ROWS * CELL + 6;
        Ui.text(g, this.font, "Page " + (page + 1) + "  |  " + totalItems() + " items", gridX + 62, footY + 5, Ui.MUTED);
        if (!inventoryMode) Ui.text(g, this.font, "Count", gridX + 130, footY - 9, Ui.FAINT);
        if (!message.isEmpty()) Ui.text(g, this.font, message, gridX + 136, footY + 32, Ui.RED);

        int dw = px + pw - 14 - detailX;
        if (dw > 70) {
            Ui.roundBorder(g, detailX, gridY - 4, detailX + dw, py + ph - 12, 6, Ui.CARD, Ui.CARD_BORDER);
            int x = detailX + 10;
            if (selInfo == null) {
                Ui.text(g, this.font, "Click an item to see its details.", x, gridY + 6, Ui.MUTED);
            } else {
                Ui.round(g, x, gridY + 4, x + 28, gridY + 32, 5, Ui.FIELD);
                g.item(selStack, x + 6, gridY + 10);
                Ui.text(g, this.font, Ui.fit(this.font, selInfo.displayName, dw - 56), x + 36, gridY + 8, Ui.TEXT);
                Ui.text(g, this.font, Ui.fit(this.font, selInfo.itemId, dw - 56), x + 36, gridY + 19, Ui.FAINT);
                int y = gridY + 40;
                if (selInfo.customName != null) {
                    Ui.text(g, this.font, Ui.fit(this.font, "Named: " + selInfo.customName, dw - 20), x, y, Ui.AQUA);
                    y += 11;
                }
                Ui.text(g, this.font, inventoryMode ? "Count: " + selInfo.count : "Count: set below", x, y, Ui.MUTED);
                y += 11;
                if (selInfo.maxDamage > 0) {
                    Ui.text(g, this.font, "Durability: " + (selInfo.maxDamage - selInfo.damage) + "/" + selInfo.maxDamage, x, y, Ui.MUTED);
                    y += 11;
                }
                for (String line : selInfo.lines) {
                    if (y > py + ph - 26) break;
                    Ui.text(g, this.font, Ui.fit(this.font, line, dw - 20), x, y, selInfo.enchanted ? Ui.AQUA : Ui.MUTED);
                    y += 10;
                }
            }
        }
        drawButtons(g, mx, my);
    }

    @Override
    public void onClose() {
        McCompat.show(parent);
    }
}
