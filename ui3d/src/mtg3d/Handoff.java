package mtg3d;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A way out of a blocked game thread.
 *
 * The second of the two safeguards the owner attached to the input plan, and the one that decides
 * whether taking over a decision is safe at all.
 *
 * Once `Gui3D` answers a decision from the 3D window instead of forwarding it, **Forge never sees
 * that call**. The game thread blocks until the 3D window replies. If the window has been closed,
 * has crashed, or its render thread has wedged, there is nothing else that can answer and the game
 * hangs for good - with no dialog anywhere to click. Losing a game that way is worse than any
 * amount of 3D polish is worth.
 *
 * So a taken-over decision never waits indefinitely. It waits for an answer, a timeout, or the
 * window going quiet, and on either of the last two it **hands the decision back to Forge** and
 * the game carries on in the window that was always there.
 *
 * Three things make that safe:
 *
 *   - **A heartbeat, not just a clock.** `Table3D` calls {@link #alive} every frame. A window that
 *     has stopped drawing cannot answer, so there is no reason to sit out the full timeout; the
 *     fallback fires as soon as the beat stops. Conversely a player genuinely thinking gets the
 *     whole timeout, because the window is still beating.
 *   - **Exactly one answer.** Whoever gets there first - the 3D window, the timeout, or the
 *     player pressing the hand-back key - wins, and everything after is ignored. A reply that
 *     arrives while Forge is already being asked the same question would otherwise answer the
 *     *next* decision, which is the same class of bug `ClickGuard` exists to prevent.
 *   - **The fallback is just the forward.** Handing back means calling what `Gui3D` would have
 *     called had the method not been taken over, so the fallback path is the already-working one.
 */
public final class Handoff<T> {

    /** How long a taken-over decision waits before Forge is asked instead. */
    public static final long DEFAULT_TIMEOUT_MS = Long.getLong("forge.ui3d.handoff.ms", 45_000L);
    /** No frame drawn for this long means the window cannot answer, whatever the clock says. */
    private static final long HEARTBEAT_STALE_MS = 2_000L;
    private static final long POLL_MS = 100L;

    private static volatile long lastBeat;
    /** Set by the hand-back key; cleared when the next decision starts. */
    private static volatile boolean handBackRequested;

    private final String what;
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile T answer;
    private volatile boolean settled;

    private Handoff(String what) {
        this.what = what;
    }

    /** Called once a frame by the renderer. Its absence is what proves the window cannot answer. */
    public static void alive() {
        lastBeat = System.currentTimeMillis();
    }

    /** The player asking for this decision to go back to Forge's window. */
    public static void handBack() {
        handBackRequested = true;
    }

    /** True while the 3D window has drawn recently enough to be capable of answering. */
    public static boolean windowResponsive() {
        return lastBeat != 0L && System.currentTimeMillis() - lastBeat < HEARTBEAT_STALE_MS;
    }

    public static <T> Handoff<T> start(String what) {
        handBackRequested = false;
        return new Handoff<>(what);
    }

    /**
     * Called from the render thread when the player has answered. Ignored if this decision has
     * already been settled by a timeout or a hand-back, which is what stops a late answer landing
     * on the following decision.
     *
     * @return true if this answer was the one that counted
     */
    public synchronized boolean answer(T value) {
        if (settled) return false;
        answer = value;
        settled = true;
        latch.countDown();
        return true;
    }

    /** Has this already been decided one way or the other? */
    public boolean isSettled() {
        return settled;
    }

    /**
     * Blocks the game thread until the window answers, then returns it. If the window does not or
     * cannot, calls {@code forge} instead and returns that - the game never stops.
     *
     * Only ever called on the game thread; it must never be called on the render thread, which
     * would deadlock the very window it is waiting for.
     */
    public T awaitOr(Callable<T> forge) {
        return awaitOr(forge, DEFAULT_TIMEOUT_MS);
    }

    public T awaitOr(Callable<T> forge, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String reason = null;
        try {
            while (true) {
                if (latch.await(POLL_MS, TimeUnit.MILLISECONDS)) return answer;
                if (handBackRequested) { reason = "the player handed it back"; break; }
                if (!windowResponsive()) { reason = "the 3D window stopped drawing"; break; }
                if (System.currentTimeMillis() >= deadline) { reason = "no answer in time"; break; }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        }

        // Settle it before asking Forge, so an answer arriving from the window a moment later is
        // dropped rather than applied to whatever Forge asks next.
        synchronized (this) {
            if (settled) return answer;      // it came in while we were giving up
            settled = true;
            latch.countDown();
        }
        System.out.println("UI3D: handing '" + what + "' back to Forge (" + reason + ")");
        // Forge's window may be minimised while the 3D table runs; it has to be seen to answer.
        ForgeWindow.restore("'" + what + "' handed back: " + reason);
        try {
            return forge.call();
        } catch (Throwable t) {
            System.out.println("UI3D: the fallback for '" + what + "' also failed (" + t + ")");
            return null;
        }
    }
}
