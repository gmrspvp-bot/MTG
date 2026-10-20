// Who arms the auto-pass? (2026-10-21) EvalCacheAgent routes
// forge.gamemodes.match.YieldController.setAutoPassUntilEndOfTurn(boolean) here (original renamed
// setAutoPassUntilEndOfTurn$orig).
//
// "It skipped my whole turn" has now been diagnosed wrongly three times - the GUI's phase
// toggles, YieldController's auto-pass, and its per-player skip set - and each fix was right
// about its own mechanism without stopping it happening. The owner's screenshots show
// AUTO-PASSING to end of turn already lit on his OWN turn, so something arms it and nothing in
// the 3D window asked for it.
//
// The first attempt hooked PlayerControllerHuman.autoPassUntilEndOfTurn(), the action behind the
// End Turn button. It never fired, which rules that out and says YieldController sets the flag
// itself from somewhere inside. This hooks the setter instead, so every route is caught whatever
// calls it.
//
// Prints the call stack the first few times the flag goes ON, and changes no behaviour.
// -Dforge.autopasslog=false turns it off.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.gamemodes.match.YieldController;

public final class AutoPassHook {
    private AutoPassHook() {}

    private static volatile MethodHandle orig;
    private static int logged;
    /** A few is plenty: the same caller every time is the answer, and a log full of it is not. */
    private static final int MAX_LOGGED = 6;

    public static void setAutoPassUntilEndOfTurn(YieldController self, boolean on) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(YieldController.class, MethodHandles.lookup());
            orig = l.findVirtual(YieldController.class, "setAutoPassUntilEndOfTurn$orig",
                MethodType.methodType(void.class, boolean.class));
        }
        try {
            if (on && !"false".equals(System.getProperty("forge.autopasslog")) && logged < MAX_LOGGED) {
                logged++;
                StringBuilder b = new StringBuilder("AUTOPASS: armed to end of turn by:");
                StackTraceElement[] st = new Throwable().getStackTrace();
                // The first frame is this hook; the next few are whoever asked for it.
                for (int i = 1; i < st.length && i < 16; i++) {
                    b.append(System.lineSeparator()).append("    ").append(st[i]);
                }
                System.out.println(b);
            }
        } catch (Throwable ignored) {
            // never let the diagnostic be the thing that breaks the game
        }
        // And now the fix the trace paid for.
        //
        // The owner's turns were being ended by Forge's OWN menu. FControl installs a global
        // KeyEventDispatcher, which sees keystrokes anywhere in the JVM; ForgeMenu.handleKeyEvent
        // turns one into a click on the "End Turn" menu item, and that arms the auto-pass. The
        // 3D window never saw the key and nothing it answers was ever consulted - which is why
        // three correct fixes to three real mechanisms all failed to stop it.
        //
        // While the 3D table is running, an End Turn that arrived down that path is refused.
        // Deliberately choosing End Turn from the menu with the mouse does NOT go through
        // handleKeyEvent, so it still works; so does the 3D window's own End Turn button,
        // which calls Forge's cancel directly. -Dforge.autopassguard=false allows it again.
        if (on && ui3dRunning() && !"false".equals(System.getProperty("forge.autopassguard"))
                && fromForgeMenuKey()) {
            System.out.println("AUTOPASS: refused - Forge's menu shortcut tried to end the turn"
                + " (the 3D table is the window being played in)");
            return;
        }
        orig.invoke(self, on);
    }

    /** Did this come from a key press landing on Forge's menu rather than from a real choice? */
    private static boolean fromForgeMenuKey() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if ("forge.menus.ForgeMenu".equals(e.getClassName()) && "handleKeyEvent".equals(e.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /** Only guard while the 3D table is the window being played in. */
    private static boolean ui3dRunning() {
        return "true".equals(System.getProperty("forge.ui3d"));
    }
}
