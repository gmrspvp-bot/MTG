import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.Input;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;

/**
 * Proliferate's "Mine" button (MassSelectHook, 2026-11-01). Player 0 is Forge's human controller
 * with 2 experience counters and a Grizzly Bears with a +1/+1 counter; the opponent has a bear
 * with a +1/+1 counter too. Steady Progress is resolved; the probe presses the mass-select
 * button once ("Mine"), then OK, and prints what got a counter. Mine must give the player a third
 * experience counter and their own bear a second +1/+1 counter, and leave the opponent's alone.
 * -Dforge.mine.self=false shows Forge's own (experience stays at 2).
 *
 * Run from forge-installer-2.0.15 with the evalcache agent, the Forge jar and scripts\tools\classes.
 */
public class MassSelectProbe {
    static volatile boolean mineShown = false;
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("Me")).setPlayer(new forge.player.LobbyPlayerHuman("Me")));
        players.add(new RegisteredPlayer(new Deck("Other")).setPlayer(GamePlayerUtil.createAiPlayer("Other")));
        Game game = new Match(new GameRules(GameType.Commander), players, "MassSelectProbe").createGame();
        Object stub = java.lang.reflect.Proxy.newProxyInstance(MassSelectProbe.class.getClassLoader(),
            new Class<?>[] { forge.gui.interfaces.IGuiGame.class }, (p, m, args) -> {
                if (m.getName().equals("updateButtons") && args.length == 6)
                    { System.out.println("  buttons: ok=" + args[1] + " cancel=" + args[2]); if ("Mine".equals(args[2])) mineShown = true; }
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                return null;
            });
        PlayerControllerHuman pch = (PlayerControllerHuman) game.getPlayers().get(0).getController();
        pch.setGui((forge.gui.interfaces.IGuiGame) stub);
        game.setAge(GameStage.Play);
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=human", "activephase=MAIN1",
            "humanlife=40", "ailife=40",
            "humanbattlefield=Grizzly Bears|Counters:P1P1=1;Island;Island;Island",
            "humanhand=Steady Progress", "humanlibrary=Island;Island;Island",
            "aibattlefield=Grizzly Bears|Counters:P1P1=1;Mountain", "ailibrary=Island;Island;Island"));
        st.applyToGame(game);
        Thread.sleep(1000);
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        CounterType xp = CounterType.getType("EXPERIENCE");
        me.setCounters(xp, 2);
        Card spell = me.getCardsIn(ZoneType.Hand).get(0);
        SpellAbility sa = spell.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        Thread t = new Thread(() -> AbilityUtils.resolve(sa));
        t.setDaemon(true);
        t.start();
        Input in = null;
        for (int i = 0; i < 100 && in == null; i++) { Thread.sleep(50); in = pch.getInputQueue().getInput(); }
        System.out.println("PROMPT " + (in == null ? "none" : in.getClass().getSimpleName()));
        if (in == null) System.exit(1);
        for (int i = 0; i < 100 && !mineShown; i++) Thread.sleep(50);   // the prompt is drawn, mode set
        in.selectButtonCancel();            // the mass-select button: Mine
        Thread.sleep(200);
        System.out.println("SELECTED after Mine: " + ((forge.gamemodes.match.input.InputSelectEntitiesFromList<?>) in).getSelected());
        in.selectButtonOK();
        t.join(5000);
        Card myBear = null, theirBear = null;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals("Grizzly Bears")) myBear = c;
        for (Card c : opp.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals("Grizzly Bears")) theirBear = c;
        CounterType p1 = CounterType.getType("P1P1");
        System.out.println("RESULT experience=" + me.getCounters(xp) + " myBear+1/+1=" + myBear.getCounters(p1)
            + " theirBear+1/+1=" + theirBear.getCounters(p1));
        System.exit(0);
    }
}
