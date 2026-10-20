// House rule (owner, 2026-10-21): a die that an "ignore the lowest roll" effect throws away
// (Wyll, Blade of Frontiers, and any card like it) was still ROLLED, so cards that care
// "whenever you roll" (Mr. House, President and CEO, and any card like it) see it too - except
// for the roll their own ability made. Example: Clown Car rolls 4 dice, Wyll makes it 5 and
// ignores the lowest; Clown Car uses the 4 it keeps, Mr. House triggers on all 5. When Mr. House's
// own {4},{T} ability rolls, Wyll's extra die stays ignored for him.
//
// Forge drops the ignored dice inside RollDiceEffect.rollAction and never fires a trigger for
// them. EvalCacheAgent routes two private statics of RollDiceEffect here:
//   rollDiceForPlayer(sa, player, ...)  - remembers which ability is rolling (thread-local);
//   rollAction(..., ignored, ...)       - after the original, for each die it put in `ignored`,
//                                         fires the same RolledDie trigger Forge fires for a kept
//                                         die, marked with Cause = the rolling card;
// and TriggerRolledDie.performTest      - refuses a marked die for the card that rolled it.
// -Dforge.dice.seeignored=false turns the rule off.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;

public final class DiceIgnoreHook {
    private DiceIgnoreHook() {}

    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("forge.dice.seeignored", "true"));

    private static volatile MethodHandle origRollFor, origRollAction;
    private static final ThreadLocal<SpellAbility> ROLLING = new ThreadLocal<>();

    private static MethodHandles.Lookup lookup() throws IllegalAccessException {
        return MethodHandles.privateLookupIn(RollDiceEffect.class, MethodHandles.lookup());
    }

    public static int rollDiceForPlayer(SpellAbility sa, Player player, int amount, int sides, int ignore,
                                        int modifier, List<Integer> rollsResult, boolean toVisitAttractions) throws Throwable {
        if (origRollFor == null) {
            origRollFor = lookup().findStatic(RollDiceEffect.class, "rollDiceForPlayer$orig",
                MethodType.methodType(int.class, SpellAbility.class, Player.class, int.class, int.class, int.class,
                    int.class, List.class, boolean.class));
        }
        SpellAbility before = ROLLING.get();
        ROLLING.set(sa);
        try {
            return (int) origRollFor.invokeExact(sa, player, amount, sides, ignore, modifier, rollsResult, toVisitAttractions);
        } finally {
            ROLLING.set(before);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static List rollAction(int amount, int sides, int ignore, List naturalRolls, List ignored,
                                  Map ignoreChosen, Set dicePTExchanges, Player player, Map repParams) throws Throwable {
        if (origRollAction == null) {
            origRollAction = lookup().findStatic(RollDiceEffect.class, "rollAction$orig",
                MethodType.methodType(List.class, int.class, int.class, int.class, List.class, List.class,
                    Map.class, Set.class, Player.class, Map.class));
        }
        int had = ignored == null ? 0 : ignored.size();
        List kept = (List) origRollAction.invokeExact(amount, sides, ignore, naturalRolls, ignored, ignoreChosen, dicePTExchanges, player, repParams);
        if (ON && ignored != null && ignored.size() > had && player != null) {
            try {
                seeIgnored(player, sides, ignored.subList(had, ignored.size()));
            } catch (Throwable t) {
                System.out.println("DICEIGNORE: could not report the ignored dice (" + t + ")");
            }
        }
        return kept;
    }

    private static void seeIgnored(Player player, int sides, List<Integer> dice) {
        SpellAbility sa = ROLLING.get();
        Card source = sa == null ? null : sa.getHostCard();
        for (Integer die : dice) {
            if (die == null) continue;
            // The same parameters Forge's own RolledDie trigger carries for a kept die, plus Cause =
            // the card that rolled. Forge's own rolls never set Cause on this trigger, so it marks
            // an ignored die; performTest below uses it to keep that card from seeing its own.
            Map<AbilityKey, Object> run = AbilityKey.mapFromPlayer(player);
            run.put(AbilityKey.Sides, sides);
            run.put(AbilityKey.Result, die);
            run.put(AbilityKey.NaturalResult, die);
            run.put(AbilityKey.RolledToVisitAttractions, false);
            run.put(AbilityKey.Number, player.getNumRollsThisTurn());
            run.put(AbilityKey.Cause, source == null ? IGNORED_DIE : source);
            player.getGame().getTriggerHandler().runTrigger(TriggerType.RolledDie, run, false);
            System.out.println("DICEIGNORE: " + player.getName() + "'s ignored roll of " + die
                + (source == null ? "" : " (" + source.getName() + ")") + " seen by roll triggers"
                + (source == null ? "" : ", not by " + source.getName() + " itself"));
        }
    }

    /** Cause for an ignored die whose rolling card is unknown (nobody is left out). */
    private static final Object IGNORED_DIE = new Object();

    private static volatile MethodHandle origTest;

    /**
     * TriggerRolledDie.performTest, routed here: an ignored die (Cause set by seeIgnored) is not
     * seen by the card whose own ability rolled it. Checked when Forge evaluates the trigger -
     * which, while something is resolving, is later than the roll - so it works whenever that is.
     */
    public static boolean performTest(forge.game.trigger.TriggerRolledDie self, Map<AbilityKey, Object> runParams) throws Throwable {
        if (origTest == null) {
            origTest = MethodHandles.privateLookupIn(forge.game.trigger.TriggerRolledDie.class, MethodHandles.lookup())
                .findVirtual(forge.game.trigger.TriggerRolledDie.class, "performTest$orig", MethodType.methodType(boolean.class, Map.class));
        }
        Object cause = runParams == null ? null : runParams.get(AbilityKey.Cause);
        if (cause instanceof Card && self.getHostCard() != null && ((Card) cause).getId() == self.getHostCard().getId()) return false;
        return (boolean) origTest.invoke(self, runParams);
    }
}
