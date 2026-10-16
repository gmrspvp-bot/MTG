// Human-like decisions for every AI player ("humanlike" in -Dforge.aipatches).
//
//  Mulligans (EvalCacheAgent routes ComputerUtil.wantMulligan here), the user's rule
//  ("mulligan only if not enough lands", "2-3 lands minimum"): take the free mulligan with
//  fewer than 3 lands, later hands need 2, at 5 cards or fewer any land will do. Land-heavy
//  hands are always kept.
//
//  Attack targets (called from AttackHook after Forge picks attackers): Forge sends each
//  attacker where it looks best on its own, so open, weak-looking players get piled on.
//  People pick a target for the turn: a kill that is on offer, else the player who is
//  winning (board + threat), with a pull towards whoever attacked them last. Attackers are
//  only moved to that player when it is safe (no untapped creature there can block and
//  kill them) and when they were not already part of a kill on someone else.
//
//  Before that (also from AttackHook): goForKill sends everything at a player who can be
//  finished off this turn, and freeAttacks adds attackers that can't be killed by a block
//  when our own life is safe. Logged as "TARGETAI ... kill on" / "free attack".

import java.util.Map;
import java.util.WeakHashMap;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public final class HumanHooks {
    private HumanHooks() {}

    static boolean enabled = false;

    // ---------------------------------------------------------------- mulligans

    private static volatile java.lang.invoke.MethodHandle origMull;

    public static boolean wantMulligan(Player p, int cardsToReturn) throws Throwable {
        java.lang.invoke.MethodHandle h = origMull;
        if (h == null) {
            h = java.lang.invoke.MethodHandles.lookup().findStatic(forge.ai.ComputerUtil.class, "wantMulligan$orig",
                java.lang.invoke.MethodType.methodType(boolean.class, Player.class, int.class));
            origMull = h;
        }
        if (!enabled) return (boolean) h.invokeExact(p, cardsToReturn);
        try {
            int lands = 0, cheap = 0, ramp = 0, size = 0;
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                size++;
                if (c.isLand()) { lands++; continue; }
                if (c.getCMC() <= 2) cheap++;
                if ((c.isArtifact() && !c.getManaAbilities().isEmpty()) || (c.getOracleText() != null
                        && c.getOracleText().toLowerCase().contains("search your library for a basic land"))) ramp++;
            }
            boolean paidAlready = cardsToReturn > 0;   // the first Commander mulligan is free
            // The user's rule: mulligan only for too few lands, "2-3 lands minimum": the free
            // mulligan is taken below 3 lands, later hands need 2, and at 5 cards or fewer
            // any land will do. Land-heavy hands are always kept.
            int minLands = size <= 5 ? 1 : (paidAlready ? 2 : 3);
            boolean want = lands < minLands;
            System.out.println("MULLAI " + p.getName() + ": " + lands + " lands of " + size + (paidAlready ? " (after a paid mulligan)" : "")
                + " -> " + (want ? "mulligan" : "keep"));
            return want;
        } catch (Throwable t) {
            return (boolean) h.invokeExact(p, cardsToReturn);
        }
    }

    // ---------------------------------------------------------------- attack targets

    /** Per game: whom each player attacked in their most recent combat. */
    private static final Map<Game, Map<Player, java.util.Set<Player>>> LAST_ATTACKED = new WeakHashMap<>();

    static double threat(Player p) {
        double s = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) s += Math.max(0, c.getNetPower()) * 2;
            if (!c.isLand()) s += 1;
            s += ThreatTracker.score(c) / 10.0;
            if (c.isCommander()) s += 8;
        }
        return s;   // life total itself doesn't make someone a threat
    }

    static boolean safeAttack(Card a, Player def) {
        if (a.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) return true;
        int toughness = a.getNetToughness() - a.getDamage();
        for (Card b : def.getCardsIn(ZoneType.Battlefield)) {
            if (!b.isCreature() || b.isTapped() || !CombatUtil.canBlock(a, b)) continue;
            if (b.getNetCombatDamage() >= toughness || b.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH)) return false;
        }
        return true;
    }

    /** Re-aim attackers at a human-like target for this turn; returns a log note or null. */
    static String retarget(Combat combat) {
        if (!enabled || combat == null) return null;
        Player me = combat.getAttackingPlayer();
        if (me == null || combat.getAttackers().isEmpty()) return null;
        Game game = me.getGame();
        // Kills on offer stay as they are.
        java.util.Set<Player> lethalOn = new java.util.HashSet<>();
        for (Player d : combat.getDefendingPlayers()) {
            int power = 0;
            for (Card a : combat.getAttackersOf(d)) power += Math.max(0, a.getNetCombatDamage());
            if (power >= d.getLife()) lethalOn.add(d);
        }
        // Choose this turn's target.
        Player target = null;
        double best = -1;
        java.util.Set<Player> hitMe = new java.util.HashSet<>();
        Map<Player, java.util.Set<Player>> last = LAST_ATTACKED.get(game);
        if (last != null) {
            for (Map.Entry<Player, java.util.Set<Player>> e : last.entrySet()) { if (e.getValue().contains(me)) hitMe.add(e.getKey()); }
        }
        // Finish what the commander started: a player our commander can knock out with one
        // more hit of commander damage is this turn's target (the user rotated hits that way).
        int cmdrPower = 0;
        Card myCmdr = null;
        for (Card c : me.getCreaturesInPlay()) { if (c.isCommander() && c.getNetCombatDamage() > cmdrPower) { cmdrPower = c.getNetCombatDamage(); myCmdr = c; } }
        for (Player opp : me.getOpponents()) {
            if (opp.hasLost()) continue;
            double s = threat(opp) + (hitMe.contains(opp) ? 10 : 0);
            if (myCmdr != null && opp.getCommanderDamage(myCmdr) > 0 && cmdrPower >= AiPatches.CaptainPolicy.commanderDamageLeft(myCmdr, opp)) s += 1000;
            if (s > best) { best = s; target = opp; }
        }
        if (target == null) return null;
        int moved = 0;
        for (Card a : new java.util.ArrayList<>(combat.getAttackers())) {
            Player d = combat.getDefenderPlayerByAttacker(a);
            if (d == null || d == target || lethalOn.contains(d)) continue;
            if (!CombatUtil.canAttack(a, target) || !safeAttack(a, target)) continue;
            combat.removeFromCombat(a);
            combat.addAttacker(a, target);
            moved++;
        }
        return moved == 0 ? null : "moved " + moved + " attacker(s) to " + target.getName()
            + (hitMe.contains(target) ? " (hit back)" : " (biggest threat)");
    }

    /** A creature that should stay home: mana creatures and creatures with a tap ability. */
    static boolean busyAtHome(Card c) {
        if (!c.getManaAbilities().isEmpty()) return true;
        for (forge.game.spellability.SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isAbility() && sa.getPayCosts() != null && sa.getPayCosts().hasTapCost()) return true;
        }
        return false;
    }

    /** Our creatures that could join an attack now (untapped, no summoning sickness, power > 0). */
    static java.util.List<Card> available(Player me) {
        java.util.List<Card> out = new java.util.ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (c.getNetCombatDamage() > 0 && CombatUtil.canAttack(c)) out.add(c);
        }
        return out;
    }

    /**
     * Damage that gets through to p if all these attack: p's untapped creatures are assumed to
     * block the biggest attackers they can block, one each; attackers nothing of p can block
     * always connect.
     */
    static int damageThrough(java.util.List<Card> attackers, Player p) {
        int through = 0;
        for (Card a : unblockedEstimate(attackers, p)) through += Math.max(0, a.getNetCombatDamage());
        return through;
    }

    /** The attackers expected to connect (see damageThrough). */
    static java.util.List<Card> unblockedEstimate(java.util.List<Card> attackers, Player p) {
        java.util.List<Card> blockers = new java.util.ArrayList<>();
        for (Card b : p.getCreaturesInPlay()) { if (!b.isTapped()) blockers.add(b); }
        java.util.List<Card> sorted = new java.util.ArrayList<>(attackers);
        sorted.sort((x, y) -> Integer.compare(y.getNetCombatDamage(), x.getNetCombatDamage()));
        java.util.List<Card> through = new java.util.ArrayList<>();
        for (Card a : sorted) {
            Card stop = null;
            for (Card b : blockers) { if (CombatUtil.canBlock(a, b)) { stop = b; break; } }
            if (stop != null) blockers.remove(stop);
            else through.add(a);
        }
        return through;
    }

    /** A commander among these attackers that connects for at least the commander damage p still has room for. */
    static Card commanderKill(java.util.List<Card> attackers, Player p) {
        for (Card a : unblockedEstimate(attackers, p)) {
            if (a.isCommander() && a.getNetCombatDamage() > 0
                    && a.getNetCombatDamage() >= AiPatches.CaptainPolicy.commanderDamageLeft(a, p)) return a;
        }
        return null;
    }

    /**
     * Go for the kill: Forge often splits attackers or keeps some home when a player could be
     * finished off (no "kill on offer" re-aim was ever logged in 1,600 games - the old code
     * only kept kills that Forge had already set up). If every creature we can attack with,
     * after the defender's likely blocks, deals at least that player's life, all of them attack
     * that player (lowest life first). Returns a log note or null.
     */
    static String goForKill(Combat combat) {
        if (!enabled || combat == null) return null;
        Player me = combat.getAttackingPlayer();
        if (me == null) return null;
        java.util.List<Player> opps = new java.util.ArrayList<>();
        for (Player p : me.getOpponents()) { if (!p.hasLost()) opps.add(p); }
        opps.sort((x, y) -> Integer.compare(x.getLife(), y.getLife()));
        for (Player p : opps) {
            java.util.List<Card> can = new java.util.ArrayList<>();
            for (Card c : available(me)) { if (CombatUtil.canAttack(c, p)) can.add(c); }
            if (can.isEmpty()) continue;
            int through = damageThrough(can, p);
            Card cmdr = through >= p.getLife() ? null : commanderKill(can, p);
            if (through < p.getLife() && cmdr == null) continue;
            int added = 0;
            for (Card c : can) {
                if (combat.isAttacking(c, p)) continue;
                if (combat.isAttacking(c)) combat.removeFromCombat(c);
                combat.addAttacker(c, p);
                added++;
            }
            String how = cmdr != null ? "commander damage: " + cmdr.getName() + " " + cmdr.getNetCombatDamage() + " vs "
                    + AiPatches.CaptainPolicy.commanderDamageLeft(cmdr, p) + " left" : through + " damage through vs " + p.getLife() + " life";
            return added == 0 ? null : "kill on " + p.getName() + " (" + how + ", " + added + " attacker(s) added/moved)";
        }
        return null;
    }

    /**
     * Free attacks: Forge keeps creatures home whenever an attack might leave it at 10-20 life
     * (our Realistic profile), so 40-life four-player games dragged on (~13 rounds vs ~9-10 in
     * real games). People swing when it is safe: if our life stays well above everything the
     * opponents could hit back with, creatures that no untapped blocker of the chosen defender
     * can kill attack too (this turn's target first, else the lowest-life opponent). Mana
     * creatures, creatures with tap abilities and Captain America (his own policy) stay home.
     */
    static String freeAttacks(Combat combat) {
        if (!enabled || combat == null) return null;
        Player me = combat.getAttackingPlayer();
        if (me == null) return null;
        int crackBack = 0;
        for (Player p : me.getOpponents()) {
            if (p.hasLost()) continue;
            for (Card c : p.getCreaturesInPlay()) crackBack += Math.max(0, c.getNetCombatDamage());
        }
        if (me.getLife() - crackBack <= FREE_ATTACK_LIFE_MARGIN) return null;
        java.util.List<Player> opps = new java.util.ArrayList<>();
        for (Player p : me.getOpponents()) { if (!p.hasLost()) opps.add(p); }
        // Same player the other attackers went for, if any; then lowest life.
        Player main = null;
        for (Card a : combat.getAttackers()) { Player d = combat.getDefenderPlayerByAttacker(a); if (d != null) { main = d; break; } }
        opps.sort((x, y) -> Integer.compare(x.getLife(), y.getLife()));
        if (main != null) { opps.remove(main); opps.add(0, main); }
        int added = 0;
        for (Card c : available(me)) {
            if (combat.isAttacking(c) || busyAtHome(c) || AiPatches.CaptainPolicy.isCaptain(c)) continue;
            for (Player p : opps) {
                if (CombatUtil.canAttack(c, p) && safeAttack(c, p)) { combat.addAttacker(c, p); added++; break; }
            }
        }
        return added == 0 ? null : "free attack with " + added + " more creature(s) (life " + me.getLife() + ", opponents' power " + crackBack + ")";
    }

    static final int FREE_ATTACK_LIFE_MARGIN = 10;

    /** Record whom this player attacked (called once per real combat). */
    static void remember(Combat combat) {
        Player me = combat.getAttackingPlayer();
        if (me == null) return;
        java.util.Set<Player> defs = new java.util.HashSet<>();
        for (Card a : combat.getAttackers()) { Player d = combat.getDefenderPlayerByAttacker(a); if (d != null) defs.add(d); }
        synchronized (LAST_ATTACKED) { LAST_ATTACKED.computeIfAbsent(me.getGame(), g -> new WeakHashMap<>()).put(me, defs); }
    }
}
