import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.Input;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;

/**
 * The "has priority" mark (PriorityViewHook, 2026-11-01). Two human seats with stand-in windows.
 * P0 holds priority in their main phase; P1 is then made to discard a card - the situation where
 * the yellow frame and Forge's "Waiting for" stayed on P0. Prints both seats' mark before, while
 * P1's discard prompt is open, and after it is answered. With the hook: P0 / P1 / P0. Forge
 * alone: P0 throughout. -Dforge.prioritymark=false shows Forge's own.
 *
 * Run from forge-installer-2.0.15 with the evalcache agent, the Forge jar and scripts\tools\classes.
 */
public class PriorityMarkProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("P0")).setPlayer(new forge.player.LobbyPlayerHuman("P0")));
        players.add(new RegisteredPlayer(new Deck("P1")).setPlayer(new forge.player.LobbyPlayerHuman("P1")));
        Game game = new Match(new GameRules(GameType.Commander), players, "PriorityMarkProbe").createGame();
        Object stub = java.lang.reflect.Proxy.newProxyInstance(PriorityMarkProbe.class.getClassLoader(),
            new Class<?>[] { forge.gui.interfaces.IGuiGame.class }, (p, m, args) -> {
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                return null;
            });
        for (Player p : game.getPlayers()) ((PlayerControllerHuman) p.getController()).setGui((forge.gui.interfaces.IGuiGame) stub);
        game.setAge(GameStage.Play);
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=human", "activephase=MAIN1",
            "humanlife=40", "ailife=40", "humanbattlefield=Island", "humanhand=Mind Rot", "humanlibrary=Island;Island",
            "aihand=Memnite;Ornithopter;Shock", "aibattlefield=Mountain", "ailibrary=Island;Island"));
        st.applyToGame(game);
        Thread.sleep(1000);
        Player p0 = game.getPlayers().get(0), p1 = game.getPlayers().get(1);
        game.getPhaseHandler().setPriority(p0);
        for (Player p : game.getPlayers()) p.setHasPriority(p == p0);
        System.out.println("BEFORE  " + marks(game));
        PlayerControllerHuman h1 = (PlayerControllerHuman) p1.getController();
        SpellAbility sa = p0.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();
        sa.setActivatingPlayer(p0);
        final CardCollection hand = new CardCollection(p1.getCardsIn(ZoneType.Hand));
        Thread t = new Thread(() -> h1.chooseCardsToDiscardFrom(p1, sa, hand, 1, 1));
        t.setDaemon(true);
        t.start();
        Input in = null;
        for (int i = 0; i < 100 && in == null; i++) { Thread.sleep(50); in = h1.getInputQueue().getInput(); }
        Thread.sleep(300);
        System.out.println("ASKING  " + marks(game) + "   (P1 has " + (in == null ? "no prompt" : in.getClass().getSimpleName()) + ")");
        Card pick = hand.get(0);
        in.selectCard(pick, null, null);
        in.selectButtonOK();
        t.join(3000);
        Thread.sleep(300);
        System.out.println("AFTER   " + marks(game) + "   (P1 prompt open: " + (h1.getInputQueue().getInput() != null) + ")");
        System.exit(0);
    }

    static String marks(Game g) {
        StringBuilder b = new StringBuilder();
        for (Player p : g.getPlayers()) b.append(p.getName()).append('=').append(p.getView().getHasPriority() ? "YELLOW" : "-").append("  ");
        return b.toString();
    }
}
