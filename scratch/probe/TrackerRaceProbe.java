import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;

/**
 * The network sync race (2026-10-26): one thread adds delayed property changes to a Tracker while
 * another reads them, as the game thread and the host's network sync do. Without the agent
 * (TrackerSyncHook) the reader hits ConcurrentModificationException; with it, it must not.
 * Prints the count and PASS when it matches the expectation passed as args[0] ("race"/"safe").
 * Run from forge-installer-2.0.15 with the tools classes and the Forge jar (+ the agent for "safe").
 */
public class TrackerRaceProbe {
    public static void main(String[] a) throws Exception {
        final Tracker tracker = new Tracker();
        final forge.game.player.PlayerView obj = new forge.game.player.PlayerView(1, tracker);
        final TrackableProperty prop = TrackableProperty.values()[0];
        final long until = System.currentTimeMillis() + 2500;
        final int[] errors = { 0 }, reads = { 0 };
        Thread writer = new Thread(() -> {
            int i = 0;
            while (System.currentTimeMillis() < until) {
                tracker.addDelayedPropChange(obj, prop, i++);
                if (i % 500 == 0) tracker.clearDelayed();
            }
        });
        Thread reader = new Thread(() -> {
            while (System.currentTimeMillis() < until) {
                try { tracker.getDelayedPropsFor(obj); reads[0]++; }
                catch (java.util.ConcurrentModificationException e) { errors[0]++; }
            }
        });
        writer.start(); reader.start(); writer.join(); reader.join();
        boolean expectRace = a.length > 0 && a[0].equals("race");
        System.out.println("reads " + reads[0] + ", ConcurrentModificationExceptions " + errors[0]);
        boolean ok = expectRace ? errors[0] > 0 : errors[0] == 0;
        System.out.println(ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
