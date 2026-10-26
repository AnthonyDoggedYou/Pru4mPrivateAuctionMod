package dev.donutauction;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Optional convenience key (default M, rebindable under Controls > Key Binds > Misc). 26.3 switched
 * input from GLFW to SDL and Fabric renamed KeyBindingHelper to KeyMappingHelper. If this file fails to
 * compile after a future Minecraft update, delete it and the two Keybinds.* calls in DonutAuctionClient:
 * the /dauction command does the same job.
 */
final class Keybinds {
    private static KeyMapping open;

    private Keybinds() {}

    static void register() {
        open = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.donutauction.open", InputConstants.KEY_M, KeyMapping.Category.MISC));
    }

    static void tick() {
        if (open == null) return;
        while (open.consumeClick()) DonutAuctionClient.openDashboard();
    }
}
