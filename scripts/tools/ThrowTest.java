// Sets up a fixed board in Forge and asks the AI how it rates each of
// Captain America's Throw abilities (and what it would choose to play).
//
// Usage (from forge-installer-2.0.15): java -cp <classes>;<forge jar> ThrowTest <state file>

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import forge.GuiDesktop;
import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

public class ThrowTest {
    public static void main(String[] args) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);

        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("A")).setPlayer(GamePlayerUtil.createAiPlayer("Opponent")));
        players.add(new RegisteredPlayer(new Deck("B")).setPlayer(GamePlayerUtil.createAiPlayer("Cap")));
        Match match = new Match(new GameRules(GameType.Constructed), players, "ThrowTest");
        Game game = match.createGame();

        GameState state = new GameState();
        state.parse(Files.readAllLines(Paths.get(args[0])));
        state.applyToGame(game);
        Thread.sleep(3000); // applyToGame runs on Forge's game thread

        for (Player p : game.getPlayers()) {
            System.out.println("PLAYER " + p.getName() + " life=" + p.getLife()
                + " battlefield=" + p.getCardsIn(ZoneType.Battlefield));
        }
        System.out.println("PHASE " + game.getPhaseHandler().getPhase() + " turn of " + game.getPhaseHandler().getPlayerTurn());

        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(java.util.Arrays.asList(ZoneType.Battlefield, ZoneType.Command, ZoneType.Hand))) {
                if (!c.getName().startsWith("Captain America")) continue;
                AiController ai = ((PlayerControllerAi) p.getController()).getAi();
                System.out.println("CAP in " + c.getZone().getZoneType() + " controlled by " + p.getName() + ", equipped by " + c.getEquippedBy());
                for (SpellAbility sa : c.getSpellAbilities()) {
                    String d = sa.toString();
                    System.out.println("  canPlay=" + sa.canPlay() + "  aiDecision=" + ai.canPlaySa(sa)
                        + "  :: " + d.substring(0, Math.min(90, d.length())));
                }
                System.out.println("AI WOULD PLAY: " + ai.chooseSpellAbilityToPlay());
            }
        }
        System.exit(0);
    }
}
