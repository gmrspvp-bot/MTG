import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import forge.player.PlayerControllerHuman;
import mtg3d.ClickGuard;

/**
 * The double-click race from the agreed input plan (ui3d README, step 2), measured rather than
 * argued. A stand-in game thread asks the human controller for two priority decisions back to
 * back - the worst case, where the next step's priority arrives at once. Two clicks aimed at the
 * FIRST decision are then sent through the EDT, `gap` ms apart, the way a 3D SPACE and a click on
 * Forge's OK (or a double click) would arrive.
 *
 *   1 decision taken  = safe (the second click was spent harmlessly, or dropped)
 *   2 decisions taken = the second click passed a step the player never saw
 *
 * Unguarded is Forge's own behaviour (and a naive 3D button); guarded is mtg3d.ClickGuard.
 *
 * Run from forge-installer-2.0.15\ with the Forge desktop jar and ui3d\classes on the classpath.
 */
public class DoubleClickProbe {
    static PlayerControllerHuman pch;

    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("Me")).setPlayer(GamePlayerUtil.getGuiPlayer()));
        players.add(new RegisteredPlayer(new Deck("AI")).setPlayer(GamePlayerUtil.createAiPlayer("AI")));
        Game game = new Match(new GameRules(GameType.Constructed), players, "DoubleClickProbe").createGame();
        Player me = null;
        for (Player p : game.getPlayers()) if (p.getController() instanceof PlayerControllerHuman) me = p;
        pch = (PlayerControllerHuman) me.getController();
        pch.setGui(stubGui());
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        // Something to do, so priority is a real decision rather than an automatic pass.
        Card land = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Plains"), me);
        game.getAction().moveToHand(land, null);

        String[][] cases = {
            { "unguarded", "unguarded", "0" }, { "unguarded", "unguarded", "40" },
            { "unguarded", "unguarded", "150" }, { "unguarded", "unguarded", "400" },
            { "guarded", "guarded", "0" }, { "guarded", "guarded", "40" },
            { "guarded", "guarded", "150" }, { "guarded", "guarded", "400" },
            { "guarded", "unguarded", "150" },   // 3D first, then Forge's own OK, unprotected
            { "unguarded", "guarded", "150" },   // Forge's OK first, then the 3D key
            // "forge" = Forge's OK through ClickGuard.forgeWindow, as Gui3D would install it
            { "guarded", "forge", "0" }, { "guarded", "forge", "40" },
            { "guarded", "forge", "150" }, { "guarded", "forge", "400" },
            { "forge", "guarded", "150" }, { "forge", "forge", "150" },
        };
        int bad = 0;
        for (String[] c : cases) {
            int took = run(c[0], c[1], Integer.parseInt(c[2]));
            boolean safe = took <= 1;    // 0 = nothing taken: harmless, the player clicks again
            // Only the second click's guard matters. A Forge-window click later than the debounce
            // is treated as meant for the new prompt, so it may legitimately take a second step.
            boolean expectSafe = c[1].equals("guarded")
                || (c[1].equals("forge") && Integer.parseInt(c[2]) < ClickGuard.DEBOUNCE_MS);
            System.out.printf("%-9s then %-9s gap %3s ms -> %d decision(s) taken  %s%n",
                c[0], c[1], c[2], took, took == 0 ? "safe (nothing taken)" : safe ? "safe" : "SKIPPED A STEP");
            if (expectSafe && !safe) bad++;
        }
        System.out.println("dropped by guard: " + ClickGuard.dropped);
        System.out.println("prompt calls seen: " + promptThreads);
        System.out.println(bad == 0 ? "GUARD HOLDS" : "GUARD FAILED " + bad);
        System.exit(0);
    }

    /** One scenario; returns how many of the two queued decisions the two clicks completed. */
    static int run(String first, String second, int gap) throws Exception {
        AtomicInteger done = new AtomicInteger();
        Thread game = new Thread(() -> {
            for (int k = 0; k < 2; k++) {
                pch.chooseSpellAbilityToPlay();
                done.incrementAndGet();
            }
        }, "probe-game");
        game.start();
        if (!waitForInput(3000)) throw new IllegalStateException("no priority input appeared");
        Object seen = ClickGuard.token(pch);     // what the player is looking at
        // A person reads a prompt before clicking it. Forge's window is debounced for exactly
        // that reason, so its FIRST click comes a human-like 400 ms after the prompt appears.
        if (first.equals("forge")) Thread.sleep(400);
        click(first, seen);
        Thread.sleep(gap);
        click(second, seen);
        Thread.sleep(1200);
        int took = done.get();
        // Finish whatever is still pending so the next case starts clean.
        for (int i = 0; game.isAlive() && i < 20; i++) {
            javax.swing.SwingUtilities.invokeLater(pch::selectButtonOk);
            Thread.sleep(150);
        }
        game.join(2000);
        return took;
    }

    static forge.interfaces.IGameController forgeCtl;

    static void click(String how, Object seen) {
        if (how.equals("guarded")) ClickGuard.ok(pch, seen);
        else if (how.equals("forge")) {
            if (forgeCtl == null) forgeCtl = ClickGuard.forgeWindow(pch);
            javax.swing.SwingUtilities.invokeLater(() -> forgeCtl.selectButtonOk());
        } else javax.swing.SwingUtilities.invokeLater(pch::selectButtonOk);
    }

    static final Set<String> promptThreads = Collections.synchronizedSet(new TreeSet<>());

    static boolean waitForInput(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ClickGuard.token(pch) != null) return true;
            Thread.sleep(5);
        }
        return false;
    }

    /** An IGuiGame that answers nothing: every call returns a default. Prompts are not needed. */
    static IGuiGame stubGui() {
        return (IGuiGame) java.lang.reflect.Proxy.newProxyInstance(
            IGuiGame.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
            (proxy, m, args) -> {
                // What Gui3D will do when it forwards a prompt to Forge's window.
                if (m.getName().equals("showPromptMessage") || m.getName().equals("updateButtons")) {
                    promptThreads.add(m.getName() + " on " + Thread.currentThread().getName());
                }
                if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
                Class<?> r = m.getReturnType();
                if (r == boolean.class) return false;
                if (r == int.class) return 0;
                if (r == long.class) return 0L;
                if (r == float.class) return 0f;
                if (r == double.class) return 0d;
                if (r == List.class) return new ArrayList<>();
                return null;
            });
    }
}
