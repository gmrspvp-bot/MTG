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

// Soul-Guide Lantern decisions (run with -Dforge.aipatches=abilities,threat,captain):
// opponents' graveyard empty -> keep it; with Lyra Dawnbringer there -> exile their graveyards; never the draw mode while that's worth it.
public class LanternProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "LanternProbe").createGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 3; i++) put(game, me, "Plains", ZoneType.Battlefield);
        Card lantern = put(game, me, "Soul-Guide Lantern", ZoneType.Battlefield);
        forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
        SpellAbility exile = null, draw = null;
        for (SpellAbility s : lantern.getSpellAbilities()) { s.setActivatingPlayer(me); if (s.getApi() == forge.game.ability.ApiType.ChangeZoneAll) exile = s; else if (s.getApi() == forge.game.ability.ApiType.Draw) draw = s; }
        System.out.println("empty graveyards: exile -> " + ai.canPlaySa(exile) + ", draw -> " + ai.canPlaySa(draw));
        put(game, opp, "Lyra Dawnbringer", ZoneType.Graveyard);
        System.out.println("Lyra in their graveyard: exile -> " + ai.canPlaySa(exile) + ", draw -> " + ai.canPlaySa(draw));
        System.exit(0);
    }
    static Card put(Game game, Player p, String name, ZoneType z) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> { if (z == ZoneType.Battlefield) game.getAction().moveToPlay(c, null, null); else game.getAction().moveTo(z, c, null, null); });
        Thread.sleep(250);
        return c;
    }
}
