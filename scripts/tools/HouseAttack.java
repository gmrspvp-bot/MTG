// The owner's Mr House attack style (2026-10-21; scratch\house-style.ps1 on their recorded games
// vs ~9,000 sim games). The owner: 1.7 combats a game with ~12 attackers each, hitting ~2 players
// per combat, robots kept home as blockers (double blocks) while the army builds, then an
// all-in swing to finish. The AI: 3.7 combats with ~3 attackers each, one player per combat -
// trickling robots into blockers instead of building a board that wins in two swings.
//
// Applied by AttackHook after Forge (and HumanHooks) chose the attack, only for a player who
// has Mr. House, President and CEO on the battlefield. Patch "housestyle".
//   Building (fewer than WIDE creatures able to attack): attack only with what leaves HOME
//     untapped blockers behind - unless the attack as chosen kills a player.
//   Wide (WIDE or more): every creature with power attacks except Mr. House (a 0/4 blocker),
//     spread so each opponent - lowest life first - gets more attackers than they have
//     untapped blockers; the rest go at the lowest-life one. If the whole army is enough to
//     kill one opponent through their blockers, it all goes there instead.
// Logged as "HOUSEAI ...".

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public final class HouseAttack {
    private HouseAttack() {}

    static boolean enabled;
    static final int WIDE = Integer.getInteger("forge.house.wide", 6);
    static final int HOME = Integer.getInteger("forge.house.home", 2);

    static boolean isHouse(Card c) {
        return c.getName().startsWith("Mr. House, President and CEO");
    }

    static void apply(Combat combat, boolean real) {
        if (!enabled || combat == null) return;
        Player me = combat.getAttackingPlayer();
        boolean house = false;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (isHouse(c)) house = true;
        if (!house) return;

        List<Player> opps = new ArrayList<>();
        for (Player p : me.getOpponents()) if (!p.hasLost()) opps.add(p);
        if (opps.isEmpty()) return;
        opps.sort(Comparator.comparingInt(Player::getLife));

        // Who could attack at all this combat (untapped, not summoning sick, power > 0).
        List<Card> able = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (isHouse(c) || c.getNetCombatDamage() <= 0) continue;
            boolean any = false;
            for (Player p : opps) if (CombatUtil.canAttack(c, p)) { any = true; break; }
            if (any || combat.isAttacking(c)) able.add(c);
        }
        String who = me.getName();

        if (able.size() < WIDE) {
            // Building: keep HOME untapped creatures back, unless the attack as chosen is lethal.
            if (killsSomeone(combat)) return;
            int untappedHome = 0;
            for (Card c : me.getCreaturesInPlay()) if (!c.isTapped() && !combat.isAttacking(c)) untappedHome++;
            List<Card> attackers = new ArrayList<>(combat.getAttackers());
            attackers.sort(Comparator.comparingInt(Card::getNetCombatDamage));   // weakest come home first
            int pulled = 0;
            for (Card a : attackers) {
                if (untappedHome >= HOME) break;
                if (a.isTapped()) continue;
                combat.removeFromCombat(a);
                untappedHome++;
                pulled++;
            }
            if (pulled > 0 && real) System.out.println("HOUSEAI " + who + ": building - " + pulled + " kept home as blockers ("
                + able.size() + " could attack, going wide at " + WIDE + ")");
            return;
        }

        // Wide: clear Forge's choice and lay the whole army out.
        for (Card a : new ArrayList<>(combat.getAttackers())) combat.removeFromCombat(a);
        List<Card> army = new ArrayList<>(able);
        army.sort(Comparator.comparingInt(Card::getNetCombatDamage).reversed());   // biggest placed first
        int total = 0;
        for (Card c : army) total += c.getNetCombatDamage();

        // One opponent the army can kill through their blockers (each blocker stops one attacker,
        // the biggest ones, pessimistically): all of it goes there.
        for (Player p : opps) {
            int blockers = blockers(p);
            int through = 0;
            for (int i = blockers; i < army.size(); i++) through += army.get(i).getNetCombatDamage();
            if (through >= p.getLife()) {
                int sent = send(combat, army, p);
                if (real) System.out.println("HOUSEAI " + who + ": all in at " + p.getName() + " (" + sent + " attackers, "
                    + through + " gets past " + blockers + " blockers, life " + p.getLife() + ")");
                return;
            }
        }
        // Spread: each opponent, lowest life first, gets blockers + 1 attackers; the rest to the lowest.
        List<Card> left = new ArrayList<>(army);
        StringBuilder plan = new StringBuilder();
        for (Player p : opps) {
            int want = blockers(p) + 1;
            int got = 0;
            for (Card c : new ArrayList<>(left)) {
                if (got >= want) break;
                if (!CombatUtil.canAttack(c, p)) continue;
                combat.addAttacker(c, p);
                left.remove(c);
                got++;
            }
            if (got > 0) plan.append(plan.length() > 0 ? ", " : "").append(got).append(" at ").append(p.getName());
        }
        for (Card c : left) {
            for (Player p : opps) {
                if (!CombatUtil.canAttack(c, p)) continue;
                combat.addAttacker(c, p);
                break;
            }
        }
        if (!left.isEmpty()) plan.append(", the other ").append(left.size()).append(" at ").append(opps.get(0).getName());
        if (real) System.out.println("HOUSEAI " + who + ": going wide with " + army.size() + " (" + total + " power): " + plan);
    }

    /** Untapped creatures that could block. */
    static int blockers(Player p) {
        int n = 0;
        for (Card c : p.getCreaturesInPlay()) if (!c.isTapped()) n++;
        return n;
    }

    static int send(Combat combat, List<Card> army, Player p) {
        int n = 0;
        for (Card c : army) {
            if (!CombatUtil.canAttack(c, p)) continue;
            combat.addAttacker(c, p);
            n++;
        }
        return n;
    }

    /** Does the attack as chosen take any opponent to 0 if unblocked past their blockers? */
    static boolean killsSomeone(Combat combat) {
        Player me = combat.getAttackingPlayer();
        for (Player p : me.getOpponents()) {
            if (p.hasLost()) continue;
            List<Card> at = new ArrayList<>(combat.getAttackersOf(p));
            if (at.isEmpty()) continue;
            at.sort(Comparator.comparingInt(Card::getNetCombatDamage).reversed());
            int through = 0;
            for (int i = blockers(p); i < at.size(); i++) through += at.get(i).getNetCombatDamage();
            if (through >= p.getLife()) return true;
        }
        return false;
    }
}
