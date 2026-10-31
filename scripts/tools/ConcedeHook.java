// House rule (owner, 2026-11-01): a player who concedes does not drop out at once. They sit out -
// passing priority, attacking and blocking with nothing, saying no to every "may" - until the
// start of their next upkeep, and only then leave the game. Their permanents stay on the
// battlefield until then and can still be attacked.
//
// EvalCacheAgent routes PlayerControllerHuman.concede() here (original renamed concede$orig);
// that is where both the desktop Concede button and a network client's concede arrive.
// The seat is handed to Passive, an AI controller with every voluntary action switched off;
// choices the rules force on a player (a sacrifice, a discard, ordering triggers) are still made,
// by the AI. Anything the player was being asked at the moment they conceded is answered with
// nothing (no blocks, no attackers, pass). CombatGuardHook calls beforePhase() at every phase
// begin, and from the first phase of their next turn after the untap step they concede for real.
//
// A normal concede happens instead when sitting out would only make people wait:
//   - no other human is left playing (a game against the AI just ends, as before);
//   - one player or fewer would be left playing. Everyone still sitting out leaves too then,
//     so the game ends straight away.
//
// Logged as "CONCEDE ..." on the console and as an INFORMATION line in the game log.
// -Dforge.concede.sitout=false turns it off. Test: scratch\probe\ConcedeProbe.java.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameLogEntryType;
import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputQueue;
import forge.player.PlayerControllerHuman;
import forge.util.collect.FCollectionView;

public final class ConcedeHook {
    private ConcedeHook() {}

    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("forge.concede.sitout", "true"));
    private static volatile MethodHandle orig;
    /** Players sitting out, with the turn number they conceded on. */
    private static final Map<Player, Integer> SITTING = Collections.synchronizedMap(new WeakHashMap<>());

    public static void concede(PlayerControllerHuman self) throws Throwable {
        if (orig == null) {
            orig = MethodHandles.privateLookupIn(PlayerControllerHuman.class, MethodHandles.lookup())
                .findVirtual(PlayerControllerHuman.class, "concede$orig", MethodType.methodType(void.class));
        }
        Player p = self.getPlayer();
        Game g = p == null ? null : p.getGame();
        if (!ENABLED || g == null || g.isGameOver() || p.hasLost() || g.getAge() != GameStage.Play) {
            orig.invoke(self);
            return;
        }
        if (SITTING.containsKey(p)) return; // already sitting out; leaves at their upkeep
        int playing = 0;
        boolean otherHuman = false;
        for (Player q : g.getPlayers()) {
            if (q == p || q.hasLost() || SITTING.containsKey(q)) continue;
            playing++;
            if (q.getController() instanceof PlayerControllerHuman) otherHuman = true;
        }
        if (!otherHuman || playing <= 1) {
            System.out.println("CONCEDE " + p.getName() + ": leaves now (" + (playing <= 1 ? "one player left" : "no other human playing") + ")");
            for (Player q : new ArrayList<>(SITTING.keySet())) leave(q, false);
            orig.invoke(self);
            return;
        }

        int turn = g.getPhaseHandler().getTurn();
        SITTING.put(p, turn);
        // The seat goes to Passive first, so whatever the game asks next is already answered by it.
        p.dangerouslySetController(new Passive(g, p, self.getLobbyPlayer()));
        // Then release whatever the player had in front of them, with nothing chosen.
        InputQueue q = self.getInputQueue();
        Input in = q.getInput();
        Combat combat = g.getPhaseHandler().getCombat();
        if (combat != null && (in instanceof InputBlock || in instanceof InputAttack)) {
            List<Card> mine = new ArrayList<>();
            for (Card c : in instanceof InputBlock ? combat.getAllBlockers() : combat.getAttackers())
                if (c.getController() == p) mine.add(c);
            for (Card c : mine) combat.removeFromCombat(c);
        }
        q.onGameOver(true);
        q.clearInputs();
        String msg = p.getName() + " conceded and sits out until the start of their next upkeep (house rule).";
        g.getGameLog().add(GameLogEntryType.INFORMATION, msg);
        System.out.println("CONCEDE " + p.getName() + ": sits out from turn " + turn
            + (in == null ? "" : " (released " + in.getClass().getSimpleName() + ")"));
    }

    /** Called by CombatGuardHook at every phase begin, on the game thread. */
    public static void beforePhase(PhaseHandler ph) {
        if (SITTING.isEmpty()) return;
        Player p = ph.getPlayerTurn();
        PhaseType phase = ph.getPhase();
        if (p == null || phase == null || phase == PhaseType.UNTAP) return;
        Integer since = SITTING.get(p);
        if (since == null || ph.getTurn() <= since) return;
        leave(p, true);
    }

    static boolean isSittingOut(Player p) {
        return SITTING.containsKey(p);
    }

    private static void leave(Player p, boolean check) {
        SITTING.remove(p);
        if (p.hasLost()) return;
        Game g = p.getGame();
        p.concede();
        g.getGameLog().add(GameLogEntryType.INFORMATION, p.getName() + " leaves the game (conceded earlier).");
        System.out.println("CONCEDE " + p.getName() + ": leaves the game (turn " + g.getPhaseHandler().getTurn()
            + ", " + g.getPhaseHandler().getPlayerTurn() + " " + g.getPhaseHandler().getPhase() + ")");
        if (check) g.getAction().checkGameOverCondition();
    }

    /**
     * The sitting-out seat: Forge's AI for everything the rules force, nothing for everything
     * voluntary. SeatClaimHook recognises it by name and never hands it to someone joining.
     */
    public static final class Passive extends PlayerControllerAi {
        public Passive(Game game, Player p, forge.LobbyPlayer lp) { super(game, p, lp); }

        @Override public List<SpellAbility> chooseSpellAbilityToPlay() { return null; }
        @Override public void declareAttackers(Player attacker, Combat combat) {}
        @Override public void declareBlockers(Player defender, Combat combat) {}
        @Override public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                List<String> options, Card cardToShow, Map<String, Object> params) { return false; }
        @Override public boolean confirmTrigger(WrappedAbility wrapper) { return false; }
        @Override public boolean confirmReplacementEffect(ReplacementEffect re, SpellAbility sa,
                forge.game.GameEntity affected, String question) { return false; }
        @Override public boolean confirmPayment(CostPart costPart, String prompt, SpellAbility sa) { return false; }
        @Override public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid,
                FCollectionView<Player> allPayers) { return false; }
        @Override public List<OptionalCostValue> chooseOptionalCosts(SpellAbility sa, List<OptionalCostValue> options) {
            return new ArrayList<>();
        }
    }
}
