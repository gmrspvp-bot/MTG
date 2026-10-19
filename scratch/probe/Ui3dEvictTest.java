// Does CardArt actually let go of textures it stopped drawing?
//
// CardArt caps itself at MAX_TEXTURES and, before 2026-10-20, simply stopped loading once it got
// there - so a long game would silently stop showing art. It now drops the least recently drawn.
// This proves the drop really happens, because the failure is invisible: everything still renders,
// just as blank cards, which is also what a missing picture looks like.
//
// It needs a real GL context (disposing a Texture is a GL call), so it opens a small window.
//
//   javac -cp "<mobile jar>;ui3d\classes" -d scratch\probe\classes scratch\probe\Ui3dEvictTest.java
//   java  -cp "<mobile jar>;ui3d\classes;scratch\probe\classes" Ui3dEvictTest
//
// Phase 1 asks for 500 distinct generated faces, past the 400 cap.
// Phase 2 asks for only 12 of them, for long enough to pass EVICT_AFTER_FRAMES.
// Expect: loaded() rises to about the cap, then falls back towards the working set.

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import mtg3d.CardArt;
import mtg3d.Snapshot;

public class Ui3dEvictTest {

    private static final int DISTINCT = 500;
    private static final int WORKING_SET = 12;
    private static final int IDLE_FRAMES = 1400;    // must exceed EVICT_AFTER_FRAMES (600)
    private static final int FILL_TIMEOUT = 200000; // frames, not time: vsync is off and these fly by

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("CardArt eviction probe");
        cfg.setWindowedMode(360, 200);
        cfg.useVsync(false);            // run as fast as it can; this is a test, not a view
        cfg.setForegroundFPS(0);

        new Lwjgl3Application(new ApplicationAdapter() {
            CardArt art;
            int frame;
            int peak;
            int fillEnded;

            @Override public void create() {
                // A directory that cannot exist, so every card misses the cache and gets a
                // generated face. That keeps the probe independent of what Forge happens to have
                // downloaded on this machine.
                art = new CardArt(new java.io.File("no-such-pics-dir"));
            }

            @Override public void render() {
                frame++;
                // Filling is bounded by how fast the worker threads draw faces, not by frames -
                // with vsync off, 1200 frames go by before a single one finishes. So phase 1 runs
                // until the cache stops growing, not for a fixed number of frames.
                if (fillEnded == 0) {
                    for (int i = 0; i < DISTINCT; i++) art.get(card(i));
                    art.update();
                    peak = Math.max(peak, art.loaded());
                    if (art.loaded() >= 400 || frame > FILL_TIMEOUT) {
                        fillEnded = frame;
                        System.out.println("after fill:   loaded=" + art.loaded()
                            + "  peak=" + peak + "  (took " + frame + " frames)");
                    }
                    return;
                }
                for (int i = 0; i < WORKING_SET; i++) art.get(card(i));
                art.update();
                peak = Math.max(peak, art.loaded());

                if (frame == fillEnded + IDLE_FRAMES) {
                    int now = art.loaded();
                    System.out.println("after idle:   loaded=" + now);
                    boolean capped = peak <= 400;
                    boolean shrank = now < peak;
                    System.out.println("cap respected (peak <= 400): " + capped);
                    System.out.println("evicted down from peak:      " + shrank);
                    System.out.println(capped && shrank ? "PASS" : "FAIL");
                    Gdx.app.exit();
                }
            }

            Snapshot.CardV card(int i) {
                Snapshot.CardV c = new Snapshot.CardV(i, "Probe Card " + i, "Creature - Test", true, false);
                c.manaCost = "{" + (i % 8) + "}";
                c.power = String.valueOf(i % 9);
                c.toughness = String.valueOf(i % 7);
                return c;
            }
        }, cfg);
    }
}
