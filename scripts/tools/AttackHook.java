// Runs after Forge's AI has chosen its attackers (EvalCacheAgent routes
// forge.ai.AiAttackController.declareAttackers here; the original is renamed
// declareAttackers$orig).
//  - "captain" AI patch: Captain America, First Avenger is taken back out of the attack
//    unless AiPatches.CaptainPolicy.attackReason says it pays off (or, in the aggro and
//    pressure styles, added to every attack). Logged as "ATTACKAI ...".
//  - "humanlike" AI patch: attackers are re-aimed at a human-like target for the turn
//    (HumanHooks.retarget). Logged as "TARGETAI ...".

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.ai.AiAttackController;
import forge.game.card.Card;
import forge.game.combat.Combat;

public final class AttackHook {
    private AttackHook() {}

    private static volatile MethodHandle orig;

    public static int declareAttackers(AiAttackController controller, Combat combat) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.lookup().findVirtual(AiAttackController.class, "declareAttackers$orig",
                MethodType.methodType(int.class, Combat.class));
            orig = h;
        }
        int result = (int) h.invokeExact(controller, combat);
        if (combat == null) return result;
        boolean real = isRealCombat(combat);
        if (AiPatches.CaptainPolicy.enabled) {
            try {
                captain(combat, real);
            } catch (Throwable t) {
                // Never break the game: keep Forge's own choice.
            }
        }
        if (HumanHooks.enabled && real) {
            try {
                String name = combat.getAttackingPlayer().getName();
                String kill = HumanHooks.goForKill(combat);
                if (kill != null) System.out.println("TARGETAI " + name + ": " + kill);
                else {
                    String free = HumanHooks.freeAttacks(combat);
                    if (free != null) System.out.println("TARGETAI " + name + ": " + free);
                }
                String note = kill != null ? null : HumanHooks.retarget(combat);
                if (note != null) System.out.println("TARGETAI " + name + ": " + note);
                HumanHooks.remember(combat);
            } catch (Throwable t) {
                // Never break the game.
            }
        }
        return result;
    }

    private static void captain(Combat combat, boolean real) {
        if ("aggro".equals(AiPatches.CaptainPolicy.MODE) || "pressure".equals(AiPatches.CaptainPolicy.MODE)) {
            // Aggressive styles: Captain America attacks every combat he is able to.
            forge.game.player.Player me = combat.getAttackingPlayer();
            for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (!AiPatches.CaptainPolicy.isCaptain(c) || combat.isAttacking(c)) continue;
                forge.game.player.Player target = null;
                for (forge.game.player.Player p : me.getOpponents()) {
                    if (forge.game.combat.CombatUtil.canAttack(c, p) && (target == null || p.getLife() < target.getLife())) target = p;
                }
                if (target != null) {
                    combat.addAttacker(c, target);
                    if (real) System.out.println("ATTACKAI Captain America attacks (aggressive style)");
                }
            }
            return;
        }
        for (Card a : new java.util.ArrayList<>(combat.getAttackers())) {
            if (!AiPatches.CaptainPolicy.isCaptain(a)) continue;
            String why = AiPatches.CaptainPolicy.attackReason(combat, a);
            if (why == null) {
                combat.removeFromCombat(a);
                if (real) System.out.println("ATTACKAI Captain America stays home");
            } else if (real) {
                System.out.println("ATTACKAI Captain America attacks (" + why + ")");
            }
        }
        // Forge sometimes leaves him home when an attack pays off; the user attacked with him
        // on 67% of their turns (the AI 50-58%). Try each opponent (players his team already
        // attacks first, then lowest life) and keep him in where attackReason says yes.
        forge.game.player.Player me = combat.getAttackingPlayer();
        for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
            if (!AiPatches.CaptainPolicy.isCaptain(c) || combat.isAttacking(c) || !forge.game.combat.CombatUtil.canAttack(c)) continue;
            java.util.List<forge.game.player.Player> opps = new java.util.ArrayList<>();
            for (forge.game.player.Player p : me.getOpponents()) { if (!p.hasLost()) opps.add(p); }
            opps.sort((x, y) -> Integer.compare(x.getLife(), y.getLife()));
            for (Card a : combat.getAttackers()) {
                forge.game.player.Player d = combat.getDefenderPlayerByAttacker(a);
                if (d != null && opps.remove(d)) opps.add(0, d);
            }
            for (forge.game.player.Player p : opps) {
                if (!forge.game.combat.CombatUtil.canAttack(c, p)) continue;
                combat.addAttacker(c, p);
                String why = AiPatches.CaptainPolicy.attackReason(combat, c);
                // Only for a kill or when suited up: adding him for every safe attack made him
                // attack 88% of turns (user 67%) and get removed more often.
                if (why != null && !(why.contains("lethal") || why.startsWith("suited up"))) why = null;
                if (why != null) {
                    if (real) System.out.println("ATTACKAI Captain America added to the attack (" + why + ")");
                    break;
                }
                combat.removeFromCombat(c);
            }
        }
    }

    /** Forge also calls declareAttackers to predict combat; only act on the real one. */
    private static boolean isRealCombat(Combat combat) {
        try {
            return combat.getAttackingPlayer().getGame().getCombat() == combat;
        } catch (Throwable t) {
            return false;
        }
    }
}
