package mtg3d;

import forge.gamemodes.match.input.Input;
import forge.player.PlayerControllerHuman;

/**
 * Makes a click from the 3D window apply only to the decision the player was looking at.
 *
 * Forge's Inputs are shared by both windows, and a click is forwarded to the EDT before it is
 * delivered. If the game thread finishes one decision and posts the next in between - which,
 * for a pass of priority, takes milliseconds - an unguarded click meant for the first decision
 * lands on the second and silently passes a step the player never saw. That is a real race,
 * shown by scratch\probe\DoubleClickProbe.java, not a theoretical one.
 *
 * So the 3D side never sends a bare "OK". It sends "OK to THIS input": the token is taken when the
 * prompt is drawn (see {@link #token}), and the click is dropped on delivery if a different
 * input is active by then. Dropping is always safe - the player sees the new prompt and clicks
 * again - whereas applying it to the wrong decision cannot be undone.
 */
public final class ClickGuard {
    private ClickGuard() {}

    /** What the player is looking at: the controller's active Input, or null if none. */
    public static Object token(PlayerControllerHuman pch) {
        try {
            return pch.getInputQueue().getInput();
        } catch (Throwable t) {
            return null;
        }
    }

    /** OK, delivered on the EDT like Forge's own clicks, only if `seen` is still the active input. */
    public static void ok(PlayerControllerHuman pch, Object seen) {
        deliver(pch, seen, true);
    }

    /** Cancel, with the same guard. */
    public static void cancel(PlayerControllerHuman pch, Object seen) {
        deliver(pch, seen, false);
    }

    // ---- Forge's own window ------------------------------------------------------------------

    /**
     * Forge's clicks carry no token, and "the prompt Forge's window last showed" turned out not to
     * be one either: the probe found Forge refreshes the prompt on the EDT while the old decision
     * closes, by which time the next one is already active. So Forge's window gets a debounce
     * instead: a click within this long of a NEW decision becoming active is taken to be aimed at
     * the previous one (nobody reads a new prompt and reacts that fast) and dropped.
     */
    public static final long DEBOUNCE_MS = 300;

    /** Per controller: {the active Input, System.nanoTime() when it became active}. */
    private static final java.util.Map<PlayerControllerHuman, Object[]> since =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Starts timing decisions for this controller. InputQueue is an Observable and notifies on the
     * game thread the moment an Input is set or removed, so the time is exact, not polled.
     */
    public static void watch(final PlayerControllerHuman pch) {
        if (pch == null || since.containsKey(pch)) return;
        since.put(pch, new Object[] { token(pch), System.nanoTime() });
        pch.getInputQueue().addObserver((o, arg) -> {
            Object now = token(pch);
            Object[] last = since.get(pch);
            if (last == null || last[0] != now) since.put(pch, new Object[] { now, System.nanoTime() });
        });
    }

    /** True if the active Input became active less than DEBOUNCE_MS ago. */
    static boolean justChanged(PlayerControllerHuman pch) {
        Object[] last = since.get(pch);
        if (last == null || last[0] == null) return false;
        return (System.nanoTime() - (Long) last[1]) < DEBOUNCE_MS * 1_000_000L;
    }

    /**
     * The controller to give Forge's window instead of the real one (Gui3D's setGameController).
     * Clicks pass straight through except within DEBOUNCE_MS of a new decision, where they are
     * dropped - closing the "3D key, then Forge's OK" order of the race, and Forge's own double
     * click as a bonus. Everything that is not a click is forwarded untouched.
     */
    public static forge.interfaces.IGameController forgeWindow(final PlayerControllerHuman pch) {
        watch(pch);
        final java.util.Set<String> guarded = new java.util.HashSet<>(java.util.Arrays.asList(
            "selectButtonOk", "selectButtonCancel", "selectCard", "selectPlayer", "selectAbility",
            "useMana", "alphaStrike"));
        return (forge.interfaces.IGameController) java.lang.reflect.Proxy.newProxyInstance(
            forge.interfaces.IGameController.class.getClassLoader(),
            new Class<?>[] { forge.interfaces.IGameController.class },
            (proxy, m, args) -> {
                if (guarded.contains(m.getName()) && justChanged(pch)) {
                    dropped++;
                    return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
                try {
                    return m.invoke(pch, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    /** How many clicks were dropped because the decision had moved on - for tests and logging. */
    public static volatile int dropped;

    private static void deliver(final PlayerControllerHuman pch, final Object seen, final boolean ok) {
        if (pch == null || seen == null) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            Input now = null;
            try {
                now = pch.getInputQueue().getInput();
            } catch (Throwable ignored) {
                // treat as moved on
            }
            if (now != seen) {
                dropped++;
                return;
            }
            if (ok) pch.selectButtonOk();
            else pch.selectButtonCancel();
        });
    }
}
