import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * Why the "dice" AI patch decides to roll with Mr. House / The Deck of Many Things at an
 * opponent's end step but the activation rarely happens (2026-10-21). Board: AI with Mr. House,
 * Wyll and four untapped lands, an opponent's end step, the AI holding priority. Prints what
 * Forge's AiController says about each dice ability (canPlaySa) and what it would play.
 * Run from forge-installer-2.0.15 with scripts\tools\classes and the jar; -Dforge.aipatches=dice.
 */
public class DiceAiProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"House", "Opp"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "DiceAiProbe").createGame();
        game.setAge(GameStage.Play);
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=6", "activeplayer=ai", "activephase=END_OF_TURN", "humanlife=40", "ailife=40",
            (Boolean.getBoolean("probe.treasures")
                ? "humanbattlefield=Mr. House, President and CEO;Wyll, Blade of Frontiers;The Deck of Many Things;Mountain|Tapped;Plains|Tapped;Swamp|Tapped;T:c_a_treasure_sac;T:c_a_treasure_sac;T:c_a_treasure_sac;T:c_a_treasure_sac;T:c_a_treasure_sac"
                : "humanbattlefield=Mr. House, President and CEO;Wyll, Blade of Frontiers;The Deck of Many Things;Mountain;Plains;Swamp;Mountain;Plains;Swamp"),
            "humanhand=", "aibattlefield=Mountain"));
        st.applyToGame(game);
        Thread.sleep(2500);
        Player me = game.getPlayers().get(0);
        game.getAction().invoke(() -> {
            game.getPhaseHandler().setPriority(me);
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) { c.setSickness(false); }
            forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
            System.out.println("phase " + game.getPhaseHandler().getPhase() + ", turn of " + game.getPhaseHandler().getPlayerTurn().getName()
                + ", priority " + game.getPhaseHandler().getPriorityPlayer().getName());
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                for (SpellAbility sa : c.getSpellAbilities()) {
                    if (sa.getApi() != forge.game.ability.ApiType.RollDice) continue;
                    sa.setActivatingPlayer(me);
                    System.out.println("  " + c.getName() + ": canPlay=" + sa.canPlay()
                        + "  canPlaySa=" + ai.canPlaySa(sa)
                        + "  canPay=" + forge.ai.ComputerUtilCost.canPayCost(sa, me, false));
                }
            }
            List<SpellAbility> chosen = ai.chooseSpellAbilityToPlay();
            System.out.println("AI would play: " + chosen);
        });
        Thread.sleep(1500);
        System.exit(0);
    }
}
