// Forced sacrifices ("that player sacrifices an artifact", edicts, Damocles Base...):
// EvalCacheAgent routes forge.ai.ComputerUtil.choosePermanentsToSacrifice here (original renamed
// choosePermanentsToSacrifice$orig). In 1,560 games Mr House v4 gave up real cards 58 times and
// tokens 18 times when Killmonger / Damocles Base made him sacrifice - Mr. House himself 17 times,
// Sol Ring, Skullclamp - while Treasures and Robot tokens were usually there. With "humanlike":
// tokens go first (cheapest first), then real cards in Forge's own order, the commander last.
// Logged as "SACAI ...".

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public final class SacrificeHook {
    private SacrificeHook() {}

    private static volatile MethodHandle orig;

    public static CardCollection choosePermanentsToSacrifice(Player ai, CardCollectionView list, int amount, SpellAbility source,
                                                            boolean destroy, boolean isOptional) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.lookup().findStatic(forge.ai.ComputerUtil.class, "choosePermanentsToSacrifice$orig",
                MethodType.methodType(CardCollection.class, Player.class, CardCollectionView.class, int.class,
                    SpellAbility.class, boolean.class, boolean.class));
            orig = h;
        }
        CardCollection chosen = (CardCollection) h.invokeExact(ai, list, amount, source, destroy, isOptional);
        if (!HumanHooks.enabled || chosen == null || chosen.isEmpty() || list == null) return chosen;
        try {
            return reorder(ai, list, chosen);
        } catch (Throwable t) {
            return chosen;   // never break the game
        }
    }

    static int value(Card c) {
        if (c.isCreature()) return forge.ai.ComputerUtilCard.evaluateCreature(c);
        return 100 + c.getCMC() * 20;
    }

    static CardCollection reorder(Player ai, CardCollectionView list, CardCollection chosen) {
        java.util.List<Card> tokens = new java.util.ArrayList<>(), real = new java.util.ArrayList<>(), commanders = new java.util.ArrayList<>();
        for (Card c : list) {
            if (c.isCommander()) commanders.add(c);
            else if (c.isToken()) tokens.add(c);
        }
        // Cheapest first; on a tie keep Forge's own pick (it chose a Treasure over a Sword token).
        tokens.sort((a, b) -> value(a) != value(b) ? Integer.compare(value(a), value(b))
            : Boolean.compare(!chosen.contains(a), !chosen.contains(b)));
        for (Card c : chosen) { if (!c.isToken() && !c.isCommander()) real.add(c); }       // Forge's own picks first
        for (Card c : list) { if (!c.isToken() && !c.isCommander() && !real.contains(c)) real.add(c); }
        java.util.List<Card> order = new java.util.ArrayList<>(tokens);
        order.addAll(real);
        order.addAll(commanders);
        CardCollection out = new CardCollection();
        for (int i = 0; i < chosen.size() && i < order.size(); i++) out.add(order.get(i));
        boolean changed = false;
        for (Card c : out) { if (!chosen.contains(c)) changed = true; }
        if (changed) {
            StringBuilder was = new StringBuilder(), now = new StringBuilder();
            for (Card c : chosen) was.append(was.length() > 0 ? ", " : "").append(c.getName());
            for (Card c : out) now.append(now.length() > 0 ? ", " : "").append(c.getName());
            System.out.println("SACAI " + ai.getName() + ": sacrifices " + now + " instead of " + was);
        }
        return out;
    }
}
