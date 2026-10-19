import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// HumanHooks.threat: a suited-up Captain America board vs a board of four angels.
public class ThreatProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"Cap", "Angels"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "ThreatProbe").createGame();
        Player cap = game.getPlayers().get(0), ang = game.getPlayers().get(1);
        Card c = put(game, cap, "Captain America, First Avenger"); c.setCommander(true);
        List<Card> gear = new ArrayList<>();
        for (String n : new String[]{"Colossus Hammer", "Sword of War and Peace", "Sword of Feast and Famine", "Champion's Helm", "Whispersilk Cloak"}) gear.add(put(game, cap, n));
        put(game, cap, "Sol Ring"); put(game, cap, "Arcane Signet");
        game.getAction().invoke(() -> { for (Card g : gear) g.attachToEntity(c, null); game.getAction().checkStaticAbilities(); }); Thread.sleep(800);
        Card s = put(game, ang, "Seluma, Light of Aysen"); s.setCommander(true);
        for (String n : new String[]{"Lyra Dawnbringer", "Serra Angel", "Baneslayer Angel"}) put(game, ang, n);
        game.getAction().invoke(() -> game.getAction().checkStaticAbilities()); Thread.sleep(500);
        System.out.println("Cap " + c.getNetPower() + "/" + c.getNetToughness() + " with 5 gear: threat " + Math.round(HumanHooks.threat(cap)));
        System.out.println("Angels board (Seluma, Lyra, Serra, Baneslayer): threat " + Math.round(HumanHooks.threat(ang)));
        System.exit(0);
    }
    static Card put(Game game, Player p, String name) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        Thread.sleep(200);
        return c;
    }
}
