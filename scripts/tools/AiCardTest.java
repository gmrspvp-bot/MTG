// Sets up a board from a Forge game-state file and reports how the AI rates
// every ability of one card. With "resolve", it also resolves that card's
// first ability for its controller and prints hand/graveyard afterwards.
//
// Usage (from forge-installer-2.0.15):
//   java -cp <classes>;<forge jar> AiCardTest <state file> "<card name>" [resolve]

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

public class AiCardTest {
    public static void main(String[] args) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();

        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck("A")).setPlayer(GamePlayerUtil.createAiPlayer("Opponent")));
        players.add(new RegisteredPlayer(new Deck("B")).setPlayer(GamePlayerUtil.createAiPlayer("Me")));
        Game game = new Match(new GameRules(GameType.Constructed), players, "AiCardTest").createGame();

        GameState state = new GameState();
        state.parse(Files.readAllLines(Paths.get(args[0])));
        state.applyToGame(game);
        Thread.sleep(3000); // applyToGame runs on Forge's game thread
        AiPatches.prepareGame(game);

        String name = args[1];
        boolean resolve = args.length > 2 && args[2].equals("resolve");
        for (Player p : game.getPlayers()) {
            System.out.println("BOARD " + p.getName() + " life=" + p.getLife() + " " + p.getCardsIn(ZoneType.Battlefield));
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (c.isCreature()) {
                    System.out.println("  EVAL " + c + " = " + forge.ai.ComputerUtilCard.evaluateCreature(c));
                }
            }
        }
        System.out.println("PHASE " + game.getPhaseHandler().getPhase() + ", turn of " + game.getPhaseHandler().getPlayerTurn()
            + ", next turn " + game.getPhaseHandler().getNextTurn());

        for (Player p : game.getPlayers()) {
            AiController ai = ((PlayerControllerAi) p.getController()).getAi();
            for (Card c : p.getCardsIn(Arrays.asList(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Command))) {
                if (!c.getName().equals(name)) continue;
                System.out.println("CARD " + c + " in " + c.getZone().getZoneType() + " (" + p.getName() + ")");
                for (SpellAbility sa : c.getSpellAbilities()) {
                    sa.setActivatingPlayer(p);
                    String d = sa.toString();
                    System.out.println("  canPlay=" + sa.canPlay() + " ai=" + ai.canPlaySa(sa) + " :: " + d.substring(0, Math.min(80, d.length())));
                }
                if (args.length > 2 && args[2].equals("trigger")) {
                    // Ask the AI how it would resolve each of this card's triggers (targets it picks).
                    for (forge.game.trigger.Trigger t : c.getTriggers()) {
                        SpellAbility tsa = t.ensureAbility();
                        if (tsa == null) continue;
                        tsa.setActivatingPlayer(p);
                        tsa.resetTargets();
                        boolean mandatory = !t.hasParam("OptionalDecider");
                        boolean ok = ai.doTrigger(tsa, mandatory);
                        String d = t.toString();
                        System.out.println("TRIGGER " + d.substring(0, Math.min(70, d.length())) + " -> willPlay=" + ok
                            + " targets=" + tsa.getTargets().getTargetCards());
                    }
                    continue;
                }
                List<SpellAbility> chosen = ai.chooseSpellAbilityToPlay();
                System.out.println("AI WOULD PLAY: " + chosen);
                if (chosen != null) {
                    for (SpellAbility s : chosen) {
                        System.out.println("  TARGETS: " + s.getTargets().getTargetCards() + " " + s.getTargets().getTargetPlayers());
                    }
                }
                if (resolve) {
                    SpellAbility sa = c.getFirstSpellAbility();
                    sa.setActivatingPlayer(p);
                    game.getAction().invoke(() -> {
                        AbilityUtils.resolve(sa);
                        // Let triggers caused by the spell go on the stack and resolve.
                        for (int n = 0; n < 50; n++) {
                            game.getTriggerHandler().runWaitingTriggers();
                            game.getStack().addAllTriggeredAbilitiesToStack();
                            if (game.getStack().isEmpty()) break;
                            game.getStack().resolveStack();
                        }
                    });
                    Thread.sleep(2000);
                    System.out.println("AFTER RESOLVE hand=" + p.getCardsIn(ZoneType.Hand) + " graveyard=" + p.getCardsIn(ZoneType.Graveyard));
                    System.out.println("AFTER BATTLEFIELD " + p.getName() + ": " + p.getCardsIn(ZoneType.Battlefield));
                    for (Player q : game.getPlayers()) {
                        for (Card b : q.getCardsIn(ZoneType.Battlefield)) {
                            if (!b.isCreature()) continue;
                            String text = b.getAbilityText().replace("\n", " | ");
                            System.out.println("  AFTER " + q.getName() + ": " + b + " " + b.getNetPower() + "/" + b.getNetToughness()
                                + " :: " + text.substring(0, Math.min(110, text.length())));
                        }
                    }
                }
            }
        }
        System.exit(0);
    }
}
