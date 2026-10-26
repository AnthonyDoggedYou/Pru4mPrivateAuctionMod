package dev.donutauction.mc;

import dev.donutauction.core.ItemInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Turns an ItemStack into a saveable ItemInfo. Reads only; never modifies the stack or your inventory. */
public final class ItemCapture {
    private ItemCapture() {}

    public static ItemInfo capture(ItemStack stack, String notes) {
        ItemInfo info = new ItemInfo();
        info.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        info.count = stack.getCount();
        info.displayName = stack.getHoverName().getString();
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        info.customName = custom == null ? null : custom.getString();
        info.enchanted = stack.isEnchanted();
        info.damage = stack.getDamageValue();
        info.maxDamage = stack.getMaxDamage();
        info.notes = notes == null ? "" : notes;
        // The tooltip already contains enchantments, lore, attribute modifiers, potion effects etc.
        // for every item type, so we record it generically instead of special-casing components.
        List<Component> tip = stack.getTooltipLines(Item.TooltipContext.EMPTY, Minecraft.getInstance().player, TooltipFlag.NORMAL);
        for (int i = 1; i < tip.size(); i++) {
            String line = tip.get(i).getString();
            if (!line.isBlank()) info.lines.add(line);
        }
        return info;
    }
}
