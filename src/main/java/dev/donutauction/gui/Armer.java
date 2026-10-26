package dev.donutauction.gui;

/** Two-click safety: the first click arms an action, a second click within 4 seconds runs it. */
final class Armer {
    private static final long WINDOW_MS = 4000;
    private String key;
    private long until;

    /** @return true if this click should run the action (it was armed by a previous click). */
    boolean fire(String k, long now) {
        if (k.equals(key) && now < until) {
            key = null;
            return true;
        }
        key = k;
        until = now + WINDOW_MS;
        return false;
    }

    boolean isArmed(String k, long now) {
        return k.equals(key) && now < until;
    }

    /** True once if an armed action timed out, so the screen can redraw its labels. */
    boolean expire(long now) {
        if (key != null && now >= until) {
            key = null;
            return true;
        }
        return false;
    }
}
