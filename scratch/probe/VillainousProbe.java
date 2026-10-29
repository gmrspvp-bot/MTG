import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * Villainous choice and rule 701.55b (VillainousChoiceHook, CARD-MECHANICS.md 2026-10-29).
 * Player 0 has Dr. Eggman and Myr Battlesphere (a Construct) in hand; the opponent's hand is
 * set by the first argument. Eggman's end-step trigger is resolved directly, then the board is
 * printed: with an empty hand the opponent must not get to "discard", so the Battlesphere comes in.
 *
 *   VillainousProbe empty|card   [dalek: The Dalek Emperor's sacrifice, opponent with no creatures]
 *
 * Run from forge-installer-2.0.15 with the evalcache agent (the hook lives there), the Forge jar
 * and scripts\tools\classes on the classpath. -Dforge.villainous.legal=false shows Forge's own.
 */
public class VillainousProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        String which = a.length > 0 ? a[0] : "empty";
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("Eggman")).setPlayer(GamePlayerUtil.createAiPlayer("Eggman")));
        players.add(new RegisteredPlayer(new Deck("Other")).setPlayer(GamePlayerUtil.createAiPlayer("Other")));
        Game game = new Match(new GameRules(GameType.Commander), players, "VillainousProbe").createGame();
        game.setAge(GameStage.Play);
        boolean dalek = which.equals("dalek");
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=human", "activephase=END_OF_TURN",
            "humanlife=40", "ailife=40",
            "humanbattlefield=" + (dalek ? "The Dalek Emperor" : "Dr. Eggman") + ";Island;Island;Island",
            "humanhand=Myr Battlesphere",
            "humanlibrary=Island;Island;Island;Island",
            "aibattlefield=Mountain;Mountain" + (which.equals("card") ? ";Grizzly Bears" : ""),
            "aihand=" + (which.equals("card") ? "Memnite;Shock" : ""),
            "ailibrary=Island;Island;Island;Island"));
        st.applyToGame(game);
        Thread.sleep(1500);
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        Card host = null;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals(dalek ? "The Dalek Emperor" : "Dr. Eggman")) host = c;
        SpellAbility vc = null;
        if (dalek) {
            // The Dalek Emperor: its villainous choice sits on an attack trigger; resolve that sub-ability.
            for (Trigger t : host.getTriggers()) {
                SpellAbility s = t.ensureAbility();
                for (SpellAbility x = s; x != null; x = x.getSubAbility())
                    if (x.getApi() == forge.game.ability.ApiType.VillainousChoice) vc = x;
            }
        } else {
            for (Trigger t : host.getTriggers()) vc = t.ensureAbility();
        }
        if (vc == null) { System.out.println("RESULT no villainous choice found"); System.exit(1); }
        vc.setActivatingPlayer(me);
        System.out.println("BEFORE opp hand=" + opp.getCardsIn(ZoneType.Hand) + " my hand=" + me.getCardsIn(ZoneType.Hand));
        AbilityUtils.resolve(vc);
        System.out.println("AFTER  opp hand=" + opp.getCardsIn(ZoneType.Hand) + " opp gy=" + opp.getCardsIn(ZoneType.Graveyard)
            + " my hand=" + me.getCardsIn(ZoneType.Hand));
        boolean in = false;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals("Myr Battlesphere")) in = true;
        System.out.println("RESULT " + which + ": Battlesphere on battlefield=" + in);
        System.exit(0);
    }
}
