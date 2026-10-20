package mtg3d;

/**
 * Forge's own window, while the 3D table is the one being played in (2026-10-21).
 *
 * Every decision Forge asks during a game is now answered in the 3D window, so Forge's window
 * has nothing left to do on screen except get in the way: it was the window that kept being
 * raised over the table. While a 3D game runs it is MINIMISED - not closed or hidden: the game
 * still lives in it, and it must be able to come straight back. It comes back by itself
 * whenever Forge needs it after all:
 *   - a question handed back to Forge (Handoff: timeout, H, or the 3D window stopped drawing);
 *   - a decision Gui3D does not take, or chose to forward (sideboarding, an unknown shape);
 *   - the 3D window is closed;
 *   - Quit on the end screen (Forge's menus are where you go next).
 * -Dforge.ui3d.hideforge=false keeps Forge's window up as before.
 *
 * Only ever restores a window it minimised itself, so a window the player minimised stays put.
 */
public final class ForgeWindow {
    private ForgeWindow() {}

    static final boolean HIDE = !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.hideforge", "true"));

    private static volatile boolean minimisedByUs;
    private static volatile java.awt.Frame minimised;

    /** Forge's main window: the largest visible Frame (the 3D window is not a Swing frame). */
    static java.awt.Frame frame() {
        java.awt.Frame best = null;
        long bestArea = -1;
        for (java.awt.Frame f : java.awt.Frame.getFrames()) {
            if (!f.isVisible() || !f.isDisplayable()) continue;
            long area = (long) f.getWidth() * f.getHeight();
            if (area > bestArea) { bestArea = area; best = f; }
        }
        return best;
    }

    /**
     * Minimise it a moment after the game starts - Forge is still building its match screen
     * when the 3D table attaches, and minimising before that finishes would be undone by it.
     */
    public static void minimiseSoon() {
        if (!HIDE) return;
        try {
            javax.swing.Timer t = new javax.swing.Timer(1500, e -> minimiseNow());
            t.setRepeats(false);
            t.start();
        } catch (Throwable ignored) {
            // Forge's window just stays where it is
        }
    }

    static void minimiseNow() {
        try {
            java.awt.Frame f = frame();
            if (f == null || (f.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) return;
            f.setExtendedState(f.getExtendedState() | java.awt.Frame.ICONIFIED);
            minimised = f;
            minimisedByUs = true;
            System.out.println("UI3D: Forge's window minimised while the 3D table runs (forge.ui3d.hideforge=false keeps it up)");
        } catch (Throwable ignored) {
            // not fatal
        }
    }

    /** Bring it back, because Forge needs it after all. A no-op if we did not minimise it. */
    public static void restore(String why) {
        if (!minimisedByUs) return;
        minimisedByUs = false;
        Runnable r = () -> {
            try {
                java.awt.Frame f = minimised != null && minimised.isDisplayable() ? minimised : frame();
                if (f == null) return;
                f.setExtendedState(f.getExtendedState() & ~java.awt.Frame.ICONIFIED);
                f.toFront();
                System.out.println("UI3D: Forge's window restored (" + why + ")");
            } catch (Throwable ignored) {
                // nothing more to do
            }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run();
        else javax.swing.SwingUtilities.invokeLater(r);
    }

    /** True while Forge's window is minimised by us - Swing dialogs parented to it would not show. */
    public static boolean isMinimised() {
        return minimisedByUs;
    }
}
