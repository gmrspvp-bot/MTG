// Loads a deck's main cards into an AI player's library and prints which card
// Forge's AI ranks as "best" (the ranking its search effects use for an
// unrestricted "search your library for a card").
//
// Usage (from forge-installer-2.0.15): java -cp <classes>;<forge jar> TutorTest <deck.dck>

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import forge.GuiDesktop;
import forge.ai.ComputerUtilCard;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

public class TutorTest {
    public static void main(String[] args) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);

        // Main-deck card names from the .dck file.
        List<String> names = new ArrayList<>();
        boolean inMain = false;
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            if (line.startsWith("[")) { inMain = line.equalsIgnoreCase("[main]"); continue; }
            if (inMain && line.matches("^\\d+ .+")) {
                int sp = line.indexOf(' ');
                int qty = Integer.parseInt(line.substring(0, sp));
                for (int i = 0; i < qty; i++) names.add(line.substring(sp + 1).trim());
            }
        }

        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("A")).setPlayer(GamePlayerUtil.createAiPlayer("Opponent")));
        players.add(new RegisteredPlayer(new Deck("B")).setPlayer(GamePlayerUtil.createAiPlayer("Tutor")));
        Game game = new Match(new GameRules(GameType.Constructed), players, "TutorTest").createGame();

        GameState state = new GameState();
        List<String> lines = new ArrayList<>();
        lines.add("[state]");
        lines.add("turn=6");
        lines.add("activeplayer=ai");
        lines.add("activephase=MAIN1");
        lines.add("aibattlefield=Plains;Plains;Mountain;Mountain;Swamp;Swamp");
        lines.add("ailibrary=" + String.join(";", names));
        state.parse(lines);
        state.applyToGame(game);
        Thread.sleep(3000);

        for (Player p : game.getPlayers()) {
            CardCollection lib = new CardCollection(p.getCardsIn(ZoneType.Library));
            if (lib.isEmpty()) continue;
            System.out.println("Library size: " + lib.size());
            Card best = ComputerUtilCard.getBestAI(lib);
            System.out.println("BEST (getBestAI): " + best);
            CardCollection nonLand = new CardCollection();
            for (Card c : lib) if (!c.isLand()) nonLand.add(c);
            System.out.println("MOST EXPENSIVE PERMANENT: " + ComputerUtilCard.getMostExpensivePermanentAI(nonLand));
            System.out.println("BEST CREATURE: " + ComputerUtilCard.getBestCreatureAI(lib));
        }
        System.exit(0);
    }
}
