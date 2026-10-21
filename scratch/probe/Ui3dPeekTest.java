package mtg3d;

import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * Opened zones (2026-10-21): openZones(viewer, zones, players) must mirror the zones of the
 * players in the MAP (args[2]), not of args[0] (the viewer) - looking at "Rosa's hand" showed
 * your own. Also: the call is counted in the fallback tally as a Forge window.
 * Run from forge-installer-2.0.15 with ui3d\classes and the Forge jars on the classpath.
 */
public class Ui3dPeekTest {
    static boolean pass = true;

    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("Me")).setPlayer(GamePlayerUtil.createAiPlayer("Me")));
        players.add(new RegisteredPlayer(new Deck("Rosa")).setPlayer(GamePlayerUtil.createAiPlayer("Rosa")));
        Game game = new Match(new GameRules(GameType.Commander), players, "Ui3dPeekTest").createGame();
        Player me = game.getPlayers().get(0), rosa = game.getPlayers().get(1);
        hand(game, me, "Lightning Bolt");
        hand(game, rosa, "Counterspell");
        hand(game, rosa, "Ponder");
        Thread.sleep(300);

        final List<String> forgeCalls = Collections.synchronizedList(new ArrayList<>());
        IGuiGame window = (IGuiGame) java.lang.reflect.Proxy.newProxyInstance(
            Ui3dPeekTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
            (p, m, args) -> { forgeCalls.add(m.getName()); return m.getReturnType() == boolean.class ? false : m.getReturnType() == int.class ? 0 : null; });
        java.lang.reflect.Constructor<?> c = Gui3D.class.getDeclaredConstructor(IGuiGame.class, forge.player.PlayerControllerHuman.class);
        c.setAccessible(true);
        IGuiGame g = (IGuiGame) java.lang.reflect.Proxy.newProxyInstance(Ui3dPeekTest.class.getClassLoader(),
            new Class<?>[] { IGuiGame.class }, (java.lang.reflect.InvocationHandler) c.newInstance(window, null));

        // The 3D window has to be drawing for it to take the call over (Handoff heartbeat).
        Thread early = new Thread(() -> { for (int i = 0; i < 40; i++) { Handoff.alive(); try { Thread.sleep(40); } catch (InterruptedException e) { return; } } });
        early.setDaemon(true);
        early.start();
        Thread.sleep(120);
        Map<PlayerView, Object> whose = new HashMap<>();
        whose.put(rosa.getView(), null);
        g.openZones(me.getView(), Collections.singletonList(ZoneType.Hand), whose);
        Gui3D.Peek peek = Gui3D.pendingPeek();
        check("a mirror opened", peek != null, "");
        if (peek != null) {
            check("it is Rosa's hand, not ours", peek.title.startsWith("Rosa") && peek.items.size() == 2,
                peek.title + ", " + peek.items.size() + " cards");
        }
        // Changed by Xenil's Claude, 2026-10-21: openZones is now TAKEN OVER, not merely
        // mirrored - the mirror has the cards, so Forge's floating window is swallowed and
        // never appears. It is therefore no longer a Forge window pop, and counting it as one
        // would put a line in every bug report saying the player had been sent to a window
        // they were not sent to. The assertion is inverted rather than deleted so the next
        // person can see which way round it is meant to be.
        check("NOT counted as a Forge window (we swallow it)",
            !Gui3D.fallbackSummary().contains("openZones"), Gui3D.fallbackSummary());

        // A library search as the owner met it (Path to Exile, 2026-10-21): Forge offers library
        // cards through setSelectables and its window opens a floating library window. With the
        // 3D window drawing, the cards open in the 3D panel instead and Forge's window is not told.
        Card plains = card(game, me, "Plains", ZoneType.Library);
        Card island = card(game, me, "Island", ZoneType.Library);
        Thread beat = new Thread(() -> { while (true) { Handoff.alive(); try { Thread.sleep(40); } catch (InterruptedException e) { return; } } });
        beat.setDaemon(true);
        beat.start();
        Thread.sleep(150);
        Gui3D.dismissPeek();
        forgeCalls.clear();
        g.setSelectables(Arrays.asList(plains.getView(), island.getView()), 0, 1);
        Gui3D.Peek search = Gui3D.pendingPeek();
        check("library search shown in 3D", search != null && search.fromSelectables && search.items.size() == 2,
            search == null ? "none" : search.title + ", " + search.items.size());
        check("Forge's floating window held back", !forgeCalls.contains("setSelectables"), forgeCalls.toString());
        Gui3D.releaseSelectables();
        Thread.sleep(400);
        check("H shows it in Forge's window too", forgeCalls.contains("setSelectables"), forgeCalls.toString());
        g.clearSelectables();
        check("cleared -> the 3D search closes", Gui3D.pendingPeek() == null, "");
        beat.interrupt();
        Thread.sleep(200);
        forgeCalls.clear();
        // Deterministic: "the 3D window has not drawn" (sleeping on thread timing was flaky).
        java.lang.reflect.Field lb = Handoff.class.getDeclaredField("lastBeat"); lb.setAccessible(true); lb.set(null, 0L);
        g.setSelectables(Arrays.asList(plains.getView()), 0, 1);
        check("3D window not drawing -> Forge shows it", forgeCalls.contains("setSelectables"), forgeCalls.toString());

        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static Card card(Game game, Player p, String name, ZoneType z) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveTo(z, c, null, null));
        Thread.sleep(150);
        return c;
    }

    static void hand(Game game, Player p, String name) throws Exception {
        Card card = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveTo(ZoneType.Hand, card, null, null));
        Thread.sleep(150);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-34s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }
}
