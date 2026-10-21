package mtg3d;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import forge.game.Game;

/**
 * Attaches the 3D table to a live Forge game (stage 1: read-only, alongside Forge's own UI).
 *
 * PlayHook calls {@link #attach} when a game with a human starts and -Dforge.ui3d=true. We then
 * subscribe to the game's event bus, rebuild a Snapshot on the game thread whenever anything
 * happens, and publish it to a volatile field the renderer reads. The LibGDX window runs on its
 * own thread, so it cannot stall the game even if rendering is slow.
 *
 * Nothing here can affect play. If any of it throws, the game carries on without the view.
 */
public final class Bridge {
    private Bridge() {}

    private static volatile Snapshot latest = new Snapshot();
    /** The game the window shows; listeners of earlier games go quiet (Game has no unsubscribe). */
    private static volatile Game current;
    /** The window's thread while it is open; null or finished once it has been closed. */
    private static Thread window;

    public static void attach(final Game game) {
        try {
            current = game;
            latest = StateReader.read(game);
            game.subscribeToEvents(new Listener(game));
            // Step 1 of the input plan: wrap the local human's GUI so the 3D window can see
            // what the engine is asking. Forwards everything, so Forge is unaffected.
            Gui3D.install(game);
            start();
            // Every decision is answered in 3D now, so Forge's window steps aside (minimised;
            // back whenever Forge needs it - see ForgeWindow).
            ForgeWindow.minimiseSoon();
        } catch (Throwable t) {
            System.out.println("UI3D: could not attach (" + t + ")");
        }
    }

    /**
     * A JOINED network game (2026-10-21). ClientHook calls this, by reflection, with the GUI
     * Forge has just made for the joining client, and uses whatever comes back in its place: the
     * Gui3D wrapper, which follows the host's game view and routes 3D clicks to the host. A local
     * game never comes through here - it attaches in attach() as before.
     */
    public static Object wrapClientGui(Object clientGui) {
        try {
            if (!(clientGui instanceof forge.gui.interfaces.IGuiGame)) return clientGui;
            current = null;                    // local listeners from an earlier game go quiet
            return Gui3D.installRemote((forge.gui.interfaces.IGuiGame) clientGui);
        } catch (Throwable t) {
            System.out.println("UI3D: could not follow the joined game (" + t + ")");
            return clientGui;
        }
    }

    /** A joined game's latest snapshot, built by Gui3D from the host's game view. */
    static void publish(Snapshot s) {
        if (s != null) latest = s;
    }

    /** Open the window for a joined game (no-op if it is already open). */
    /**
     * Bring the 3D window to the front and give it the keyboard.
     *
     * Forge's own window raises itself when it has something to ask, which takes the keyboard
     * with it: the question appeared on the 3D table and SPACE did nothing until the table was
     * clicked first (the owner, 2026-10-22). Only called when a question actually arrives, so it
     * is never fighting anybody for focus the rest of the time.
     */
    static void focusWindow() {
        try {
            com.badlogic.gdx.Graphics g = com.badlogic.gdx.Gdx.graphics;
            if (g instanceof com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics) {
                ((com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics) g).getWindow().focusWindow();
            }
        } catch (Throwable t) {
            Soak.caught("Bridge.focusWindow", t);
        }
    }

    static void openWindow() {
        if (Boolean.getBoolean("forge.ui3d.nowindow")) return;     // tests: no window on screen
        start();
        ForgeWindow.minimiseSoon();
    }

    /** The snapshot the window would draw - for tests (scratch\probe\Ui3dRemoteTest). */
    static Snapshot latestForTest() {
        return latest;
    }

    /**
     * Guava's event bus finds this by the @Subscribe annotation, the same way BoardRecorder does.
     * Every game gets its own listener and Forge offers no way to unsubscribe, so a listener whose
     * game is no longer the current one ignores its events: otherwise, after a rematch, the old
     * game's listener would keep overwriting the new game's snapshot.
     */
    public static final class Listener {
        private final Game game;

        Listener(Game game) { this.game = game; }

        @com.google.common.eventbus.Subscribe
        public void onEvent(Object ev) {
            if (game != current) return;
            // A turn must never BEGIN with an auto-pass already armed. That is what "it
            // skipped my whole turn" has been every time: something set it earlier, Forge
            // consulted it before anything the 3D window could answer, and the turn was gone
            // before the player touched a key (the owner, three times on 2026-10-21).
            // Clearing it here costs nothing when none is set, and an auto-pass armed during
            // somebody else's turn has no business surviving into yours.
            if (ev instanceof forge.game.event.GameEventTurnBegan) {
                try { Gui3D.stopAutoPass(); } catch (Throwable ignored) { /* never break the game */ }
            }
            try {
                latest = StateReader.read(game);
            } catch (Throwable t) {
                // keep the previous snapshot
                Soak.caught("Bridge.Listener", t);
            }
        }
    }

    /**
     * Opens the window on its own daemon thread, unless it is already open. Closing the window
     * ends only that thread: libGDX's Lwjgl3Application returns without System.exit (checked
     * 2026-10-20 with scratch\probe\Ui3dCloseTest.java: a Swing window standing in for Forge
     * stayed up, and a second 3D window opened fine afterwards). So a window the player closed
     * opens again with the next game rather than never.
     */
    private static synchronized void start() {
        if (window != null && window.isAlive()) return;
        Thread t = new Thread(() -> {
            try {
                Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
                cfg.setTitle("MTG 3D table");
                WindowMemory.apply(cfg, 1600, 950);   // last size and position, if any
                GraphicsQuality.configure(cfg);   // vsync, frame cap, anti-aliasing (ESC menu)
                new Lwjgl3Application(new Table3D(() -> latest), cfg);
                System.out.println("UI3D: 3D table closed (Forge carries on; it opens again with the next game)");
            } catch (Throwable e) {
                System.out.println("UI3D: window failed (" + e + ")");
            } finally {
                // No 3D window: Forge's is the only one left to play in.
                ForgeWindow.restore("the 3D table closed");
            }
        }, "mtg3d");
        t.setDaemon(true);
        window = t;
        t.start();
        System.out.println("UI3D: 3D table opened");
    }
}
