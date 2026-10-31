// Show the player the game is actually waiting on (2026-11-01).
//
// Forge marks one player as "has priority" (PlayerView.hasPriority) once per game-loop step, for
// whoever holds priority, and leaves it there while the game sits inside somebody's prompt. So
// when another player has to discard, pick a card, choose for a villainous choice or declare
// blockers, the mark stays on the priority player. The 3D table's yellow frame and Forge's own
// "Waiting for X" both read that mark, and the owner kept seeing them on the wrong seat.
//
// EvalCacheAgent routes InputQueue.setInput / removeInput here (originals renamed $orig). After
// either, the mark is worked out again: every human with a prompt open right now (on this PC or
// joined over the network - the host holds every human's prompts) is marked, and if nobody is
// being asked, Forge's own rule applies again (the priority player). The flag is only read for
// display; the game itself uses PhaseHandler's priority player. Being a PlayerView property it
// also reaches joined players' tables. An AI that is thinking about a discard shows nothing new:
// it has no prompt to see, and it rarely takes long.
// -Dforge.prioritymark=false turns it off.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;

import forge.game.Game;
import forge.game.player.Player;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputBase;
import forge.gamemodes.match.input.InputLockUI;
import forge.gamemodes.match.input.InputQueue;
import forge.gamemodes.match.input.InputSynchronized;
import forge.player.PlayerControllerHuman;

public final class PriorityViewHook {
    private PriorityViewHook() {}

    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("forge.prioritymark", "true"));
    private static volatile MethodHandle setOrig, removeOrig;
    private static volatile WeakReference<Game> lastGame = new WeakReference<>(null);

    public static void setInput(InputQueue self, InputSynchronized in) throws Throwable {
        if (setOrig == null) {
            setOrig = MethodHandles.privateLookupIn(InputQueue.class, MethodHandles.lookup())
                .findVirtual(InputQueue.class, "setInput$orig", MethodType.methodType(void.class, InputSynchronized.class));
        }
        setOrig.invoke(self, in);
        if (ENABLED) remark(in);
    }

    public static void removeInput(InputQueue self, Input in) throws Throwable {
        if (removeOrig == null) {
            removeOrig = MethodHandles.privateLookupIn(InputQueue.class, MethodHandles.lookup())
                .findVirtual(InputQueue.class, "removeInput$orig", MethodType.methodType(void.class, Input.class));
        }
        removeOrig.invoke(self, in);
        if (ENABLED) remark(in);
    }

    /** Mark everyone being asked; with nobody being asked, Forge's own rule. */
    private static synchronized void remark(Input in) {
        try {
            Game g = null;
            if (in instanceof InputBase && ((InputBase) in).getController() != null) g = ((InputBase) in).getController().getGame();
            if (g == null) g = lastGame.get();
            else if (lastGame.get() != g) lastGame = new WeakReference<>(g);
            if (g == null || g.isGameOver()) return;
            boolean anyAsked = false;
            boolean[] asked = new boolean[g.getPlayers().size()];
            int i = 0;
            for (Player p : g.getPlayers()) {
                if (p.getController() instanceof PlayerControllerHuman) {
                    Input open = ((PlayerControllerHuman) p.getController()).getInputQueue().getInput();
                    asked[i] = open != null && !(open instanceof InputLockUI);
                    anyAsked |= asked[i];
                }
                i++;
            }
            Player prio = g.getPhaseHandler().getPriorityPlayer();
            i = 0;
            for (Player p : g.getPlayers()) {
                boolean mark = anyAsked ? asked[i] : p == prio;
                if (p.getView().getHasPriority() != mark) p.setHasPriority(mark);
                i++;
            }
        } catch (Throwable t) {
            // display only: never let it get in the game's way
        }
    }
}
