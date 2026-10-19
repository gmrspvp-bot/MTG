package mtg3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;

/**
 * Remembers the 3D window's size and position between runs, in the user's Java preferences (the
 * Windows registry under HKCU), so nothing is written into the repo.
 *
 * Saved while the window is open (checked every couple of seconds, written only on a change)
 * rather than on close: when Forge exits it ends the JVM, and the window thread - a daemon -
 * never gets to dispose(), so a save-on-close would almost never run.
 */
final class WindowMemory {
    private WindowMemory() {}

    private static final java.util.prefs.Preferences P =
        java.util.prefs.Preferences.userRoot().node("mtg3d/window");
    private static float clock;
    private static int lastX = Integer.MIN_VALUE, lastY, lastW, lastH;

    /** Size and position from last time, or the defaults; a position far off any screen is ignored. */
    static void apply(Lwjgl3ApplicationConfiguration cfg, int defW, int defH) {
        int w = P.getInt("w", defW), h = P.getInt("h", defH);
        if (w < 320 || h < 240) { w = defW; h = defH; }
        cfg.setWindowedMode(w, h);
        int x = P.getInt("x", Integer.MIN_VALUE), y = P.getInt("y", Integer.MIN_VALUE);
        if (x > -4000 && x < 12000 && y > -2000 && y < 8000) cfg.setWindowPosition(x, y);
    }

    /** Call once per frame on the render thread. */
    static void track() {
        clock += Gdx.graphics.getDeltaTime();
        if (clock < 2f) return;
        clock = 0f;
        try {
            if (!(Gdx.graphics instanceof Lwjgl3Graphics)) return;
            com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window win = ((Lwjgl3Graphics) Gdx.graphics).getWindow();
            int x = win.getPositionX(), y = win.getPositionY();
            int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
            if (x == lastX && y == lastY && w == lastW && h == lastH) return;
            lastX = x; lastY = y; lastW = w; lastH = h;
            if (w < 320 || h < 240) return;               // minimised or collapsed: keep the old size
            P.putInt("x", x); P.putInt("y", y); P.putInt("w", w); P.putInt("h", h);
            P.flush();
        } catch (Throwable ignored) {
            // remembering the window is a nicety; never let it break the view
        }
    }
}
