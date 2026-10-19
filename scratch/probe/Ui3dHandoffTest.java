// Does Handoff actually stop a taken-over decision hanging the game?
//
// Once Gui3D answers a decision from the 3D window, Forge never sees that call - so if the window
// cannot answer, nothing else can, and the game hangs with no dialog anywhere to click. Handoff is
// the escape. Its failure mode is a game that stops dead, which is the worst outcome in the whole
// project and also the one least likely to show up in casual testing, so it is worth proving.
//
// Five cases:
//   1. the window answers in time              -> its answer is used, Forge is never asked
//   2. the window never answers                -> Forge answers, after the timeout
//   3. the window stops drawing (crash/wedge)  -> Forge answers EARLY, without waiting it out
//   4. the player presses hand-back            -> Forge answers immediately
//   5. an answer arrives after the handoff     -> it is ignored, not applied to the next decision
//
//   javac -cp ui3d\classes -d scratch\probe\classes scratch\probe\Ui3dHandoffTest.java
//   java  -cp "ui3d\classes;scratch\probe\classes" Ui3dHandoffTest

import mtg3d.Handoff;

import java.util.concurrent.atomic.AtomicBoolean;

public class Ui3dHandoffTest {

    static boolean pass = true;

    public static void main(String[] args) throws Exception {
        // A heartbeat thread standing in for the render loop.
        AtomicBoolean beating = new AtomicBoolean(true);
        Thread beat = new Thread(() -> {
            while (beating.get()) {
                Handoff.alive();
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        beat.setDaemon(true);
        beat.start();
        Thread.sleep(150);

        // 1. answered in time
        {
            Handoff<String> h = Handoff.start("case 1");
            AtomicBoolean forgeAsked = new AtomicBoolean(false);
            new Thread(() -> { sleep(200); h.answer("from 3D"); }).start();
            String got = h.awaitOr(() -> { forgeAsked.set(true); return "from Forge"; }, 5000);
            check("answered in time", "from 3D".equals(got) && !forgeAsked.get(), got);
        }

        // 2. never answered -> falls back after the timeout
        {
            Handoff<String> h = Handoff.start("case 2");
            long t0 = System.currentTimeMillis();
            String got = h.awaitOr(() -> "from Forge", 600);
            long took = System.currentTimeMillis() - t0;
            check("timeout falls back", "from Forge".equals(got) && took >= 550, got + " after " + took + "ms");
        }

        // 3. window stops drawing -> falls back EARLY, not after the full timeout
        {
            beating.set(false);
            Thread.sleep(2200);                       // let the heartbeat go stale
            Handoff<String> h = Handoff.start("case 3");
            long t0 = System.currentTimeMillis();
            String got = h.awaitOr(() -> "from Forge", 30_000);
            long took = System.currentTimeMillis() - t0;
            check("dead window falls back early", "from Forge".equals(got) && took < 2000,
                got + " after " + took + "ms (timeout was 30s)");
            beating.set(true);
            Thread b2 = new Thread(() -> {
                while (beating.get()) { Handoff.alive(); sleep(50); }
            });
            b2.setDaemon(true);
            b2.start();
            Thread.sleep(150);
        }

        // 4. the player hands it back
        {
            Handoff<String> h = Handoff.start("case 4");
            new Thread(() -> { sleep(150); Handoff.handBack(); }).start();
            long t0 = System.currentTimeMillis();
            String got = h.awaitOr(() -> "from Forge", 30_000);
            long took = System.currentTimeMillis() - t0;
            check("hand-back falls back now", "from Forge".equals(got) && took < 1500,
                got + " after " + took + "ms");
        }

        // 5. a late answer must be ignored, or it would land on the NEXT decision
        {
            Handoff<String> h = Handoff.start("case 5");
            String got = h.awaitOr(() -> "from Forge", 300);
            boolean accepted = h.answer("late from 3D");
            check("late answer ignored", "from Forge".equals(got) && !accepted,
                got + ", late answer accepted=" + accepted);
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-30s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
