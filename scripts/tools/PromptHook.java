// Routes forge.screens.match.controllers.CPrompt.setMessage() here (original renamed
// setMessage$orig) so the floating prompt window can find the live CPrompt instance.
//
// There is no singleton on VPrompt/CPrompt - a CPrompt belongs to a CMatchUI - so the cheapest
// way to get hold of the one in play is to watch the method Forge calls whenever the prompt text
// changes. The hook does nothing to the prompt itself: it calls the original and hands the
// instance to PromptWindow, which mirrors it. Any failure here is swallowed, because losing the
// floating window must never stop the real prompt from updating.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.card.CardView;
import forge.screens.match.controllers.CPrompt;

public final class PromptHook {
    private PromptHook() {}

    private static volatile MethodHandle orig;
    /** -Dforge.promptwindow=false turns the floating window off and leaves Forge untouched. */
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("forge.promptwindow", "true"));

    public static void setMessage(CPrompt self, String message, CardView card) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(CPrompt.class, MethodHandles.lookup());
            orig = l.findVirtual(CPrompt.class, "setMessage$orig",
                MethodType.methodType(void.class, String.class, CardView.class));
        }
        orig.invoke(self, message, card);
        if (!ENABLED) return;
        try {
            PromptWindow.attach(self);
        } catch (Throwable ignored) {
            // the mirror is cosmetic; never let it break the real prompt
        }
    }
}
