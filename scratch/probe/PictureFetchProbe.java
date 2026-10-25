package mtg3d;

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
 * Pictures on demand (2026-10-26): a token with no cached picture is fetched through Forge's own
 * downloader by CardArt.fetchSoon, and CardArt finds it once it lands. Builds the tokens from
 * their scripts (as an effect would), checks they have no picture, asks for them, and waits up
 * to 40 s. Needs the internet (Scryfall). Run from forge-installer-2.0.15 with ui3d\classes, the
 * tools classes and both jars. Args: token scripts (default w_2_2_cat w_2_2_cat_flying).
 */
public class PictureFetchProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        String[] scripts = a.length > 0 ? a : new String[] { "w_2_2_cat", "w_2_2_cat_flying" };
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] { "Me", "Opp" }) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "PictureFetchProbe").createGame();
        game.setAge(GameStage.Play);
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=3", "activeplayer=ai", "activephase=MAIN1", "humanbattlefield=Sol Ring", "aibattlefield=Mountain"));
        st.applyToGame(game);
        Thread.sleep(1500);
        Player me = game.getPlayers().get(0);
        Card host = me.getCardsIn(ZoneType.Battlefield).get(0);
        List<String> keys = new ArrayList<>();
        for (String s : scripts) {
            List<Card> made = new ArrayList<>();
            game.getAction().invoke(() -> made.add(forge.game.card.token.TokenInfo.getProtoType(s,
                new forge.game.spellability.SpellAbility.EmptySa(host), me)));
            Thread.sleep(300);
            keys.add(made.get(0).getView().getCurrentState().getImageKey());
        }
        CardArt art = new CardArt(CardArt.defaultRoot());
        int missing = 0;
        for (String k : keys) {
            File f = art.fileForKey(k);
            System.out.println(k + "  cached before: " + (f != null));
            if (f == null) { missing++; art.fetchSoon(k); }
        }
        if (missing == 0) { System.out.println("all already cached - pick other scripts"); System.exit(2); }
        long until = System.currentTimeMillis() + 40_000;
        int found = 0;
        while (System.currentTimeMillis() < until && found < keys.size()) {
            Thread.sleep(1000);
            found = 0;
            for (String k : keys) if (art.fileForKey(k) != null) found++;
        }
        for (String k : keys) System.out.println(k + "  ->  " + art.fileForKey(k));
        System.out.println("fetched by Forge for the table: " + art.picturesFetched());
        boolean ok = found == keys.size();
        System.out.println(ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
