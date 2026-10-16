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

// Can Codsworth's attach ability target Iron Man? Plain, then wearing Whispersilk Cloak (shroud).
public class CodsProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "CodsProbe").createGame();
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=human", "activephase=MAIN1", "humanlife=40", "ailife=40",
            "humanbattlefield=Codsworth, Handy Helper;Iron Man, Titan of Innovation;Colossus Hammer;Whispersilk Cloak;Grizzly Bears", "aibattlefield=Hill Giant"));
        st.applyToGame(game);
        Thread.sleep(3000);
        Player p0 = game.getPlayers().get(0);
        Card cods = null, iron = null, hammer = null, cloak = null, bear = null;
        for (Card c : p0.getCardsIn(ZoneType.Battlefield)) {
            switch (c.getName()) {
                case "Codsworth, Handy Helper": cods = c; break;
                case "Iron Man, Titan of Innovation": iron = c; break;
                case "Colossus Hammer": hammer = c; break;
                case "Whispersilk Cloak": cloak = c; break;
                case "Grizzly Bears": bear = c; break;
            }
        }
        SpellAbility pump = null;
        for (SpellAbility sa : cods.getSpellAbilities()) { if (sa.getApi() == forge.game.ability.ApiType.Pump) pump = sa; }
        pump.setActivatingPlayer(p0);
        SpellAbility attach = pump.getSubAbility();
        attach.setActivatingPlayer(p0);
        System.out.println("pump can target Hammer: " + pump.canTarget(hammer));
        System.out.println("attach can target Iron Man: " + attach.canTarget(iron) + "   Bears: " + attach.canTarget(bear));
        final Card fc = cloak, fi = iron;
        game.getAction().invoke(() -> { fc.attachToEntity(fi, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(1500);
        System.out.println("Iron Man with Whispersilk Cloak, shroud=" + iron.hasKeyword(forge.game.keyword.Keyword.SHROUD)
            + " -> attach can target Iron Man: " + attach.canTarget(iron));
        System.exit(0);
    }
}
