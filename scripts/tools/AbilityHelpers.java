// Activated abilities Forge's AI never used in our decks ("abilities" in -Dforge.aipatches).
// Each AI class below only takes over for its card and leaves everything else to Forge.
//
//  Professional Face-Breaker (Dig): sacrifice a Treasure to exile the top card and play it.
//    On its own main phase, with 2+ Treasures (one is always kept) and 2+ untapped lands.
//    Needs the override forge-data\custom\cards\professional_face_breaker.txt
//    (SVar:AIPreference:SacCost$Treasure), or Forge refuses to pay the sacrifice.
//  Blitzball Stadium (Animate): before combat, make the best untapped creature with counters
//    (most counter kinds) unblockable so it draws a card per kind when it connects.
//  Rikku, Resourceful Guardian (Pump + MoveCounter): steal a counter from the opponent creature
//    with the most counters onto our best creature (handled in AiPatches.EquipHelperPumpAi via
//    rikkuChoose; the MoveCounter half is accepted by HelperMoveCounterAi).
//  Loran of the Third Path (Draw, "you and target opponent each draw"): at an opponent's end
//    step, giving the card to the least threatening opponent.
//  Deadly Dispute (Draw with a sacrifice cost): only cast with a token to sacrifice.
//  O'aka, Traveling Merchant (Draw, remove a counter): at an opponent's end step when a
//    permanent has a counter to spare.
// Logged as "ABILITYAI ...".

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public final class AbilityHelpers {
    private AbilityHelpers() {}

    static boolean enabled = false;

    static boolean host(SpellAbility sa, String name) {
        return sa != null && sa.getHostCard() != null && name.equals(sa.getHostCard().getName());
    }

    static boolean myMainPhase(Player ai, boolean beforeCombatOnly) {
        forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (ph.getPlayerTurn() != ai || !ai.getGame().getStack().isEmpty()) return false;
        return ph.getPhase() == PhaseType.MAIN1 || (!beforeCombatOnly && ph.getPhase() == PhaseType.MAIN2);
    }

    static int untappedLands(Player ai) {
        int n = 0;
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) { if (c.isLand() && !c.isTapped()) n++; }
        return n;
    }

    static AiAbilityDecision yes(String what) {
        System.out.println("ABILITYAI " + what);
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    static AiAbilityDecision no() { return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi); }

    // ------------------------------------------------ Professional Face-Breaker

    public static class HelperDigAi extends forge.ai.ability.DigAi {
        @Override
        protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            if (!enabled || !host(sa, "Professional Face-Breaker")) return super.checkApiLogic(ai, sa);
            if (!myMainPhase(ai, false) || untappedLands(ai) < 2) return no();
            int treasures = 0;
            for (Card c : ai.getCardsIn(ZoneType.Battlefield)) { if (c.getType().hasSubtype("Treasure")) treasures++; }
            if (treasures < 2) return no();   // always keep one Treasure back
            return yes("Professional Face-Breaker: sacrifice a Treasure, exile and play the top card (" + treasures + " Treasures)");
        }
    }

    // ------------------------------------------------ Blitzball Stadium

    public static class HelperAnimateAi extends forge.ai.ability.AnimateAi {
        @Override
        protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, forge.game.phase.PhaseHandler ph) {
            return (enabled && host(sa, "Blitzball Stadium")) || AiPatches.DeathSave.applies(sa) || super.checkPhaseRestrictions(ai, sa, ph);
        }

        @Override
        protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            if (AiPatches.DeathSave.applies(sa)) return AiPatches.DeathSave.decide(ai, sa);   // Feign Death, Not Dead After All
            if (!enabled || !host(sa, "Blitzball Stadium")) return super.checkApiLogic(ai, sa);
            if (!myMainPhase(ai, true)) return no();
            Card best = null;
            int bestScore = 0;
            for (Card c : ai.getCreaturesInPlay()) {
                if (c.isTapped() || c.hasSickness() || !sa.canTarget(c) || !forge.game.combat.CombatUtil.canAttack(c)) continue;
                int kinds = c.getCounters().elementSet().size();   // different counter kinds
                if (kinds == 0) continue;
                int score = kinds * 100 + Math.max(0, c.getNetPower());
                if (score > bestScore) { bestScore = score; best = c; }
            }
            if (best == null) return no();
            sa.resetTargets();
            sa.getTargets().add(best);
            return yes("Blitzball Stadium: " + best.getName() + " unblockable (" + best.getCounters().elementSet().size() + " counter kinds)");
        }

        @Override
        public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
            if (enabled && sa.getParent() != null && host(sa.getParent(), "Blitzball Stadium")) return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            return super.chkDrawback(ai, sa);
        }
    }

    // ------------------------------------------------ Rikku, Resourceful Guardian

    /** Pump half targets an opponent creature with counters; MoveCounter half our best creature. */
    static String rikkuChoose(Player ai, SpellAbility sa) {
        if (!enabled) return null;
        forge.game.spellability.AbilitySub sub = sa.getSubAbility();
        if (sub == null || !myMainPhase(ai, true)) return null;
        Card from = null;
        int most = 0;
        for (Player opp : ai.getOpponents()) {
            for (Card c : opp.getCreaturesInPlay()) {
                int n = c.getCounters().size();   // Multiset: total number of counters
                if (n > most && sa.canTarget(c)) { most = n; from = c; }
            }
        }
        if (from == null) return null;
        Card to = null;
        int best = -1;
        for (Card c : ai.getCreaturesInPlay()) {
            if (c == sa.getHostCard() || !sub.canTarget(c)) continue;
            int v = forge.ai.ComputerUtilCard.evaluateCreature(c);
            if (v > best) { best = v; to = c; }
        }
        if (to == null) return null;
        sa.resetTargets();
        sa.getTargets().add(from);
        sub.resetTargets();
        sub.getTargets().add(to);
        return "Rikku: steal a counter from " + from.getName() + " onto " + to.getName();
    }

    public static class HelperMoveCounterAi extends forge.ai.ability.CountersMoveAi {
        @Override
        public AiAbilityDecision chkDrawback(Player ai, SpellAbility sa) {
            if (enabled && sa.getParent() != null && host(sa.getParent(), "Rikku, Resourceful Guardian")
                    && sa.getTargets() != null && !sa.getTargets().isEmpty()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return super.chkDrawback(ai, sa);
        }
    }

    // ------------------------------------------------ Kiki-Jiki self-copy loop guard

    /**
     * Kiki-Jiki copying a non-legendary copy of itself makes a fresh hasty Kiki each time, an
     * endless loop with no payoff for the AI: 3 of 4 games that hit the 15-minute limit in 1,560
     * overnight games (120-466 copies in one game). Allow MAX_SELF_COPIES per turn, then stop.
     */
    public static class HelperCopyPermanentAi extends forge.ai.ability.CopyPermanentAi {
        static final int MAX_SELF_COPIES = 4;
        static final java.util.Map<Player, int[]> COUNT = new java.util.WeakHashMap<>();   // {turn, copies}

        @Override
        protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            AiAbilityDecision d = super.checkApiLogic(ai, sa);
            if (!enabled || !d.willingToPlay() || sa.getHostCard() == null || sa.getTargets() == null) return d;
            String name = sa.getHostCard().getName();
            boolean selfCopy = false;
            for (Card t : sa.getTargets().getTargetCards()) { if (name.equals(t.getName())) selfCopy = true; }
            if (!selfCopy) return d;
            int turn = ai.getGame().getPhaseHandler().getTurn();
            synchronized (COUNT) {
                int[] c = COUNT.computeIfAbsent(ai, k -> new int[]{turn, 0});
                if (c[0] != turn) { c[0] = turn; c[1] = 0; }
                if (c[1] >= MAX_SELF_COPIES) return no();
                c[1]++;
            }
            return d;
        }
    }

    // ------------------------------------------------ Loran of the Third Path

    public static class HelperDrawAi extends forge.ai.ability.DrawAi {
        static boolean ours(SpellAbility sa) {
            return enabled && (host(sa, "Loran of the Third Path") || host(sa, "O'aka, Traveling Merchant"));
        }

        @Override
        protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, forge.game.phase.PhaseHandler ph) {
            return ours(sa) || super.checkPhaseRestrictions(ai, sa, ph);
        }

        @Override
        protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, forge.game.phase.PhaseHandler ph, String logic) {
            return ours(sa) || super.checkPhaseRestrictions(ai, sa, ph, logic);
        }

        @Override
        protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
            if (enabled && host(sa, "Deadly Dispute")) {
                // Only with a token to sacrifice (the card's AIPreference picks it). Without one
                // Forge sacrificed Sol Ring, Academy Manufactor, Caesar etc. for two cards.
                for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                    if (c.isToken() && (c.isArtifact() || c.isCreature()) && !c.isCommander()) return super.checkApiLogic(ai, sa);
                }
                return no();
            }
            if (enabled && host(sa, "O'aka, Traveling Merchant")) {
                // Draw at an opponent's end step when a counter can be spared: a permanent with 2+
                // counters, or a Saga (a lore counter less only delays it). Forge's AI almost never
                // paid the counter (1 activation in 4 casts).
                forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
                if (ph.getPhase() != PhaseType.END_OF_TURN || ph.getPlayerTurn() == ai || !ai.getGame().getStack().isEmpty()) return no();
                if (ai.getCardsIn(ZoneType.Hand).size() >= 7) return no();
                for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                    if (c.isLand()) continue;
                    if (c.getCounters().size() >= 2 || (c.getType().hasSubtype("Saga") && !c.getCounters().isEmpty())) {
                        return yes("O'aka: remove a counter, draw (" + c.getName() + " has " + c.getCounters().size() + ")");
                    }
                }
                return no();
            }
            if (!enabled || !host(sa, "Loran of the Third Path")) return super.checkApiLogic(ai, sa);
            forge.game.phase.PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPhase() != PhaseType.END_OF_TURN || ph.getPlayerTurn() == ai || !ai.getGame().getStack().isEmpty()) return no();
            if (ai.getCardsIn(ZoneType.Hand).size() >= 7) return no();
            Player gift = null;
            double least = Double.MAX_VALUE;
            for (Player opp : ai.getOpponents()) {
                if (opp.hasLost() || !sa.canTarget(opp)) continue;
                double t = HumanHooks.threat(opp);
                if (t < least) { least = t; gift = opp; }
            }
            if (gift == null) return no();
            sa.resetTargets();
            sa.getTargets().add(gift);
            return yes("Loran: you and " + gift.getName() + " (least threatening) each draw");
        }
    }
}
