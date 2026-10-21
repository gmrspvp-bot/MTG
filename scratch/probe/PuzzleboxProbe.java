import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * The "dice" patch's end-step tap (2026-10-21): at the end step just before the AI's turn, an
 * untapped Vexing Puzzlebox is tapped for its d20 roll, which Mr. House sees. Board: AI "House"
 * with Mr. House and an untapped Puzzlebox, the opponent's end step (two players, so House is
 * next). Checks the Puzzlebox got tapped, a roll happened (charge counters) and Mr. House
 * triggered. Run from forge-installer-2.0.15 with the new tools classes and the jar.
 */
public class PuzzleboxProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.DiceEngineAi.enabled = true;
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"House", "Opp"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "PuzzleboxProbe").createGame();
        game.setAge(GameStage.Play);
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=6", "activeplayer=ai", "activephase=END_OF_TURN", "humanlife=40", "ailife=40",
            "humanbattlefield=Mr. House, President and CEO;Vexing Puzzlebox", "humanhand=", "aibattlefield=Mountain"));
        st.applyToGame(game);
        Thread.sleep(2500);
        Player me = game.getPlayers().get(0);
        final Card[] box = new Card[1];
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals("Vexing Puzzlebox")) box[0] = c;
        game.getAction().invoke(() -> {
            System.out.println("next turn: " + game.getPhaseHandler().getNextTurn().getName() + ", Puzzlebox tapped: " + box[0].isTapped());
            AiPatches.DiceEngineAi.endStepRolls(game, new forge.game.event.GameEventTurnPhase(game.getPhaseHandler().getPlayerTurn(), PhaseType.END_OF_TURN, ""));
            for (int i = 0; i < 20; i++) {
                game.getTriggerHandler().runWaitingTriggers();
                game.getStack().addAllTriggeredAbilitiesToStack();
                if (game.getStack().isEmpty()) break;
                game.getStack().resolveStack();
            }
        });
        Thread.sleep(800);
        int robots = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getName().contains("Robot")) robots++;
        boolean ok = box[0].isTapped() && box[0].getCounters(forge.game.card.CounterEnumType.CHARGE) > 0;
        System.out.println("Puzzlebox tapped: " + box[0].isTapped() + ", charge counters (= the roll): "
            + box[0].getCounters(forge.game.card.CounterEnumType.CHARGE) + ", robots: " + robots);
        System.out.println(ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
