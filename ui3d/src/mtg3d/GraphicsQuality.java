package mtg3d;

/**
 * Graphics quality: High, Medium or Low, chosen in the ESC menu and remembered between runs (in
 * the user's Java preferences, like the window position - nothing in the repo).
 *
 *             frame cap   anti-aliasing   card texture filtering   arc smoothness   felt texture
 *   High      60 + vsync  8x MSAA         16x anisotropic          28 segments      1024 px
 *   Medium    60 + vsync  4x MSAA          4x anisotropic          20 segments      1024 px
 *   Low       30          off              off                     12 segments       512 px
 *
 * The frame cap changes at once. Texture filtering applies to card pictures loaded after the
 * change (most of them, as the table turns over). Anti-aliasing and the felt are set when the
 * window is created, so those take effect the next time the 3D window opens - the menu says so.
 */
final class GraphicsQuality {
    enum Level { HIGH, MEDIUM, LOW }

    private static final java.util.prefs.Preferences P =
        java.util.prefs.Preferences.userRoot().node("mtg3d/settings");
    private static volatile Level level = load();

    private GraphicsQuality() {}

    private static Level load() {
        try {
            return Level.valueOf(P.get("graphics", Level.HIGH.name()));
        } catch (Throwable t) {
            return Level.HIGH;
        }
    }

    static Level current() { return level; }

    /** High -> Medium -> Low -> High, saved at once. */
    static void next() {
        level = level == Level.HIGH ? Level.MEDIUM : level == Level.MEDIUM ? Level.LOW : Level.HIGH;
        try { P.put("graphics", level.name()); P.flush(); } catch (Throwable ignored) { }
    }

    static String label() {
        return level == Level.HIGH ? "High" : level == Level.MEDIUM ? "Medium" : "Low";
    }

    static String detail() {
        switch (level) {
            case HIGH: return "60 fps, 8x anti-aliasing, sharpest cards (AA applies when the window next opens)";
            case MEDIUM: return "60 fps, 4x anti-aliasing (AA applies when the window next opens)";
            default: return "30 fps, no anti-aliasing - for a slow PC or a laptop on battery";
        }
    }

    static int fps() { return level == Level.LOW ? 30 : 60; }

    static boolean vsync() { return level != Level.LOW; }

    static int msaa() { return level == Level.HIGH ? 8 : level == Level.MEDIUM ? 4 : 0; }

    static float anisotropy() { return level == Level.HIGH ? 16f : level == Level.MEDIUM ? 4f : 1f; }

    static int arcSegments() { return level == Level.HIGH ? 28 : level == Level.MEDIUM ? 20 : 12; }

    static int feltSize() { return level == Level.LOW ? 512 : 1024; }

    /** The window settings this level wants, for whoever builds the window (Bridge, Demo). */
    static void configure(com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration cfg) {
        cfg.setBackBufferConfig(8, 8, 8, 8, 16, 0, msaa());
        cfg.useVsync(vsync());
        cfg.setForegroundFPS(fps());
    }
}
