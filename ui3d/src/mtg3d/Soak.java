package mtg3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Makes the 3D table's silent failures countable.
 *
 * The bridge to Forge swallows everything on purpose: a snapshot that throws must never take the
 * game thread down with it, so every read is wrapped in `catch (Throwable)` and the view simply
 * shows less. That is right for playing, and useless for finding bugs - a StateReader that throws
 * on every single event looks exactly like one that works, because the only symptom is a view
 * that quietly stops updating.
 *
 * This records those catches instead of losing them. Off by default, so playing is unchanged:
 * -Dforge.ui3d.soak=true turns on counting and prints the first stack trace per site.
 *
 * Used by the soak runner (scripts\soak-ui3d.ps1), which drives StateReader over ordinary all-AI
 * games - no window, no human - so the read path meets thousands of real board states.
 */
public final class Soak {
    private Soak() {}

    public static final boolean ON = Boolean.getBoolean("forge.ui3d.soak");

    /** site -> how many times it threw. */
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    /** site -> the first exception's "class: message", kept for the summary. */
    private static final Map<String, String> FIRST = new ConcurrentHashMap<>();
    private static final AtomicLong READS = new AtomicLong();

    /**
     * Note that a guarded block threw. Never throws itself - it is called from inside the very
     * catch blocks that exist to keep the game alive.
     */
    public static void caught(String site, Throwable t) {
        if (!ON) return;
        try {
            AtomicLong n = COUNTS.computeIfAbsent(site, k -> new AtomicLong());
            if (n.incrementAndGet() == 1) {
                FIRST.put(site, t == null ? "(null)" : t.getClass().getName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                System.out.println("UI3D-SOAK: first failure at " + site);
                if (t != null) t.printStackTrace(System.out);
            }
        } catch (Throwable ignored) {
            // counting must never be the thing that breaks
        }
    }

    /** One completed snapshot read, for the per-read failure rate. */
    public static void read() {
        if (ON) READS.incrementAndGet();
    }

    /** Printed at the end of a soak run; the runner greps for these lines. */
    public static void report() {
        if (!ON) return;
        System.out.println("UI3D-SOAK: " + READS.get() + " snapshot reads");
        if (COUNTS.isEmpty()) {
            System.out.println("UI3D-SOAK: no failures");
            return;
        }
        COUNTS.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
            .forEach(e -> System.out.println("UI3D-SOAK: FAIL " + e.getValue().get()
                + "x " + e.getKey() + " - " + FIRST.get(e.getKey())));
    }
}
