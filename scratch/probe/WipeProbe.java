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

// AI decisions for v8's wipes after the fixes (run with -Dforge.aipatches=threat,captain):
//  Winds of Abandon with 3 opposing creatures: overload should be WillPlay, single target held.
//  Blasphemous Act with our bare Captain America: held; with Darksteel Plate on him: allowed.
public class WipeProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "WipeProbe").createGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 6; i++) play(game, me, "Plains");
        for (int i = 0; i < 3; i++) play(game, me, "Mountain");
        play(game, opp, "Grizzly Bears"); play(game, opp, "Serra Angel"); play(game, opp, "Colossal Dreadmaw");
        forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
        Card winds = hand(game, me, "Winds of Abandon");
        for (SpellAbility sa : winds.getAllSpellAbilities()) {
            sa.setActivatingPlayer(me);
            System.out.println("Winds " + (sa.isAlternativeCost(forge.game.spellability.AlternativeCost.Overload) ? "OVERLOAD" : "single  ") + " -> " + ai.canPlaySa(sa));
        }
        Card act = hand(game, me, "Blasphemous Act");
        SpellAbility actSa = act.getFirstSpellAbility(); actSa.setActivatingPlayer(me);
        System.out.println("Blasphemous Act, no Cap        -> " + ai.canPlaySa(actSa));
        Card cap = play(game, me, "Captain America, First Avenger");
        System.out.println("Blasphemous Act, bare Cap 4/4  -> " + ai.canPlaySa(actSa));
        Card plate = play(game, me, "Darksteel Plate");
        game.getAction().invoke(() -> { plate.attachToEntity(cap, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(800);
        System.out.println("Blasphemous Act, Cap + Darksteel Plate -> " + ai.canPlaySa(actSa));
        game.getAction().invoke(() -> { plate.unattachFromEntity(cap); game.getAction().checkStaticAbilities(); }); Thread.sleep(500); play(game, opp, "Hill Giant"); play(game, opp, "Goblin Piker");
        System.out.println("Blasphemous Act, bare Cap vs 5 of theirs -> " + ai.canPlaySa(actSa));
        System.exit(0);
    }
    static Card play(Game game, Player p, String name) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        Thread.sleep(200);
        return c;
    }
    static Card hand(Game game, Player p, String name) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveTo(ZoneType.Hand, c, null, null));
        Thread.sleep(200);
        return c;
    }
}
