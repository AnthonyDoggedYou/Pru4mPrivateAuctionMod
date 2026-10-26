package dev.donutauction.mc;

import com.mojang.blaze3d.platform.ClipboardManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Every Minecraft call whose name or location changed between 1.21.11 and 26.3 lives here, so a future
 * Minecraft update means editing this one file. Names follow Mojang's official (unobfuscated) mappings.
 *
 * 26.2 moved the current screen and HUD methods off Minecraft into Gui and Hud, and made
 * ChatComponent#addMessage private. 26.3 replaced GLFW with SDL; ClipboardManager no longer takes a Window.
 */
public final class McCompat {
    private McCompat() {}

    /** Opens a screen (null closes the current one). Was Minecraft#setScreen before 26.2. */
    public static void show(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** The screen currently open, or null. Was the Minecraft#screen field before 26.2. */
    public static Screen current() {
        return Minecraft.getInstance().gui.screen();
    }

    /** Adds a line to your own chat window only. Nothing is sent to the server. */
    public static void localChat(Component line) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(line);
    }

    /** The action-bar style overlay text. Was Gui#setOverlayMessage before 26.2. */
    public static void overlay(Component text) {
        Minecraft.getInstance().gui.hud.setOverlayMessage(text, false);
    }

    public static void copyToClipboard(String text) {
        new ClipboardManager().setClipboard(text);
    }
}
