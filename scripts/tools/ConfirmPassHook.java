// "Pass anyway?" safety net for the human player (desktop games). EvalCacheAgent routes
// forge.gamemodes.match.input.InputPassPriority.onOk() here (original renamed onOk$orig).
// The user found it too easy to click OK and skip part of their own turn. On their own turn,
// with an empty stack, before passing it asks for confirmation when:
//   - main phase 1 or 2: a land drop is still available and there is a land in hand, or
//   - main phase 2: there is untapped mana for at least one non-land card in hand.
// On any turn it also asks when an opponent's spell is on the stack and a counterspell in hand
// could answer it (counterReason).
// Answering No keeps priority. All-AI games never reach this code.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPassPriority;

public final class ConfirmPassHook {
    private ConfirmPassHook() {}

    private static volatile MethodHandle orig, getController;

    public static void onOk(InputPassPriority input) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(InputPassPriority.class, MethodHandles.lookup());
            orig = l.findVirtual(InputPassPriority.class, "onOk$orig", MethodType.methodType(void.class));
            getController = l.findVirtual(InputPassPriority.class, "getController",
                MethodType.methodType(forge.player.PlayerControllerHuman.class));
        }
        String why = null;
        askedAbout = -1;
        try {
            forge.player.PlayerControllerHuman pc = (forge.player.PlayerControllerHuman) getController.invoke(input);
            // Only ever ask about the player THIS client controls. When one machine hosts a
            // network game, inputs for the other human are built in this JVM too, so without
            // this check the host was shown the other player's decisions - including a
            // counterspell reminder that named a card in their opponent's hand.
            if (pc.isGuiPlayer()) why = reason(pc.getPlayer());
        } catch (Throwable t) {
            why = null;   // never block passing because of our own error
        }
        if (why != null && !confirm(why)) return;   // keep priority
        if (why != null && askedAbout != -1) wavedThrough = askedAbout;   // don't ask about that spell again
        orig.invoke(input);
    }

    /** Stack-instance id of the spell the last counter reminder was about / the user let through. */
    private static volatile int askedAbout = -1, wavedThrough = -1;

    /**
     * Counterspell reminder: an opponent's spell is on top of the stack and a counterspell in hand
     * could answer it (the user let Damning Verdict resolve with Force of Negation in hand).
     * Force of Negation: noncreature spells only; free on an opponent's turn by exiling another
     * blue card, otherwise {1}{U}{U}. Others: "noncreature" counters skip creature spells; the
     * mana value has to fit the untapped mana.
     */
    static String counterReason(Player me) {
        forge.game.Game game = me.getGame();
        if (game.getStack().isEmpty()) return null;
        boolean myTurn = game.getPhaseHandler().getPlayerTurn() == me;
        int mana = forge.ai.ComputerUtilMana.getAvailableManaEstimate(me, false);
        int blueCards = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand)) { if (c.isBlue()) blueCards++; }
        // Every opponent spell on the stack, top first - not just the top item: triggers often
        // sit above the spell (Infernal Grasp on Captain America under his ward trigger, or
        // Sunscorch Regent's cast trigger), and the user missed Spell Pierce that way.
        for (forge.game.spellability.SpellAbilityStackInstance si : game.getStack()) {
            if (!si.isSpell() || si.getActivatingPlayer() == null || !me.isOpponentOf(si.getActivatingPlayer())) continue;
            Card spell = si.getSourceCard();
            if (spell == null || si.getId() == wavedThrough) continue;   // already answered "pass anyway"
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (!AiPatches.ManaHold.isCounterspell(c)) continue;
                String text = c.getOracleText() == null ? "" : c.getOracleText().toLowerCase();
                if (text.contains("noncreature spell") && spell.isCreature()) continue;
                boolean free = "Force of Negation".equals(c.getName()) && !myTurn && blueCards >= 2;
                if (!free && c.getCMC() > mana) continue;
                askedAbout = si.getId();
                return "Let " + spell.getName() + " resolve?\nYou could counter it with " + c.getName()
                    + (free ? " (free: exile another blue card)" : "") + ".\n\nPass anyway?";
            }
        }
        return null;
    }

    static String reason(Player me) {
        String counter = counterReason(me);
        if (counter != null) return counter;
        forge.game.Game game = me.getGame();
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        if (ph.getPlayerTurn() != me || !game.getStack().isEmpty()) return null;
        PhaseType phase = ph.getPhase();
        if (phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2) return null;
        java.util.List<String> notes = new java.util.ArrayList<>();
        boolean landInHand = false;
        for (Card c : me.getCardsIn(ZoneType.Hand)) { if (c.isLand()) landInHand = true; }
        boolean landDropLeft = me.getMaxLandPlaysInfinite() || me.getLandsPlayedThisTurn() < me.getMaxLandPlays();
        if (landInHand && landDropLeft) notes.add("you haven't played a land this turn");
        if (phase == PhaseType.MAIN2) {
            int mana = forge.ai.ComputerUtilMana.getAvailableManaEstimate(me, false);
            java.util.List<String> castable = new java.util.ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (!c.isLand() && c.getCMC() <= mana && castable.size() < 3) castable.add(c.getName());
            }
            if (!castable.isEmpty()) notes.add(mana + " untapped mana and cards you could cast (" + String.join(", ", castable) + ")");
        }
        if (notes.isEmpty()) return null;
        String where = phase == PhaseType.MAIN1 ? "go to combat" : "end your turn";
        return "You're about to " + where + ", but " + String.join(" and ", notes) + ".\n\nPass anyway?";
    }

    /**
     * Parented on Forge's own window, not null: a null parent centres the dialog on the
     * PRIMARY monitor, so after moving the game to a second screen it kept opening back on
     * the main one.
     */
    static boolean confirm(String text) {
        final boolean[] yes = { true };
        Runnable ask = () -> yes[0] = javax.swing.JOptionPane.showConfirmDialog(PlayHook.forgeFrame(),
            text, "Pass priority?", javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE) == javax.swing.JOptionPane.YES_OPTION;
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) ask.run();
            else javax.swing.SwingUtilities.invokeAndWait(ask);
        } catch (Throwable t) {
            return true;
        }
        return yes[0];
    }
}
