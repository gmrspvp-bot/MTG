// "Attack with nobody?" for the human player (desktop games). EvalCacheAgent routes
// forge.gamemodes.match.input.InputAttack.onOk() here (original renamed onOk$orig). The user
// clicked through a declare-attackers step with a fully equipped Captain America and attacked
// with nothing. If no attackers are declared while untapped creatures with power could attack,
// ask first; No keeps the attack declaration open.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.gamemodes.match.input.InputAttack;

public final class AttackConfirmHook {
    private AttackConfirmHook() {}

    private static volatile MethodHandle orig, combatField, playerField;

    public static void onOk(InputAttack input) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(InputAttack.class, MethodHandles.lookup());
            orig = l.findVirtual(InputAttack.class, "onOk$orig", MethodType.methodType(void.class));
            combatField = l.findGetter(InputAttack.class, "combat", Combat.class);
            playerField = l.findGetter(InputAttack.class, "playerAttacks", Player.class);
        }
        String text = null;
        try {
            Combat combat = (Combat) combatField.invoke(input);
            Player me = (Player) playerField.invoke(input);
            if (combat != null && me != null && combat.getAttackers().isEmpty()) {
                java.util.List<String> could = new java.util.ArrayList<>();
                for (Card c : me.getCreaturesInPlay()) {
                    if (!c.isTapped() && c.getNetCombatDamage() > 0 && CombatUtil.canAttack(c) && could.size() < 4) {
                        could.add(c.getName() + " (" + c.getNetPower() + "/" + c.getNetToughness() + ")");
                    }
                }
                if (!could.isEmpty()) text = "You're not attacking with anything.\nYou could attack with: "
                    + String.join(", ", could) + ".\n\nSkip combat anyway?";
            }
        } catch (Throwable t) {
            text = null;   // never block the game because of our own error
        }
        if (text != null && !ConfirmPassHook.confirm(text)) return;   // stay in declare attackers
        orig.invoke(input);
    }
}
