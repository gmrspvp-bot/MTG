import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * ClientRecorder (records a JOINED game on the joining PC) without a network: a local game's
 * GameView stands in for the host's views and a stub for Forge's client window. Plays a land,
 * puts a creature in, changes a life total, kills the creature, ends the game, then checks the
 * written log. Run from forge-installer-2.0.15 with -Dforge.playlog.dir=<empty folder>.
 */
public class ClientRecorderTest {
    static boolean pass = true;

    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("Me")).setPlayer(GamePlayerUtil.createAiPlayer("Me")));
        players.add(new RegisteredPlayer(new Deck("Host")).setPlayer(GamePlayerUtil.createAiPlayer("Host")));
        Game game = new Match(new GameRules(GameType.Commander), players, "ClientRecorderTest").createGame();
        Player me = game.getPlayers().get(0), host = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me, 3);
        Card hand = card(game, me, "Lightning Bolt");
        game.getAction().invoke(() -> game.getAction().moveTo(forge.game.zone.ZoneType.Hand, hand, null, null));
        Thread.sleep(300);
        final GameView gv = game.getView();
        // devModeSet sets the turn number but not the view's copy of it (package-private update).
        java.lang.reflect.Method ut = GameView.class.getDeclaredMethod("updateTurn", forge.game.phase.PhaseHandler.class);
        ut.setAccessible(true);
        ut.invoke(gv, game.getPhaseHandler());

        IGuiGame window = (IGuiGame) java.lang.reflect.Proxy.newProxyInstance(
            ClientRecorderTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
            (p, m, args) -> {
                if (m.getName().equals("getGameView")) return gv;
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                return null;
            });
        IGuiGame client = ClientRecorder.wrap(window);
        client.setOriginalGameController(me.getView(), null);
        client.setGameView(gv);
        System.out.println("  turn in view: " + gv.getTurn() + ", phase " + gv.getPhase());

        play(game, me, "Forest");
        client.updateZones(Collections.emptyList());
        play(game, host, "Grizzly Bears");
        client.updateZones(Collections.emptyList());
        game.getAction().invoke(() -> host.setLife(33, null));
        Thread.sleep(200);
        client.updateLives(Collections.emptyList());
        Card bears = host.getCardsIn(forge.game.zone.ZoneType.Battlefield).get(0);
        game.getAction().invoke(() -> game.getAction().moveToGraveyard(bears, null, null));
        Thread.sleep(200);
        client.updateZones(Collections.emptyList());
        client.finishGame();

        java.io.File dir = new java.io.File(System.getProperty("forge.playlog.dir"));
        java.io.File[] logs = dir.listFiles((d, n) -> n.startsWith("game_") && n.endsWith("_joined.log"));
        check("log written", logs != null && logs.length == 1, logs == null ? "none" : String.valueOf(logs.length));
        if (logs == null || logs.length == 0) { System.out.println("FAIL"); System.exit(1); }
        List<String> L = java.nio.file.Files.readAllLines(logs[0].toPath());
        for (String l : L) System.out.println("    | " + l);
        check("our seat first, as HUMAN-or-AI line", L.get(0).startsWith("Player: Me (HUMAN)") &&L.get(0).contains("[this PC]"), L.get(0));
        check("turn line", L.stream().anyMatch(l -> l.matches("Turn: Turn \\d+ \\(Me\\)")), "");
        check("BOARD with our hand", L.stream().anyMatch(l -> l.startsWith("Information: BOARD") && l.contains("| Me |") && l.contains("Lightning Bolt(1)")), "");
        check("other hands hidden", L.stream().anyMatch(l -> l.startsWith("Information: BOARD") && l.contains("| Host |") && l.endsWith("hand cards: hidden")), "");
        check("land played", L.stream().anyMatch(l -> l.matches("Land: Me played Forest \\(\\d+\\)")), "");
        check("creature entered", L.stream().anyMatch(l -> l.startsWith("Zone Change: Grizzly Bears") && l.contains("onto the Battlefield under Host")), "");
        check("life change", L.contains("Life: Life: Host 20 > 33"), "");
        check("creature died", L.stream().anyMatch(l -> l.startsWith("Zone Change: Grizzly Bears") && l.contains("into Graveyard from Battlefield")), "");
        check("result line", L.get(L.size() - 1).startsWith("Game Result:"), L.get(L.size() - 1));

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static Card card(Game game, Player p, String name) {
        return Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
    }

    static void play(Game game, Player p, String name) throws Exception {
        Card c = card(game, p, name);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        Thread.sleep(200);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-36s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }
}
