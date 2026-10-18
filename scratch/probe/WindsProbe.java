import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// How Forge represents Winds of Abandon's overload: list every spell ability with its API and flags.
public class WindsProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "WindsProbe").createGame();
        Player me = game.getPlayers().get(0);
        Card w = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Winds of Abandon"), me);
        for (SpellAbility sa : w.getAllSpellAbilities()) {
            System.out.println("SA api=" + sa.getApi() + " class=" + sa.getClass().getSimpleName() + " overload=" + sa.isKeyword(forge.game.keyword.Keyword.OVERLOAD)
                + " alt=" + sa.isAlternativeCost(forge.game.spellability.AlternativeCost.Overload) + " targets=" + sa.usesTargeting() + " cost=" + sa.getPayCosts()
                + " params=" + sa.getMapParams());
        }
        System.exit(0);
    }
}
