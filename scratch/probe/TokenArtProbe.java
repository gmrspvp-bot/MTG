import java.io.File;
import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * Token pictures on the 3D table (2026-10-22). Forge files token pictures by code -
 * tokens\PIP\16_c_3_3_a_robot.jpg - so CardArt's name lookup never found one. Now StateReader
 * passes each card's image key and CardArt resolves it with Forge's own ImageKeys.
 *
 * Board: a real card on the battlefield, plus Robot and Treasure tokens built from their scripts. For each, prints the image key and the file
 * CardArt.fileForKey finds; PASS when the Robot and the Treasure resolve to a file in the token
 * cache. Run from forge-installer-2.0.15 with ui3d\classes, the tools classes and both jars.
 */
public class TokenArtProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"House", "Opp"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "TokenArtProbe").createGame();
        game.setAge(GameStage.Play);
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=3", "activeplayer=ai", "activephase=MAIN1", "humanlife=40", "ailife=40",
            "humanbattlefield=Sol Ring", "aibattlefield=Mountain"));
        st.applyToGame(game);
        Thread.sleep(1500);
        mtg3d.CardArt art = new mtg3d.CardArt(mtg3d.CardArt.defaultRoot());
        boolean robot = false, treasure = false;
        Player me = game.getPlayers().get(0);
        List<Card> cards = new ArrayList<>(me.getCardsIn(ZoneType.Battlefield));
        // The tokens Mr House makes, built the way Forge builds them when an effect creates one.
        for (String script : new String[]{"c_3_3_a_robot", "c_a_treasure_sac"}) {
            Card host = cards.get(0);
            game.getAction().invoke(() -> cards.add(forge.game.card.token.TokenInfo.getProtoType(script,
                new forge.game.spellability.SpellAbility.EmptySa(host), me)));
        }
        Thread.sleep(500);
        for (Card c : cards) {
            String key = c.getView().getCurrentState().getImageKey();
            File f = art.fileForKey(key);
            System.out.println(c.getName() + (c.isToken() ? " (token)" : "") + "  key=" + key + "  ->  " + (f == null ? "none" : f.getPath()));
            if (f != null && c.isToken() && c.getName().contains("Robot")) robot = true;
            if (f != null && c.isToken() && c.getName().contains("Treasure")) treasure = true;
        }
        boolean ok = robot && treasure;
        System.out.println(ok ? "PASS" : "FAIL (robot " + robot + ", treasure " + treasure + ")");
        System.exit(ok ? 0 : 1);
    }
}
