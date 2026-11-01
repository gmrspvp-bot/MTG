// How people play, measured against the AI in the recorded games (scripts\deck-style.ps1,
// 2026-11-02, 63 human games against ~1,000 sim games a deck), three gaps that held for
// every deck. Part of the "humanlike" AI patch; each has its own switch.
//
//  1. Cast before combat (-Dforge.ai.main1=false turns it off). People cast 13-20% of their
//     own-turn spells after combat, the AI 50-63%: Forge holds every permanent for main 2
//     unless ComputerUtil.castPermanentInMain1 finds a reason not to. Now a permanent goes
//     down in main 1, except a card with flash (Forge's surprise logic keeps that) and a raid
//     card while an attack is coming (Forge's own "no" stands).
//
//  2. No pokes (-Dforge.ai.nopokes=false). People attack on about half their turns with
//     creatures, the AI on 75-90%, and the gap is small attacks: 21% of the AI's attacks were
//     2 power or less (people 8%), its average 8 power (people 13.5). People build up and hit
//     harder. An attack on a player with POKE_MAX power or less is taken back, unless it kills
//     (life or commander damage), a commander is in it, or anything of ours has an attack or
//     combat-damage trigger (those are why a small attack pays). Called from AttackHook.
//
//  3. Attack the leader (-Dforge.ai.leader=false). People aimed 45-60% of their attacks at the
//     player with the most life, the AI 35-43%, and the AI 46-51% at the lowest: Forge's
//     choosePreferredDefenderPlayer adds the square of a player's life deficit below 20. Now
//     the defender Forge builds its attack around is turnTarget(): life above the table's
//     average, less a share of the board threat (attacks that get through), plus whoever hit
//     us last. HumanHooks.retarget and
//     freeAttacks use the same target, so the three no longer pull different ways.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

public final class PlayStyleHook {
    private PlayStyleHook() {}

    static final boolean MAIN1 = !"false".equalsIgnoreCase(System.getProperty("forge.ai.main1", "true"));
    static final boolean NO_POKES = !"false".equalsIgnoreCase(System.getProperty("forge.ai.nopokes", "true"));
    static final boolean LEADER = !"false".equalsIgnoreCase(System.getProperty("forge.ai.leader", "true"));
    /** An attack on one player with this much power or less is a poke: -Dforge.ai.pokemax (default 3). */
    static final int POKE_MAX = Integer.getInteger("forge.ai.pokemax", 3);
    /** Points per life above the opponents' average in turnTarget. */
    static final double LIFE_W = 2.0, BOARD_W = 0.3, HIT_BACK = 10;

    // ---------------------------------------------------------------- 1. main 1

    private static volatile MethodHandle origMain1;

    public static boolean castPermanentInMain1(Player ai, SpellAbility sa) throws Throwable {
        MethodHandle h = origMain1;
        if (h == null) {
            h = MethodHandles.lookup().findStatic(forge.ai.ComputerUtil.class, "castPermanentInMain1$orig",
                MethodType.methodType(boolean.class, Player.class, SpellAbility.class));
            origMain1 = h;
        }
        boolean forge = (boolean) h.invokeExact(ai, sa);
        if (forge || !MAIN1 || !HumanHooks.enabled) return forge;
        try {
            Card card = sa.getHostCard();
            if (card == null || card.isLand()) return forge;
            if (card.hasKeyword(Keyword.FLASH)) return forge;
            // Forge's raid rule said no because an attack is coming: keep it.
            if ("Count$AttackersDeclared".equals(card.getSVar("RaidTest"))) return forge;
            return true;
        } catch (Throwable t) {
            return forge;
        }
    }

    // ---------------------------------------------------------------- 3. the defender

    private static volatile MethodHandle origDef;

    public static Player choosePreferredDefenderPlayer(Player ai, boolean forCombatDmg) throws Throwable {
        MethodHandle h = origDef;
        if (h == null) {
            h = MethodHandles.lookup().findStatic(forge.ai.AiAttackController.class, "choosePreferredDefenderPlayer$orig",
                MethodType.methodType(Player.class, Player.class, boolean.class));
            origDef = h;
        }
        Player forge = (Player) h.invokeExact(ai, forCombatDmg);
        // Only the attack choice; other callers (e.g. damage spells) keep Forge's pick.
        if (!forCombatDmg || !LEADER || !HumanHooks.enabled) return forge;
        try {
            Player t = turnTarget(ai);
            return t != null ? t : forge;
        } catch (Throwable e) {
            return forge;
        }
    }

    /** People's target for the turn: the life leader, weighed with board threat and who hit us last. */
    static Player turnTarget(Player me) {
        java.util.List<Player> opps = new java.util.ArrayList<>();
        for (Player p : me.getOpponents()) { if (!p.hasLost()) opps.add(p); }
        if (opps.isEmpty()) return null;
        double avg = 0;
        for (Player p : opps) avg += Math.max(0, p.getLife());
        avg /= opps.size();
        java.util.Set<Player> hitMe = HumanHooks.attackedMe(me);
        Card myCmdr = null;
        int cmdrPower = 0;
        for (Card c : me.getCreaturesInPlay()) { if (c.isCommander() && c.getNetCombatDamage() > cmdrPower) { cmdrPower = c.getNetCombatDamage(); myCmdr = c; } }
        Player best = null;
        double top = -1e9;
        for (Player p : opps) {
            // A big board counts against: people hit the biggest board less than the AI (31-58% vs
            // 57-70%), and aiming Forge's attack at it made the AI stay home - games stalled to
            // the clock (3 of 17 in the first test, 2026-11-02).
            double s = LIFE_W * (p.getLife() - avg) - BOARD_W * HumanHooks.threat(p) + (hitMe.contains(p) ? HIT_BACK : 0);
            // Finish what the commander started (as HumanHooks.retarget always did).
            if (myCmdr != null && p.getCommanderDamage(myCmdr) > 0 && cmdrPower >= AiPatches.CaptainPolicy.commanderDamageLeft(myCmdr, p)) s += 1000;
            if (s > top) { top = s; best = p; }
        }
        return best;
    }

    // ---------------------------------------------------------------- 2. no pokes

    /** Take back small attacks that do nothing but chip (see the header). Returns a log note or null. */
    static String holdPokes(Combat combat) {
        if (!NO_POKES || !HumanHooks.enabled || combat == null) return null;
        Player me = combat.getAttackingPlayer();
        if (me == null || combat.getAttackers().isEmpty() || attackPays(me)) return null;
        StringBuilder note = new StringBuilder();
        for (Player d : new java.util.ArrayList<>(combat.getDefendingPlayers())) {
            java.util.List<Card> at = new java.util.ArrayList<>(combat.getAttackersOf(d));
            if (at.isEmpty()) continue;
            int power = 0;
            boolean keep = false;
            for (Card a : at) {
                power += Math.max(0, a.getNetCombatDamage());
                if (a.isCommander() || a.isGoaded() || a.hasKeyword(Keyword.INFECT) || a.hasKeyword(Keyword.TOXIC) || ownTriggers(a)) keep = true;
                for (Card eq : a.getAttachedCards()) { if (ownTriggers(eq)) keep = true; }
            }
            if (keep || power > POKE_MAX) continue;
            if (HumanHooks.damageThrough(at, d) >= d.getLife() || HumanHooks.commanderKill(at, d) != null) continue;
            for (Card a : at) combat.removeFromCombat(a);
            // Something requires them to attack after all (an attack requirement we did not see): put them back.
            if (!CombatUtil.validateAttackers(combat)) {
                for (Card a : at) combat.addAttacker(a, d);
                continue;
            }
            note.append(note.length() > 0 ? "; " : "").append("held back a poke at ").append(d.getName())
                .append(" (").append(at.size()).append(" creature(s), power ").append(power).append(")");
        }
        return note.length() == 0 ? null : note.toString();
    }

    /** Anything of ours that rewards attacking itself (battle cry, "whenever a creature you control attacks", exalted). */
    private static boolean attackPays(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.hasKeyword(Keyword.EXALTED)) return true;
            for (Trigger t : c.getTriggers()) {
                TriggerType m = t.getMode();
                if (m == TriggerType.AttackersDeclared || m == TriggerType.AttackersDeclaredOneTarget) return true;
                if (m == TriggerType.Attacks && !c.isCreature()) return true;
            }
        }
        return false;
    }

    /** The card's own attack or combat-damage trigger (Ninja-style card draw, "whenever ~ attacks"). */
    private static boolean ownTriggers(Card c) {
        for (Trigger t : c.getTriggers()) {
            TriggerType m = t.getMode();
            if (m == TriggerType.Attacks || m == TriggerType.DamageDone || m == TriggerType.DamageDoneOnce) return true;
        }
        return false;
    }
}
