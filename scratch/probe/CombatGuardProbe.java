import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// Reproduces the rollback crash: declare attackers begins with no Combat object. With the agent
// (CombatGuardHook) it should create one instead of throwing a NullPointerException.
// Run with -javaagent:scripts\tools\evalcache-agent.jar.
public class CombatGuardProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "CombatGuardProbe").createGame();
        Player me = game.getPlayers().get(0);
        PhaseHandler ph = game.getPhaseHandler();
        ph.devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, me);
        System.out.println("before: phase=" + ph.getPhase() + " combat=" + ph.getCombat());
        java.lang.reflect.Method m = PhaseHandler.class.getDeclaredMethod("onPhaseBegin");
        m.setAccessible(true);
        try {
            game.getAction().invoke(() -> { try { m.invoke(ph); System.out.println("onPhaseBegin OK, combat=" + ph.getCombat()); } catch (Throwable t) { System.out.println("CRASH: " + (t.getCause() != null ? t.getCause() : t)); } });
            Thread.sleep(1500);
        } catch (Throwable t) { System.out.println("CRASH: " + t); }
        System.exit(0);
    }
}
