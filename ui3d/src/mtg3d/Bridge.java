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
    private static volatile boolean started = false;

    public static void attach(final Game game) {
        try {
            latest = StateReader.read(game);
            game.subscribeToEvents(new Listener(game));
            start();
        } catch (Throwable t) {
            System.out.println("UI3D: could not attach (" + t + ")");
        }
    }

    /** Guava's event bus finds this by the @Subscribe annotation, the same way BoardRecorder does. */
    public static final class Listener {
        private final Game game;

        Listener(Game game) { this.game = game; }

        @com.google.common.eventbus.Subscribe
        public void onEvent(Object ev) {
            try {
                latest = StateReader.read(game);
            } catch (Throwable t) {
                // keep the previous snapshot
            }
        }
    }

    /** Opens the window once per process, on its own thread. */
    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            try {
                Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
                cfg.setTitle("MTG 3D table");
                cfg.setWindowedMode(1600, 950);
                cfg.useVsync(true);
                cfg.setForegroundFPS(60);
                new Lwjgl3Application(new Table3D(() -> latest), cfg);
            } catch (Throwable e) {
                System.out.println("UI3D: window failed (" + e + ")");
            }
        }, "mtg3d");
        t.setDaemon(true);
        t.start();
        System.out.println("UI3D: 3D table opened");
    }
}
