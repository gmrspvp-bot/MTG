import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// Stoneforge Mystic: which Equipment does Forge's AI fetch / put into play, and does it want to
// activate the put-into-play ability? Run with -Dforge.aipatches=threat,captain.
public class StoneforgeProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "StoneforgeProbe").createGame();
        Player me = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 4; i++) play(game, me, "Plains");
        Card cap = play(game, me, "Captain America, First Avenger");
        Card sfm = play(game, me, "Stoneforge Mystic");
        sfm.setSickness(false);
        CardCollection lib = new CardCollection();
        for (String n : new String[]{"Bonesplitter", "Swiftfoot Boots", "Kaldra Compleat", "Excalibur, Sword of Eden", "Colossus Hammer", "Champion's Helm"}) lib.add(zone(game, me, n, ZoneType.Library));
        SpellAbility etb = null, act = null;
        for (forge.game.trigger.Trigger t : sfm.getTriggers()) { if (t.ensureAbility() != null) etb = t.ensureAbility(); }
        for (SpellAbility s : sfm.getSpellAbilities()) { if (s.isActivatedAbility()) act = s; }
        etb.setActivatingPlayer(me); act.setActivatingPlayer(me);
        Card fetched = forge.ai.ability.ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Hand, Arrays.asList(ZoneType.Library), etb, lib, me, me);
        System.out.println("ETB search picks: " + fetched);
        forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
        System.out.println("activate with empty hand: " + ai.canPlaySa(act));
        CardCollection hand = new CardCollection();
        for (String n : new String[]{"Bonesplitter", "Kaldra Compleat"}) hand.add(zone(game, me, n, ZoneType.Hand));
        System.out.println("activate with Bonesplitter + Kaldra in hand: " + ai.canPlaySa(act));
        Card put = forge.ai.ability.ChangeZoneAi.chooseCardToHiddenOriginChangeZone(ZoneType.Battlefield, Arrays.asList(ZoneType.Hand), act, hand, me, me);
        System.out.println("put-into-play picks: " + put);
        System.out.println("activate in main 1 with Kaldra in hand (after fix): " + ai.canPlaySa(act));
        System.exit(0);
    }
    static Card play(Game game, Player p, String name) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        Thread.sleep(200);
        return c;
    }
    static Card zone(Game game, Player p, String name, ZoneType z) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveTo(z, c, null, null));
        Thread.sleep(150);
        return c;
    }
}
