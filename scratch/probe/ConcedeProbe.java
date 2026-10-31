import java.io.File;
import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.player.*;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputQueue;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;

/**
 * Concede house rule (ConcedeHook, 2026-11-01). A real four-player game through Forge's own loop:
 * seats 0 and 1 are Forge's human controller, answered by a stand-in that always passes (OK, or
 * releasing the prompt if OK does not take); seats 2 and 3 are the AI. Seat 1 concedes from
 * turn 3 at the first prompt it gets, then the probe watches: who runs seat 1, whether the game
 * goes on, and when seat 1 leaves (it must be at the start of its own next turn's upkeep).
 *
 *   ConcedeProbe          two humans: seat 1 sits out
 *   ConcedeProbe solo     seat 0 is AI too: seat 1 is the only human, so it leaves at once
 *   ConcedeProbe own      seat 1 concedes in its own main phase: sits out a full round
 *
 * Run from forge-installer-2.0.15 with the evalcache agent, the Forge jar and scripts\tools\classes.
 */
public class ConcedeProbe {
    static volatile boolean conceded = false;
    static volatile int concededTurn = -1;

    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        boolean solo = a.length > 0 && a[0].equals("solo");
        // "own": seat 1 concedes in its own main phase, so it must sit out a whole round.
        final boolean own = a.length > 0 && a[0].equals("own");
        String[] decks = { "Derand - Captain America v7", "Other - Tidus v3", "Derand - Mr House v4", "Derand - Doom Prevails v3" };
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Deck d = DeckSerializer.fromFile(new File("..\\decks\\" + decks[i] + ".dck"));
            boolean human = i == 1 || (i == 0 && !solo);
            String name = "P" + i;
            RegisteredPlayer rp = RegisteredPlayer.forCommander(d);
            rp.setPlayer(human ? new forge.player.LobbyPlayerHuman(name) : GamePlayerUtil.createAiPlayer(name));
            players.add(rp);
        }
        Match match = new Match(new GameRules(GameType.Commander), players, "ConcedeProbe");
        Game game = match.createGame();
        Object stub = java.lang.reflect.Proxy.newProxyInstance(ConcedeProbe.class.getClassLoader(),
            new Class<?>[] { forge.gui.interfaces.IGuiGame.class }, (p, m, args) -> {
                if (m.getName().equals("getAbilityToPlay") && args[2] == null && ((List<?>) args[1]).size() == 1) return ((List<?>) args[1]).get(0);
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                // A pick from a list (who goes first, in a four-player game): the first one.
                if (args != null) for (Object o : args) {
                    if (!(o instanceof List) || ((List<?>) o).isEmpty()) continue;
                    if (t == List.class) return new ArrayList<>(((List<?>) o).subList(0, 1));
                    if (t.isInstance(((List<?>) o).get(0)) || t == Object.class) return ((List<?>) o).get(0);
                }
                return null;
            });
        for (Player p : game.getPlayers())
            if (p.getController() instanceof PlayerControllerHuman) ((PlayerControllerHuman) p.getController()).setGui((forge.gui.interfaces.IGuiGame) stub);
        Player seat1 = game.getPlayers().get(1);
        PlayerControllerHuman seat1Human = (PlayerControllerHuman) seat1.getController();

        // The stand-in for the humans, plus the concede.
        Thread driver = new Thread(() -> {
            Map<Input, Integer> seen = new HashMap<>();
            while (!game.isGameOver()) {
                try { Thread.sleep(40); } catch (InterruptedException e) { return; }
                for (Player p : game.getRegisteredPlayers()) {
                    if (!(p.getController() instanceof PlayerControllerHuman) && p != seat1) continue;
                    PlayerControllerHuman h = p == seat1 ? seat1Human : (PlayerControllerHuman) p.getController();
                    InputQueue q = h.getInputQueue();
                    Input in = q.getInput();
                    if (in == null) continue;
                    if (p == seat1 && !conceded && game.getPhaseHandler().getTurn() >= 3
                            && (!own || (game.getPhaseHandler().getPlayerTurn() == seat1
                                && game.getPhaseHandler().getPhase() == forge.game.phase.PhaseType.MAIN1))) {
                        conceded = true;
                        concededTurn = game.getPhaseHandler().getTurn();
                        System.out.println("PROBE seat 1 concedes on turn " + concededTurn + " (" + game.getPhaseHandler().getPlayerTurn()
                            + " " + game.getPhaseHandler().getPhase() + ") with " + in.getClass().getSimpleName() + " open");
                        h.concede();
                        System.out.println("PROBE seat 1 now run by " + seat1.getController().getClass().getName()
                            + ", hasLost=" + seat1.hasLost() + ", inputs left=" + q.printInputStack());
                        continue;
                    }
                    if (p == seat1 && conceded) { System.out.println("PROBE !! seat 1 asked after conceding: " + in.getClass().getSimpleName()); }
                    int n = seen.merge(in, 1, Integer::sum);
                    if (n == 1 && in instanceof forge.gamemodes.match.input.InputSelectEntitiesFromList) {
                        // A pick that needs one (who goes first, in a four-player game): the first choice.
                        forge.gamemodes.match.input.InputSelectEntitiesFromList<?> sel = (forge.gamemodes.match.input.InputSelectEntitiesFromList<?>) in;
                        if (sel.getSelected().isEmpty() && !sel.getValidChoices().isEmpty()) {
                            Object first = sel.getValidChoices().get(0);
                            if (first instanceof Player) in.selectPlayer((Player) first, null);
                            else if (first instanceof forge.game.card.Card) in.selectCard((forge.game.card.Card) first, null, null);
                        }
                    }
                    if (n == 1) in.selectButtonOK();
                    else if (n > 5) { q.onGameOver(false); q.removeInput(in); }
                }
            }
        });
        driver.setDaemon(true);
        driver.start();

        // Watch the turns.
        Thread watch = new Thread(() -> {
            int lastTurn = -1; boolean reported = false;
            while (true) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                int t = game.getPhaseHandler().getTurn();
                if (t != lastTurn) {
                    lastTurn = t;
                    System.out.println("PROBE turn " + t + ": " + game.getPhaseHandler().getPlayerTurn() + "   seat1 lost=" + seat1.hasLost()
                        + " ctrl=" + seat1.getController().getClass().getSimpleName());
                }
                if (seat1.hasLost() && !reported) {
                    reported = true;
                    System.out.println("PROBE seat 1 out on turn " + t + " (" + game.getPhaseHandler().getPlayerTurn() + " "
                        + game.getPhaseHandler().getPhase() + "), conceded on " + concededTurn + ", outcome=" + seat1.getOutcome());
                }
                if (game.isGameOver() || (reported && t >= lastTurn && t > concededTurn + 6)) {
                    System.out.println("RESULT seat1 lost=" + seat1.hasLost() + " gameOver=" + game.isGameOver()
                        + " players left=" + game.getPlayers());
                    System.exit(0);
                }
            }
        });
        watch.setDaemon(true);
        watch.start();
        match.startGame(game);
        Thread.sleep(2000);
        System.out.println("RESULT (loop ended) seat1 lost=" + seat1.hasLost() + " gameOver=" + game.isGameOver());
        System.exit(0);
    }
}
