// Games with a human player (desktop Forge via "Play Forge.cmd"): EvalCacheAgent routes
// forge.game.Match.startGame(Game, Runnable) here (original renamed startGame$orig).
// SimRunner attaches BoardRecorder, AiPatches.TurnHook and ThreatTracker to each simulated
// game; desktop games skipped SimRunner, so the AI there had no threat tracking or held
// counterspell mana, and nothing was recorded. For games with a human player this attaches
// the same three, and when the game ends writes its full log (oldest line first, same format
// as the simulation logs, incl. BOARD snapshots with hands) to <forge.playlog.dir>\game_<time>.log
// so the user's own decisions can be studied. Also shows a "YOUR TURN" banner at the start of
// each of the human's turns (TurnBanner). All-AI games (simulations) are left alone.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.Match;
import forge.game.player.Player;

public final class PlayHook {
    private PlayHook() {}

    private static volatile MethodHandle orig;

    public static void startGame(Match match, Game game, Runnable startGameHook) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.lookup().findVirtual(Match.class, "startGame$orig",
                MethodType.methodType(void.class, Game.class, Runnable.class));
            orig = h;
        }
        try {
            if (game != null && hasHuman(game)) attach(game);
        } catch (Throwable t) {
            System.out.println("PLAYHOOK: could not attach (" + t + ")");
        }
        h.invokeExact(match, game, startGameHook);
    }

    static boolean hasHuman(Game game) {
        for (Player p : game.getRegisteredPlayers()) {
            if (!(p.getController() instanceof forge.ai.PlayerControllerAi)) return true;
        }
        return false;
    }

    static String describe(Player p) {
        String deck = "?";
        try { deck = p.getRegisteredPlayer().getDeck().getName(); } catch (Throwable t) { /* unknown */ }
        return p.getName() + (p.getController() instanceof forge.ai.PlayerControllerAi ? " (AI)" : " (HUMAN)") + " deck=" + deck;
    }

    static void attach(Game game) {
        game.subscribeToEvents(new BoardRecorder(game));
        game.subscribeToEvents(new AiPatches.TurnHook(game));
        if (ThreatTracker.enabled) game.subscribeToEvents(new ThreatTracker(game));
        LogWriter writer = new LogWriter(game);
        game.subscribeToEvents(writer);
        if (!"false".equals(System.getProperty("forge.turnbanner"))) game.subscribeToEvents(new TurnBanner(game));
        if (!"false".equals(System.getProperty("forge.rollback"))) {
            Rollback rb = new Rollback(game);
            game.subscribeToEvents(rb);
            javax.swing.SwingUtilities.invokeLater(rb::showButton);
        }
        // A game closed before it ends is still worth keeping: save what was played on exit.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> writer.write("unfinished (Forge closed mid-game)")));
        StringBuilder who = new StringBuilder();
        for (Player p : game.getRegisteredPlayers()) who.append(who.length() > 0 ? ", " : "").append(describe(p));
        System.out.println("PLAYHOOK: recording game with " + who);
    }

    /**
     * "YOUR TURN" banner: the user found it too easy to pass through their own turn by
     * accident. At the start of each of the human player's turns a large banner shows over
     * the Forge window for a few seconds (it doesn't take focus or need a click), with a beep.
     * -Dforge.turnbanner=false turns it off.
     */
    public static final class TurnBanner {
        private final Game game;
        private int ownTurns = 0;

        TurnBanner(Game game) { this.game = game; }

        @com.google.common.eventbus.Subscribe
        public void onEvent(Object ev) {
            if (!(ev instanceof forge.game.event.GameEventTurnBegan)) return;
            try {
                Player p = game.getPhaseHandler().getPlayerTurn();
                if (p == null || p.getController() instanceof forge.ai.PlayerControllerAi) return;
                ownTurns++;
                final String text = "YOUR TURN  (" + ownTurns + ")";
                javax.swing.SwingUtilities.invokeLater(() -> show(text));
            } catch (Throwable t) {
                // never break the game
            }
        }

        private static void show(String text) {
            try {
                java.awt.Toolkit.getDefaultToolkit().beep();
                javax.swing.JWindow w = new javax.swing.JWindow();
                w.setFocusableWindowState(false);
                w.setAlwaysOnTop(true);
                javax.swing.JLabel label = new javax.swing.JLabel(text, javax.swing.SwingConstants.CENTER);
                label.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 54));
                label.setForeground(java.awt.Color.WHITE);
                label.setOpaque(true);
                label.setBackground(new java.awt.Color(25, 110, 60));
                label.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(250, 210, 60), 6));
                w.getContentPane().add(label);
                w.setSize(620, 130);
                java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                w.setLocation(screen.x + (screen.width - 620) / 2, screen.y + screen.height / 4);
                w.setVisible(true);
                javax.swing.Timer t = new javax.swing.Timer(2500, e -> w.dispose());
                t.setRepeats(false);
                t.start();
            } catch (Throwable t) {
                // a missing banner must never break the game
            }
        }
    }

    /**
     * "Restart this phase" button (desktop Forge's Rollback Phase only exists in the mobile UI,
     * and its game-state format is 2-player only). At the start of every phase a full copy of the
     * game is kept (forge.game.GameSnapshot, the same copy Forge's experimental undo uses). A small
     * always-on-top button puts back the copy from the start of the current phase, so a misclick
     * inside the phase can be redone. Only while the human has priority (the game thread is then
     * waiting for them). Logged in the game log as "ROLLBACK ...". -Dforge.rollback=false = off.
     */
    public static final class Rollback {
        private final Game game;
        private forge.game.GameSnapshot snap;
        private int snapTurn = -1;
        private forge.game.phase.PhaseType snapPhase;
        private javax.swing.JWindow window;

        Rollback(Game game) { this.game = game; }

        @com.google.common.eventbus.Subscribe
        public synchronized void onEvent(Object ev) {
            try {
                if (ev instanceof forge.game.event.GameEventGameFinished) {
                    javax.swing.SwingUtilities.invokeLater(() -> { if (window != null) window.dispose(); });
                    return;
                }
                if (!(ev instanceof forge.game.event.GameEventTurnPhase) || game.isGameOver()) return;
                forge.game.GameSnapshot s = new forge.game.GameSnapshot(game);
                s.makeCopy();
                snap = s;
                snapTurn = game.getPhaseHandler().getTurn();
                snapPhase = game.getPhaseHandler().getPhase();
            } catch (Throwable t) {
                snap = null;   // a failed copy just means no rollback for this phase
            }
        }

        void showButton() {
            javax.swing.JButton b = new javax.swing.JButton("↶ Restart this phase");
            b.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 16));
            b.setBackground(new java.awt.Color(120, 40, 40));
            b.setForeground(java.awt.Color.WHITE);
            b.setFocusable(false);
            b.addActionListener(e -> restart());
            window = new javax.swing.JWindow();
            window.setFocusableWindowState(false);
            window.setAlwaysOnTop(true);
            window.getContentPane().add(b);
            window.setSize(230, 44);
            java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            window.setLocation(screen.x + screen.width - 250, screen.y + screen.height - 64);
            // Remember whether Forge's own window was up, so the button can go away with it.
            final boolean hadFrame = forgeFrameVisible();
            window.setVisible(true);
            // The button was only removed at a normal game end; quitting mid-game or closing
            // Forge left it on screen, and it kept Java running in the background.
            javax.swing.Timer watch = new javax.swing.Timer(2000, null);
            watch.addActionListener(e -> {
                if (game.isGameOver() || (hadFrame && !forgeFrameVisible())) {
                    watch.stop();
                    window.dispose();
                }
            });
            watch.start();
        }

        static boolean forgeFrameVisible() {
            for (java.awt.Frame f : java.awt.Frame.getFrames()) { if (f.isVisible()) return true; }
            return false;
        }

        /**
         * Forge's restore puts back life, zones and taps but not attachments: in a test the creature
         * ended up wearing both the old and the new equipment, and the +10/+10 of Colossus Hammer
         * wasn't applied (a Catch-attached Hammer was lost in a real game). Make every battlefield
         * card's attachment match the saved copy, then recompute static effects (power/toughness).
         */
        static int repairAttachments(forge.game.GameSnapshot snap, Game game) {
            int fixed = 0;
            for (forge.game.card.Card c : game.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                forge.game.GameObject copyObj = snap.find(c);
                if (!(copyObj instanceof forge.game.card.Card)) continue;
                forge.game.GameEntity wantCopy = ((forge.game.card.Card) copyObj).getEntityAttachedTo();
                forge.game.GameEntity want = null;
                if (wantCopy != null) {
                    forge.game.GameObject w = snap.reverseFind(wantCopy);
                    if (w instanceof forge.game.GameEntity) want = (forge.game.GameEntity) w;
                }
                forge.game.GameEntity have = c.getEntityAttachedTo();
                if (have == want) continue;
                if (have != null) c.unattachFromEntity(have);
                if (want != null) c.attachToEntity(want, null);
                fixed++;
            }
            game.getAction().checkStaticAbilities();
            return fixed;
        }

        void restart() {
            String problem = null;
            forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
            Player prio = ph.getPriorityPlayer();
            forge.game.GameSnapshot s;
            synchronized (this) { s = snap; }
            if (game.isGameOver()) problem = "The game is over.";
            else if (prio == null || prio.getController() instanceof forge.ai.PlayerControllerAi) problem = "Wait until it's your priority (the AI is acting).";
            else if (!game.getStack().isEmpty()) problem = "Something is on the stack. Let it resolve first.";
            else if (s == null || snapTurn != ph.getTurn() || snapPhase != ph.getPhase()) problem = "No saved copy for this phase.";
            if (problem != null) {
                javax.swing.JOptionPane.showMessageDialog(window, problem, "Restart this phase", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String phaseName = String.valueOf(ph.getPhase());
            if (javax.swing.JOptionPane.showConfirmDialog(window, "Go back to the start of this phase (" + phaseName + ", turn " + ph.getTurn()
                    + ")?\nEverything done since then in this phase is undone.", "Restart this phase",
                    javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return;
            try {
                s.restoreGameState(game);
                int fixed = repairAttachments(s, game);
                if (fixed > 0) System.out.println("PLAYHOOK: rollback re-attached " + fixed + " card(s)");
                // Fresh copy of the restored state, so the button can be used again in this phase.
                forge.game.GameSnapshot again = new forge.game.GameSnapshot(game);
                again.makeCopy();
                synchronized (this) { snap = again; }
                game.getGameLog().add(forge.game.GameLogEntryType.INFORMATION, "ROLLBACK to the start of " + phaseName + " (turn " + ph.getTurn() + ")");
                System.out.println("PLAYHOOK: rollback to the start of " + phaseName + ", turn " + ph.getTurn());
            } catch (Throwable t) {
                System.out.println("PLAYHOOK: rollback failed (" + t + ")");
                javax.swing.JOptionPane.showMessageDialog(window, "Sorry, the rollback failed: " + t, "Restart this phase", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Writes the game log to a file when the game finishes (or Forge closes mid-game). */
    public static final class LogWriter {
        private final Game game;
        private boolean written = false;

        LogWriter(Game game) { this.game = game; }

        @com.google.common.eventbus.Subscribe
        public void onEvent(Object ev) {
            if (ev instanceof forge.game.event.GameEventGameFinished) write(null);
        }

        synchronized void write(String unfinished) {
            if (written) return;
            written = true;
            try {
                java.io.File dir = new java.io.File(System.getProperty("forge.playlog.dir", "../scratch/play-logs"));
                dir.mkdirs();
                String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new java.util.Date());
                java.io.File out = new java.io.File(dir, "game_" + stamp + (unfinished != null ? "_unfinished" : "") + ".log");
                java.util.List<GameLogEntry> log = new java.util.ArrayList<>(game.getGameLog().getLogEntries(null));
                java.util.Collections.reverse(log);
                try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8))) {
                    for (Player p : game.getRegisteredPlayers()) w.println("Player: " + describe(p));
                    for (GameLogEntry e : log) w.println(e);
                    String result = unfinished != null ? unfinished
                        : game.getOutcome() == null ? "unknown"
                        : game.getOutcome().isDraw() ? "a Draw" : game.getOutcome().getWinningLobbyPlayer().getName() + " has won";
                    w.println();
                    w.println("Game Result: " + result);
                }
                System.out.println("PLAYHOOK: game log saved to " + out.getAbsolutePath());
            } catch (Throwable t) {
                System.out.println("PLAYHOOK: could not save the game log (" + t + ")");
            }
        }
    }
}
