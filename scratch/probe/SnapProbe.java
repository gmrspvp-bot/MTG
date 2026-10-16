import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

public class SnapProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "SnapProbe").createGame();
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=human", "activephase=MAIN1", "humanlife=40", "ailife=40",
            "humanbattlefield=Grizzly Bears;Forest;Forest", "humanhand=Llanowar Elves;Giant Growth", "aibattlefield=Hill Giant;Mountain"));
        st.applyToGame(game);
        Thread.sleep(3000);
        Player p0 = game.getPlayers().get(0);
        String before = describe(game);
        GameSnapshot snap = new GameSnapshot(game);
        snap.makeCopy();
        // change things: life, move a card from hand to graveyard, tap lands
        game.getAction().invoke(() -> {
            p0.setLife(13, null);
            forge.game.card.Card elf = p0.getCardsIn(ZoneType.Hand).get(0);
            game.getAction().moveToGraveyard(elf, null, null);
            for (forge.game.card.Card c : p0.getCardsIn(ZoneType.Battlefield)) c.tap(true, null, null);
        });
        Thread.sleep(1500);
        String changed = describe(game);
        snap.restoreGameState(game);
        Thread.sleep(1500);
        String after = describe(game);
        System.out.println("BEFORE  " + before);
        System.out.println("CHANGED " + changed);
        System.out.println("AFTER   " + after);
        System.out.println(before.equals(after) ? "RESULT: restored OK" : "RESULT: DIFFERENT");
        System.exit(0);
    }

    static String describe(Game g) {
        StringBuilder sb = new StringBuilder();
        for (Player p : g.getPlayers()) {
            sb.append(p.getName()).append(" life=").append(p.getLife()).append(" hand=").append(p.getCardsIn(ZoneType.Hand).size())
              .append(" gy=").append(p.getCardsIn(ZoneType.Graveyard).size()).append(" bf=");
            int tapped = 0;
            for (forge.game.card.Card c : p.getCardsIn(ZoneType.Battlefield)) { sb.append(c.getName()).append(','); if (c.isTapped()) tapped++; }
            sb.append(" tapped=").append(tapped).append(" | ");
        }
        sb.append("phase=").append(g.getPhaseHandler().getPhase()).append(" turn=").append(g.getPhaseHandler().getTurn());
        return sb.toString();
    }
}

