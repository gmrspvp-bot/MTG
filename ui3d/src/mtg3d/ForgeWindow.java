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
        // ONCE, not once per call. This is reached from Bridge.openWindow, which Gui3D calls on
        // openView AND on setGameView - and setGameView arrives whenever the host pushes a full
        // state, which during a resync storm is over and over. Each call used to arm its own
        // 1.5s timer, so the window was iconified again 1.5 seconds after every one of them.
        //
        // minimiseNow does nothing to a window that is already down, so the only time this bit
        // was a player's clicks: they bring Forge's window up to read something, and a second
        // and a half later it is slammed shut. Again. The counts say how often it happened -
        // 20 setGameView and 13 minimises in one session, 10 and 8 in two others - and on
        // 2026-11-01 a player on a bad connection watched his Forge window "flicker and freak
        // out" and then dropped off the game entirely.
        //
        // A new game later in the same session still wants one, hence the cooldown rather than
        // a flat once-ever.
        long now = System.currentTimeMillis();
        synchronized (ForgeWindow.class) {
            if (pending || now - lastAutoMinimise < AUTO_COOLDOWN_MS) return;
            pending = true;
        }
        try {
            javax.swing.Timer t = new javax.swing.Timer(1500, e -> {
                synchronized (ForgeWindow.class) { pending = false; }
                minimiseNow();
            });
            t.setRepeats(false);
            t.start();
        } catch (Throwable ignored) {
            synchronized (ForgeWindow.class) { pending = false; }
            // Forge's window just stays where it is
        }
    }

    /** One auto-minimise armed at a time, and not another for a while after one lands. */
    private static boolean pending;
    private static volatile long lastAutoMinimise;
    private static final long AUTO_COOLDOWN_MS =
        Long.getLong("forge.ui3d.minimisecooldown", 30000L);

    static void minimiseNow() {
        try {
            java.awt.Frame f = frame();
            if (f == null || (f.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) return;
            f.setExtendedState(f.getExtendedState() | java.awt.Frame.ICONIFIED);
            minimised = f;
            minimisedByUs = true;
            lastAutoMinimise = System.currentTimeMillis();
            watch();
            System.out.println("UI3D: Forge's window minimised while the 3D table runs (forge.ui3d.hideforge=false keeps it up)");
        } catch (Throwable ignored) {
            // not fatal
        }
    }

    /**
     * Put Forge's window down now, because the player said so (J on the table).
     *
     * There was no way to do this. H hands a decision back TO Forge; nothing sent its window
     * away again, so once it had the screen the only way out was the taskbar. On one monitor
     * that is the whole game covered up - "KDAWG has only one screen, and forge has taken his
     * screen like 10 times now" (2026-11-01).
     *
     * Ignores the auto-minimise cooldown: that exists to stop US doing this unasked, and a key
     * press is asked.
     */
    public static void hideNow() {
        if (frame() == null) {
            System.out.println("UI3D: no Forge window to hide");
            return;
        }
        minimiseNow();
    }

    /** Bring it back, because Forge needs it after all. A no-op if we did not minimise it. */
    public static void restore(String why) {
        // BEFORE the minimisedByUs check, not after it. Every caller of restore() is a place
        // that is handing something to Forge's window, and that is worth recording whether or
        // not we were the ones hiding it. With -Dforge.ui3d.hideforge=false - which is the whole
        // point of "Play 3D (both windows).cmd" - minimisedByUs is never true, so with the
        // expect() below the early return not one raise in the first session could be explained:
        // the report said "0 explained, 15 UNEXPLAINED", and the 0 was this bug, not a finding.
        expect(why);
        // Giving the window back counts as a reason not to grab it again immediately: whoever
        // asked for it is about to use it.
        lastAutoMinimise = System.currentTimeMillis();
        if (!minimisedByUs) return;
        minimisedByUs = false;
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
            lastCameWhy = how + ": " + why;
            System.out.println("UI3D: ATTENTION Forge's window " + how + " - " + why
                + "   prompt=\"" + oneLine(Gui3D.PROMPT.message) + "\""
                + "   input=" + (Gui3D.PROMPT.inputName == null || Gui3D.PROMPT.inputName.isEmpty()
                    ? "[none]" : Gui3D.PROMPT.inputName));
            if (!explained) pushBack(now);
        } catch (Throwable ignored) {
            // as above
        }
    }

    /**
     * What we last saw the window do, if it was recent - for the flag button.
     *
     * When the player says attention was stolen, the useful question is whether anything we
     * watch moved at the same moment. Ten seconds is generous on purpose: the player presses the
     * button after noticing, not at the instant it happened.
     */
    static String recentRaise() {
        if (lastCame == 0 || System.currentTimeMillis() - lastCame > 10000) return "";
        return lastCameWhy + " (" + ((System.currentTimeMillis() - lastCame) / 1000) + "s ago)";
    }

    private static volatile String lastCameWhy = "";

    private static volatile long lastPush;
    private static volatile int pushStreak;

    /**
     * Forge's window came up and nothing handed it anything. Put it back.
     *
     * An explained raise is left alone - Forge has something to show and covering it up would
     * hang the game behind a window nobody can see. An UNEXPLAINED one is the window taking the
     * screen for no reason we can name, and on a single monitor that is the game gone.
     *
     * Bounded, because fighting a window is how the flicker of 2026-11-01 happened: at most one
     * push every five seconds, and after three in half a minute it gives up and says so. A
     * window that insistent has beaten us and the player should be told, not strobed at.
     */
    private static void pushBack(long now) {
        if (!HIDE) return;                       // both windows on screen on purpose
        if (now - lastPush > 30000) pushStreak = 0;
        if (now - lastPush < 5000) return;
        if (pushStreak >= 3) {
            if (pushStreak == 3) {
                pushStreak++;
                System.out.println("UI3D: Forge's window keeps coming back and we have stopped "
                    + "pushing it down - press J to send it away, H to hand it the decision");
            }
            return;
        }
        lastPush = now;
        pushStreak++;
        javax.swing.Timer t = new javax.swing.Timer(600, e -> minimiseNow());
        t.setRepeats(false);
        t.start();
    }

    /** Forge's prompts are four lines; the log wants one. */
    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 110 ? t.substring(0, 107) + "..." : t;
    }
}
