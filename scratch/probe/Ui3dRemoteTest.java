package mtg3d;

import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * The 3D table following a JOINED network game, without a network: a real GameView (from a
 * local game) stands in for what the host sends, a stub stands in for Forge's client window, and
 * a recording IGameController stands in for the network controller. Checks:
 *   - the wrapper is installed and forwards to Forge's window
 *   - a snapshot is built from the view (seats, our hand, the battlefield)
 *   - the board mode is inferred from the prompt text (declare attackers)
 *   - OK, a card click and a player click go to the network controller
 *   - a click pressed before the prompt changed is dropped
 * Run from forge-installer-2.0.15 with -Dforge.ui3d.nowindow=true.
 */
public class Ui3dRemoteTest {
    static boolean pass = true;
    static final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    static int forwarded;

    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("Me")).setPlayer(GamePlayerUtil.createAiPlayer("Me")));
        players.add(new RegisteredPlayer(new Deck("Host")).setPlayer(GamePlayerUtil.createAiPlayer("Host")));
        Game game = new Match(new GameRules(GameType.Commander), players, "Ui3dRemoteTest").createGame();
        Player me = game.getPlayers().get(0), host = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, me);
        Card bear = play(game, me, "Grizzly Bears");
        play(game, host, "Hill Giant");
        Card bolt = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Lightning Bolt"), me);
        game.getAction().invoke(() -> game.getAction().moveTo(forge.game.zone.ZoneType.Hand, bolt, null, null));
        Thread.sleep(300);
        final GameView gv = game.getView();

        IGuiGame forgeWindow = (IGuiGame) java.lang.reflect.Proxy.newProxyInstance(
            Ui3dRemoteTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
            (p, m, args) -> {
                forwarded++;
                if (m.getName().equals("getGameView")) return gv;
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                return null;
            });
        IGameController net = (IGameController) java.lang.reflect.Proxy.newProxyInstance(
            Ui3dRemoteTest.class.getClassLoader(), new Class<?>[] { IGameController.class },
            (p, m, args) -> {
                String what = m.getName();
                if (args != null && args.length > 0 && args[0] instanceof forge.game.card.CardView) what += ":" + ((forge.game.card.CardView) args[0]).getName();
                if (args != null && args.length > 0 && args[0] instanceof PlayerView) what += ":" + ((PlayerView) args[0]).getName();
                sent.add(what);
                return m.getReturnType() == boolean.class ? Boolean.TRUE : null;
            });

        Object w = Bridge.wrapClientGui(forgeWindow);
        check("wrapper installed", w != forgeWindow && java.lang.reflect.Proxy.isProxyClass(w.getClass()), String.valueOf(w.getClass()));
        IGuiGame client = (IGuiGame) w;

        // What a client receives from the host as the game starts.
        client.setOriginalGameController(me.getView(), net);
        client.setGameView(gv);
        forge.trackable.TrackableCollection<PlayerView> mine = new forge.trackable.TrackableCollection<>();
        mine.add(me.getView());
        client.openView(mine);
        client.showPromptMessage(me.getView(), "Declare attackers: select creatures to attack Host");
        client.updateButtons(me.getView(), "OK", "Cancel", true, true, true);
        settle();
        check("forwarded to Forge's window", forwarded >= 5, forwarded + " calls");

        Snapshot s = Bridge.latestForTest();
        check("snapshot from the host's view", s.seats.size() == 2, s.seats.size() + " seats");
        boolean meYou = false; int creatures = 0;
        for (Snapshot.SeatV seat : s.seats) { if (seat.isYou && seat.name.equals("Me")) meYou = true; creatures += seat.creatures.size(); }
        check("our seat and the battlefield", meYou && creatures == 2, "you=" + meYou + " creatures=" + creatures);
        check("our hand", s.hand.size() == 1 && s.hand.get(0).name.equals("Lightning Bolt"), String.valueOf(s.hand.size()));
        check("board mode from the prompt", "InputAttack".equals(Gui3D.PROMPT.inputName), Gui3D.PROMPT.inputName);
        check("3D side is active", Gui3D.active(), "");

        sent.clear();
        Gui3D.pressOk();
        Gui3D.clickCard(bear.getId());
        Gui3D.clickPlayer(1);
        settle();
        check("OK to the host", sent.contains("selectButtonOk"), sent.toString());
        check("card click to the host", sent.contains("selectCard:Grizzly Bears"), sent.toString());
        check("player click to the host", sent.contains("selectPlayer:Host"), sent.toString());

        // A click pressed, then the prompt changes before it is delivered: dropped.
        sent.clear();
        final Object[] done = new Object[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            Gui3D.clickCard(bear.getId());                                   // queued behind us on the EDT
            client.showPromptMessage(me.getView(), "Declare blockers");      // new prompt first
        });
        settle();
        check("stale click dropped", !sent.contains("selectCard:Grizzly Bears"), sent.toString());

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static void settle() throws Exception {
        for (int i = 0; i < 3; i++) { javax.swing.SwingUtilities.invokeAndWait(() -> { }); Thread.sleep(80); }
    }

    static Card play(Game game, Player p, String name) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        Thread.sleep(200);
        return c;
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-34s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }
}
