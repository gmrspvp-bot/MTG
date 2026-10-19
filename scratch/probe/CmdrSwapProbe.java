import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// Captain America's player aims Swords to Plowshares at the opposing commander Seluma; with
// Lyra Dawnbringer next to it, the removal should move to Lyra. Run with -Dforge.aipatches=threat,captain.
public class CmdrSwapProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "CmdrSwapProbe").createGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        Card cap = add(game, me, "Captain America, First Avenger"); cap.setCommander(true); me.addCommander(cap);
        Card seluma = add(game, opp, "Seluma, Light of Aysen"); seluma.setCommander(true);
        Card lyra = add(game, opp, "Lyra Dawnbringer");
        Card stp = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Swords to Plowshares"), me);
        game.getAction().invoke(() -> game.getAction().moveTo(ZoneType.Hand, stp, null, null)); Thread.sleep(300);
        SpellAbility sa = stp.getFirstSpellAbility(); sa.setActivatingPlayer(me);
        sa.resetTargets(); sa.getTargets().add(seluma);
        System.out.println("values: Seluma " + forge.ai.ComputerUtilCard.evaluateCreature(seluma) + ", Lyra " + forge.ai.ComputerUtilCard.evaluateCreature(lyra));
        forge.ai.AiAbilityDecision d = AiPatches.ThreatRetarget.apply(me, sa, new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay), true);
        System.out.println("decision " + d + " -> target " + sa.getTargets().getTargetCards());
        System.exit(0);
    }
    static Card add(Game game, Player p, String name) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        Thread.sleep(250);
        return c;
    }
}
