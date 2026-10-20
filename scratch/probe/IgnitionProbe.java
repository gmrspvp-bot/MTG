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

// Chandra's Ignition AI (AiPatches.IgnitionAi), run with -Dforge.aipatches=threat,captain:
//  1. only a 2/2 of ours vs their big creatures        -> held
//  2. a 10-power creature vs three of theirs           -> cast at it
//  3. a 1-power deathtoucher vs their board            -> cast (deathtouch kills all)
//  4. would kill our own Captain America for little    -> held
//  5. an opponent on low life                          -> cast (kills a player)
public class IgnitionProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2", "P3"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "IgnitionProbe").createGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1), opp2 = game.getPlayers().get(2);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 4; i++) play(game, me, "Plains");
        for (int i = 0; i < 3; i++) play(game, me, "Mountain");
        forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
        Card ign = hand(game, me, "Chandra's Ignition");
        SpellAbility sa = ign.getFirstSpellAbility();
        sa.setActivatingPlayer(me);

        Card bear = play(game, me, "Grizzly Bears");
        play(game, opp, "Serra Angel"); play(game, opp, "Colossal Dreadmaw"); play(game, opp2, "Hill Giant");
        report("1. only a 2/2 of ours", ai, sa);

        Card big = play(game, me, "Colossal Dreadmaw");          // 6/6
        Card hammer = play(game, me, "Colossus Hammer");          // +10/+10
        game.getAction().invoke(() -> { hammer.attachToEntity(big, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(600);
        report("2. 16-power Dreadmaw vs three of theirs", ai, sa);

        game.getAction().invoke(() -> { hammer.unattachFromEntity(big); game.getAction().checkStaticAbilities(); });
        game.getAction().invoke(() -> game.getAction().moveToGraveyard(big, null));
        Thread.sleep(400);
        Card collar = play(game, me, "Basilisk Collar");          // deathtouch, lifelink
        game.getAction().invoke(() -> { collar.attachToEntity(bear, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(600);
        report("3. 2-power deathtouch Bears vs their board", ai, sa);

        game.getAction().invoke(() -> { collar.unattachFromEntity(bear); game.getAction().checkStaticAbilities(); });
        Thread.sleep(300);
        Card cap = play(game, me, "Captain America, First Avenger");
        Card giant = play(game, me, "Hill Giant");                // 3/3: 3 damage kills a 2/2 Bears, not much else
        for (Card c : new ArrayList<>(opp.getCreaturesInPlay())) game.getAction().invoke(() -> game.getAction().moveToGraveyard(c, null));
        for (Card c : new ArrayList<>(opp2.getCreaturesInPlay())) game.getAction().invoke(() -> game.getAction().moveToGraveyard(c, null));
        Thread.sleep(400);
        play(game, opp, "Goblin Piker");
        report("4. small gain, Cap " + cap.getNetPower() + "/" + cap.getNetToughness() + " on board", ai, sa);

        game.getAction().invoke(() -> opp2.setLife(3, null));
        Thread.sleep(300);
        report("5. an opponent at 3 life", ai, sa);
        System.exit(0);
    }

    static void report(String what, forge.ai.AiController ai, SpellAbility sa) {
        sa.resetTargets();
        Object d = ai.canPlaySa(sa);
        System.out.println(String.format("%-48s -> %s  target=%s", what, d, sa.getTargets()));
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
