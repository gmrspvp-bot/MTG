import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.Input;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;

/**
 * The blank Yes/No (AutoPassHook.autoPassCancel, 2026-11-01). Forge's human controller with a
 * stand-in window that records the prompt text and buttons. The auto-pass is armed, a Yes/No
 * ("Search your hand?") is opened, and the auto-pass is then called off the way the 3D table's
 * SPACE does it. Forge alone leaves the window blank with both buttons off while the question is
 * still open; with the hook the question and its Yes / No come back. Finally Yes is pressed to
 * show the question was really still there.
 * -Dforge.autopassreshow=false shows Forge's own.
 *
 * Run from forge-installer-2.0.15 with the evalcache agent, the Forge jar and scripts\tools\classes.
 */
public class AutoPassCancelProbe {
    static volatile String message = "?";
    static volatile String buttons = "?";

    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("Me")).setPlayer(new forge.player.LobbyPlayerHuman("Me")));
        players.add(new RegisteredPlayer(new Deck("Other")).setPlayer(GamePlayerUtil.createAiPlayer("Other")));
        Game game = new Match(new GameRules(GameType.Commander), players, "AutoPassCancelProbe").createGame();
        Object stub = java.lang.reflect.Proxy.newProxyInstance(AutoPassCancelProbe.class.getClassLoader(),
            new Class<?>[] { forge.gui.interfaces.IGuiGame.class }, (p, m, args) -> {
                String n = m.getName();
                if (n.equals("showPromptMessage") && args.length >= 2) message = String.valueOf(args[1]);
                if (n.equals("updateButtons") && args.length == 6)
                    buttons = (Boolean.TRUE.equals(args[3]) ? args[1] : "-") + "/" + (Boolean.TRUE.equals(args[4]) ? args[2] : "-");
                if (n.equals("updateButtons") && args.length == 4)
                    buttons = (Boolean.TRUE.equals(args[1]) ? "on" : "-") + "/" + (Boolean.TRUE.equals(args[2]) ? "on" : "-");
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                return null;
            });
        PlayerControllerHuman pch = (PlayerControllerHuman) game.getPlayers().get(0).getController();
        pch.setGui((forge.gui.interfaces.IGuiGame) stub);
        game.setAge(GameStage.Play);
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=human", "activephase=END_OF_TURN",
            "humanlife=40", "ailife=40", "humanbattlefield=Dr. Eggman;Island", "humanlibrary=Island;Island",
            "aibattlefield=Mountain", "ailibrary=Island;Island"));
        st.applyToGame(game);
        Thread.sleep(1000);
        Player me = game.getPlayers().get(0);
        Card egg = me.getCardsIn(ZoneType.Battlefield).get(0);
        SpellAbility sa = egg.getFirstSpellAbility() != null ? egg.getFirstSpellAbility() : egg.getTriggers().get(0).ensureAbility();
        pch.autoPassUntilEndOfTurn();
        System.out.println("AUTOPASS on: " + pch.mayAutoPass());
        final boolean[] answer = { false };
        Thread t = new Thread(() -> answer[0] = pch.confirmAction(sa, null, "Search your hand?", null));
        t.setDaemon(true);
        t.start();
        Input in = null;
        for (int i = 0; i < 100 && in == null; i++) { Thread.sleep(50); in = pch.getInputQueue().getInput(); }
        Thread.sleep(500);
        System.out.println("OPEN    " + (in == null ? "none" : in.getClass().getSimpleName()) + "  message=\"" + message + "\" buttons=" + buttons);
        pch.autoPassCancel();                    // what SPACE in the 3D table does first
        Thread.sleep(800);
        System.out.println("AFTER   input=" + (pch.getInputQueue().getInput() == null ? "none" : pch.getInputQueue().getInput().getClass().getSimpleName())
            + "  message=\"" + message + "\" buttons=" + buttons + "  autopass=" + pch.mayAutoPass());
        if (in != null) in.selectButtonOK();
        t.join(3000);
        System.out.println("RESULT answered=" + !t.isAlive() + " yes=" + answer[0] + " shownAgain=" + message.contains("Search your hand"));
        System.exit(0);
    }
}
