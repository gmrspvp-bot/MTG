// Forge's pay-mana prompt crashing on a race (owner's networked game, 2026-10-22 21:27).
//
// InputPayMana.showMessage runs on the Swing thread and calls
// PlayerControllerHuman.pushActionableCards, which walks every card in the game
// (Card.getAllPossibleAbilities -> ReplacementHandler -> Game.forEachCardInGame) to light up
// the mana sources that can pay. If the game thread adds or removes a card at that moment - a
// Treasure sacrificed, a token arriving - the walk throws ConcurrentModificationException,
// and Forge shows its crash window (the game itself carries on).
//
// EvalCacheAgent routes pushActionableCards(boolean) here; the original is renamed
// pushActionableCards$orig. A clash is retried a few times, a millisecond apart; if it still
// clashes, the highlight is skipped for this refresh (the next prompt update redraws it)
// instead of reaching Forge's error handler. Logged once per game as "ACTIONABLE ...".

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ConcurrentModificationException;

import forge.player.PlayerControllerHuman;

public final class ActionableCardsHook {
    private ActionableCardsHook() {}

    private static volatile MethodHandle orig;
    private static volatile boolean reported;
    static final int TRIES = 4;

    public static void pushActionableCards(PlayerControllerHuman self, boolean b) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.lookup().findVirtual(PlayerControllerHuman.class, "pushActionableCards$orig",
                MethodType.methodType(void.class, boolean.class));
            orig = h;
        }
        for (int i = 1; ; i++) {
            try {
                h.invokeExact(self, b);
                return;
            } catch (ConcurrentModificationException e) {
                if (i >= TRIES) {
                    if (!reported) {
                        reported = true;
                        System.out.println("ACTIONABLE: the board changed while the mana sources were being lit ("
                            + TRIES + " tries) - highlight skipped this time instead of crashing");
                    }
                    return;
                }
                try { Thread.sleep(1); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
