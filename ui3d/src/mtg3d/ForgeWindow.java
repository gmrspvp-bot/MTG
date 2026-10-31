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
        // The attention log goes on either way. Both windows deliberately side by side is
        // exactly when "what pulled my eye over there" matters most, and that is the case where
        // HIDE is off and this method used to do nothing at all.
        javax.swing.Timer w = new javax.swing.Timer(1500, e -> watch());
        w.setRepeats(false);
        w.start();
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
            watch();
            System.out.println("UI3D: Forge's window minimised while the 3D table runs (forge.ui3d.hideforge=false keeps it up)");
        } catch (Throwable ignored) {
            // not fatal
        }
    }

    /** Bring it back, because Forge needs it after all. A no-op if we did not minimise it. */
    public static void restore(String why) {
        if (!minimisedByUs) return;
        minimisedByUs = false;
        expect(why);                 // so the activation this causes is logged as explained
        Runnable r = () -> {
            try {
                java.awt.Frame f = minimised != null && minimised.isDisplayable() ? minimised : frame();
                if (f == null) return;
                watch();
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

    // ------------------------------------------------------------------ attention log
    /**
     * Log every time Forge's window comes forward, and say whether we know why.
     *
     * The logs say this is needed. Counting the two existing messages across a session:
     *
     *     console_2026-10-31_001606    minimised 13    restored  3
     *     console_2026-10-30_222810    minimised  8    restored  2
     *
     * minimiseNow only prints when it found the window NOT iconified and had to push it down
     * again, so the difference is Forge's window coming back up perhaps ten times a session by
     * some path that never called restore(). None of it was logged, none of it was counted as a
     * fallback, and a bug report filed in the middle of it still said "the 3D window handled
     * everything asked of it". That is the thing to catch.
     *
     * So: a listener on the frame itself, which sees the window come forward however it happened.
     * When restore() did it the reason is named; when nothing we know about did it, the line says
     * UNEXPLAINED and carries the prompt Forge was showing at that moment, which is the best clue
     * available as to what asked for it.
     *
     * Logging only - it never moves a window, and it is installed whether or not we are hiding
     * Forge's window, because the question "what pulled my eye over there" is just as live when
     * both windows are deliberately on screen at once.
     */
    private static volatile String expectedWhy = "";
    private static volatile long expectedAt;
    private static volatile java.awt.Frame listening;

    /** Called when restore() is about to raise it, so the activation that follows is explained. */
    private static void expect(String why) {
        expectedWhy = why;
        expectedAt = System.currentTimeMillis();
    }

    /**
     * Put the listener on Forge's frame, once. Safe to call repeatedly - it checks.
     *
     * Called from minimiseNow and from restore, which between them run early in every game, and
     * again from watch() for the case where we are not hiding the window at all.
     */
    public static void watch() {
        try {
            java.awt.Frame f = frame();
            if (f == null || f == listening) return;
            listening = f;
            f.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowActivated(java.awt.event.WindowEvent e) { came("activated"); }
                @Override public void windowDeiconified(java.awt.event.WindowEvent e) { came("un-minimised"); }
            });
            System.out.println("UI3D: watching Forge's window for attention theft");
        } catch (Throwable ignored) {
            // the log is a nicety; never let it break a game
        }
    }

    private static volatile long lastCame;

    private static void came(String how) {
        try {
            long now = System.currentTimeMillis();
            // Activated and de-iconified both fire for one raise; one line per raise is the point.
            if (now - lastCame < 400) return;
            lastCame = now;
            boolean explained = !expectedWhy.isEmpty() && now - expectedAt < 2500;
            String why = explained ? expectedWhy : "UNEXPLAINED";
            if (explained) expectedWhy = "";
            System.out.println("UI3D: ATTENTION Forge's window " + how + " - " + why
                + "   prompt=\"" + oneLine(Gui3D.PROMPT.message) + "\""
                + "   input=" + (Gui3D.PROMPT.inputName == null || Gui3D.PROMPT.inputName.isEmpty()
                    ? "[none]" : Gui3D.PROMPT.inputName));
        } catch (Throwable ignored) {
            // as above
        }
    }

    /** Forge's prompts are four lines; the log wants one. */
    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 110 ? t.substring(0, 107) + "..." : t;
    }
}
