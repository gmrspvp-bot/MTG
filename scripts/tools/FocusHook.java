// Why does Forge's window steal attention at all? (2026-11-01)
//
// "We dont want to be on forge ui unless we choose to alt tab to it." Fair, and until now the
// 3D window was only ever fighting the symptom: ForgeWindow minimised Forge's window again, or
// pulled the table back to the front, after the fact. This stops it at the cause.
//
// The cause is forge.screens.match.CMatchUI.updateButtons, which ends in this (decompiled from
// lambda$updateButtons$13):
//
//     btnOK.setEnabled(enableOK);
//     btnCancel.setEnabled(enableCancel);
//     btnOK.setFocusable(enableOK && focus);
//     btnCancel.setFocusable(enableCancel && !focus);
//     if (toFocus != null && !FNetOverlay.SINGLETON_INSTANCE.getTxtInput().hasFocus())
//         toFocus.requestFocus();
//
// with
//
//     toFocus = (enableOK && focus) ? btnOK : enableCancel ? btnCancel : null;
//
// On Windows, requestFocus() on a component in a window that is not focused ACTIVATES that
// window. updateButtons runs on every new input, so Forge took the screen every time priority
// came back at a new step. The owner's log says so without ambiguity - all eleven raises in his
// first two turns were input=InputPassPriority:
//
//     ATTENTION Forge's window activated - UNEXPLAINED  prompt="Priority: Dominic
//     Turn: 2 (Dominic) Phase: Beginning of Combat Step ..."  input=InputPassPriority
//
// Two fixes that look obvious and are not:
//   - Passing focus=false. toFocus then falls through to btnCancel, which is enabled almost
//     always ("End Turn"), so the grab happens anyway.
//   - setFocusable(false) on the buttons once, which Gui3D.disarmFocusTheft did. The two
//     setFocusable calls above put it back at the next prompt.
//
// So: guard the WINDOW, and only for the length of this one call. A window whose
// focusableWindowState is false cannot be activated by a component asking for focus, and
// updateButtons runs the lambda synchronously (invokeInEdtAndWait off the GUI thread,
// immediately on it), so the flag is back on before anything else can see it.
//
// It does NOT make Forge's window unfocusable in general: alt-tab, clicking it and typing in it
// all work exactly as before, because the state is restored in a finally. And if Forge's window
// is ALREADY focused - the player chose to be there - this does nothing at all, which is the
// difference between stealing focus and keeping it.
//
// -Dforge.ui3d.nofocus=false restores Forge's behaviour (the same property Gui3D uses).

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.player.PlayerView;
import forge.screens.match.CMatchUI;

public final class FocusHook {
    private FocusHook() {}

    private static final boolean ON =
        !"false".equalsIgnoreCase(System.getProperty("forge.ui3d.nofocus", "true"));

    private static volatile MethodHandle orig;
    private static volatile boolean said;
    private static int guarded;

    public static void updateButtons(CMatchUI self, PlayerView owner, String okLabel,
            String cancelLabel, boolean enableOk, boolean enableCancel, boolean focusOk)
            throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(CMatchUI.class, MethodHandles.lookup());
            orig = l.findVirtual(CMatchUI.class, "updateButtons$orig",
                MethodType.methodType(void.class, PlayerView.class, String.class, String.class,
                    boolean.class, boolean.class, boolean.class));
        }
        java.awt.Frame f = ON ? forgeFrame() : null;
        // Already focused means the player put themselves there. Taking focusability away from a
        // focused window would push them straight back out of it, which is the same rudeness in
        // the other direction.
        boolean guard = f != null && f.getFocusableWindowState() && !f.isFocused();
        if (guard) {
            f.setFocusableWindowState(false);
            if (!said) {
                said = true;
                System.out.println("UI3D: Forge's window can no longer take focus from a prompt "
                    + "(FocusHook; -Dforge.ui3d.nofocus=false restores it)");
            }
            guarded++;
        }
        try {
            orig.invoke(self, owner, okLabel, cancelLabel, enableOk, enableCancel, focusOk);
        } finally {
            if (guard) f.setFocusableWindowState(true);
        }
    }

    /** How many prompts were stopped from taking the screen - for the bug report. */
    public static int guardedCount() {
        return guarded;
    }

    /** Forge's main window: the largest visible Frame, as ForgeWindow picks it. */
    private static java.awt.Frame forgeFrame() {
        java.awt.Frame best = null;
        long bestArea = -1;
        for (java.awt.Frame w : java.awt.Frame.getFrames()) {
            if (!w.isVisible() || !w.isDisplayable()) continue;
            long area = (long) w.getWidth() * w.getHeight();
            if (area > bestArea) { bestArea = area; best = w; }
        }
        return best;
    }
}
