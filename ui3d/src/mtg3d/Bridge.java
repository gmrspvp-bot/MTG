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
            start();
        } catch (Throwable t) {
            System.out.println("UI3D: could not attach (" + t + ")");
        }
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
            try {
                latest = StateReader.read(game);
            } catch (Throwable t) {
                // keep the previous snapshot
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
                cfg.useVsync(true);
                cfg.setForegroundFPS(60);
                new Lwjgl3Application(new Table3D(() -> latest), cfg);
                System.out.println("UI3D: 3D table closed (Forge carries on; it opens again with the next game)");
            } catch (Throwable e) {
                System.out.println("UI3D: window failed (" + e + ")");
            }
        }, "mtg3d");
        t.setDaemon(true);
        window = t;
        t.start();
        System.out.println("UI3D: 3D table opened");
    }
}
