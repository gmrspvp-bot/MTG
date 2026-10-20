// Who arms the auto-pass? (2026-10-21) EvalCacheAgent routes
// forge.player.PlayerControllerHuman.autoPassUntilEndOfTurn() here (original renamed
// autoPassUntilEndOfTurn$orig).
//
// "It skipped my whole turn" has now been diagnosed wrongly three times - the GUI's phase
// toggles, YieldController's auto-pass, and its per-player skip set - and each fix was right
// about its own mechanism and did not stop it happening. The owner's last two screenshots show
// AUTO-PASSING to end of turn already lit on his OWN turn, which means something arms it and
// nothing in the 3D window asked for it.
//
// Guessing a fourth cause is worth less than one stack trace. This prints the call stack the
// first few times the flag goes on, which names the caller outright. It changes no behaviour.
//
// -Dforge.autopasslog=false turns it off.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.player.PlayerControllerHuman;

public final class AutoPassHook {
    private AutoPassHook() {}

    private static volatile MethodHandle orig;
    private static int logged;
    /** A few is plenty: the same caller every time is the answer, and a log full of it is not. */
    private static final int MAX_LOGGED = 6;

    public static void autoPassUntilEndOfTurn(PlayerControllerHuman self) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(PlayerControllerHuman.class, MethodHandles.lookup());
            orig = l.findVirtual(PlayerControllerHuman.class, "autoPassUntilEndOfTurn$orig",
                MethodType.methodType(void.class));
        }
        try {
            if (!"false".equals(System.getProperty("forge.autopasslog")) && logged < MAX_LOGGED) {
                logged++;
                boolean mine = false;
                try { mine = self.isGuiPlayer(); } catch (Throwable ignored) { /* say so below anyway */ }
                StringBuilder b = new StringBuilder("AUTOPASS: armed to end of turn for ");
                b.append(mine ? "THIS client's player" : "another player").append(" by:");
                StackTraceElement[] st = new Throwable().getStackTrace();
                // The first frame is this hook; the next few are whoever asked for it.
                for (int i = 1; i < st.length && i < 14; i++) {
                    b.append(System.lineSeparator()).append("    ").append(st[i]);
                }
                System.out.println(b);
            }
        } catch (Throwable ignored) {
            // never let the diagnostic be the thing that breaks the game
        }
        orig.invoke(self);
    }
}
