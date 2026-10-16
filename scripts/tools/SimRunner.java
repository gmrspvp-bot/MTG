// Forge's simulation mode (forge.view.SimulateMatch) re-done so a BoardRecorder can be
// attached to every game before it starts. Same options (-d -D -n -f -c -s -a -q),
// same deck loading, rules, player names, timeout and output format, so the existing
// log parsers keep working. Tournament (-t) and match (-m) modes are not supported;
// use plain Forge for those.

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.time.StopWatch;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.player.GamePlayerUtil;

public final class SimRunner {
    private SimRunner() {}

    public static void run(String[] args) throws Exception {
        // What forge.view.Main does before sim mode (minus crash reporting).
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        System.setProperty("sun.java2d.d3d", "false");
        forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
        forge.error.ExceptionHandler.registerErrorHandling();
        forge.model.FModel.initialize(null, null);
        System.out.println("Simulation mode (SimRunner: board snapshots on)");

        Map<String, List<String>> params = new HashMap<>();
        List<String> current = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.length() > 1 && a.charAt(0) == '-') {
                current = new ArrayList<>();
                params.put(a.substring(1), current);
            } else if (current != null) {
                current.add(a);
            }
        }
        if (params.containsKey("t") || params.containsKey("m")) {
            System.out.println("SimRunner: -t and -m are not supported; run plain Forge for those.");
            return;
        }
        String baseDir = params.containsKey("D") ? params.get("D").get(0) : null;
        int games = params.containsKey("n") ? Integer.parseInt(params.get("n").get(0)) : 1;
        Long seed = null;
        if (params.containsKey("s")) {
            seed = Long.parseLong(params.get("s").get(0));
            forge.util.MyRandom.setRandom(new java.util.Random(seed));
        }
        GameType type = params.containsKey("f")
            ? GameType.valueOf(forge.util.WordUtil.capitalize(params.get("f").get(0))) : GameType.Constructed;
        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        if (params.containsKey("c")) rules.setSimTimeout(Integer.parseInt(params.get("c").get(0)));
        boolean outputLog = !params.containsKey("q");
        List<String> profiles = params.get("a");

        Method deckFrom = forge.view.SimulateMatch.class.getDeclaredMethod("deckFromCommandLineParameter",
            String.class, GameType.class, String.class);
        deckFrom.setAccessible(true);
        List<RegisteredPlayer> players = new ArrayList<>();
        StringBuilder header = new StringBuilder();
        int i = 1;
        for (String d : params.getOrDefault("d", new ArrayList<>())) {
            Deck deck = (Deck) deckFrom.invoke(null, d, type, baseDir);
            if (deck == null) {
                System.out.println("Could not load deck - " + d + ", match cannot start");
                return;
            }
            if (i > 1) header.append(" vs ");
            String profile = profiles != null && profiles.size() >= i ? profiles.get(i - 1) : "";
            String name = "Ai(" + i + ")-" + deck.getName();
            header.append(name);
            if (!profile.isEmpty()) header.append(" [").append(profile).append(']');
            RegisteredPlayer rp = type.equals(GameType.Commander) ? RegisteredPlayer.forCommander(deck) : new RegisteredPlayer(deck);
            rp.setPlayer(GamePlayerUtil.createAiPlayer(name, i - 1, profile));
            players.add(rp);
            i++;
        }
        header.append(" - ").append(forge.util.Lang.nounWithNumeral(games, "game")).append(" of ").append(type);
        if (seed != null) header.append(" seed ").append(seed);
        System.out.println(header);

        String queueDir = System.getProperty("forge.queue.dir");
        if (queueDir != null) {
            // Shared queue: several processes take game numbers from one folder until the
            // total is reached, so no process sits idle while another grinds through slow
            // games. Seat order rotates per game (game g starts at seat g mod players).
            int total = Integer.parseInt(System.getProperty("forge.queue.total", String.valueOf(games)));
            java.nio.file.Path dir = java.nio.file.Paths.get(queueDir);
            java.nio.file.Files.createDirectories(dir);
            for (int g = 0; g < total; g++) {
                try {
                    java.nio.file.Files.createFile(dir.resolve("game_" + g));
                } catch (java.nio.file.FileAlreadyExistsException taken) {
                    continue;
                }
                List<RegisteredPlayer> seats = new ArrayList<>();
                for (int s = 0; s < players.size(); s++) {
                    RegisteredPlayer orig = players.get((s + g) % players.size());
                    RegisteredPlayer rp = type.equals(GameType.Commander) ? RegisteredPlayer.forCommander(orig.getDeck())
                        : new RegisteredPlayer(orig.getDeck());
                    rp.setPlayer(orig.getPlayer());
                    seats.add(rp);
                }
                runGame(new Match(rules, seats, "Test"), g, outputLog);
                System.out.flush();
            }
            return;
        }
        Match match = new Match(rules, players, "Test");
        for (int g = 0; g < games; g++) runGame(match, g, outputLog);
        System.out.flush();
    }

    /** forge.view.SimulateMatch.simulateSingleMatch, plus the board recorder. */
    static void runGame(Match match, int index, boolean outputLog) {
        StopWatch sw = new StopWatch();
        sw.start();
        Game game = match.createGame();
        game.setNoGUIUser();
        if (!"false".equals(System.getProperty("forge.boardlog"))) game.subscribeToEvents(new BoardRecorder(game));
        game.subscribeToEvents(new AiPatches.TurnHook(game));   // keeps counterspell mana held over opponents' turns
        if (ThreatTracker.enabled) game.subscribeToEvents(new ThreatTracker(game));
        try {
            forge.view.TimeLimitedCodeBlock.runWithTimeout(() -> {
                match.startGame(game);
                sw.stop();
            }, match.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("Stopping slow match as draw");
        } catch (Exception | StackOverflowError e) {
            e.printStackTrace();
        } finally {
            if (sw.isStarted()) sw.stop();
            game.setGameOver(GameEndReason.Draw);
        }
        List<GameLogEntry> log = game.getGameLog().getLogEntries(outputLog ? null : GameLogEntryType.MATCH_RESULTS);
        java.util.Collections.reverse(log);
        for (GameLogEntry e : log) System.out.println(e);
        if (game.getOutcome().isDraw()) {
            System.out.printf("\nGame Result: Game %d ended in a Draw! Took %d ms.%n", index + 1, sw.getTime());
        } else {
            System.out.printf("\nGame Result: Game %d ended in %d ms. %s has won!\n%n", index + 1, sw.getTime(),
                game.getOutcome().getWinningLobbyPlayer().getName());
        }
    }
}
