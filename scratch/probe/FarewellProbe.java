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

// Farewell's modes for Captain America's player (run with -Dforge.aipatches=abilities,threat,captain):
// 1) small board vs us -> hold; 2) four angels + Lyra in their graveyard -> creatures + graveyards, never artifacts.
public class FarewellProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "FarewellProbe").createGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (int i = 0; i < 6; i++) put(game, me, "Plains", ZoneType.Battlefield);
        Card cap = put(game, me, "Captain America, First Avenger", ZoneType.Battlefield); cap.setCommander(true);
        Card hammer = put(game, me, "Colossus Hammer", ZoneType.Battlefield); put(game, me, "Sol Ring", ZoneType.Battlefield);
        game.getAction().invoke(() -> { hammer.attachToEntity(cap, null); game.getAction().checkStaticAbilities(); }); Thread.sleep(500);
        put(game, opp, "Grizzly Bears", ZoneType.Battlefield);
        Card fw = put(game, me, "Farewell", ZoneType.Hand);
        forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
        SpellAbility sa = fw.getFirstSpellAbility(); sa.setActivatingPlayer(me);
        System.out.println("1) one Grizzly Bears vs our Cap: " + ai.canPlaySa(sa));
        for (String n : new String[]{"Serra Angel", "Baneslayer Angel", "Lyra Dawnbringer", "Archangel of Thune"}) put(game, opp, n, ZoneType.Battlefield);
        put(game, opp, "Sun Titan", ZoneType.Graveyard);
        sa.setChosenList(null);
        System.out.println("2) four angels + Sun Titan in their graveyard: " + ai.canPlaySa(sa));
        StringBuilder sb = new StringBuilder();
        if (sa.getChosenList() != null) for (SpellAbility s : sa.getChosenList()) sb.append(s.getParamOrDefault("ChangeType", "")).append("/").append(s.getParam("Origin")).append(" ");
        System.out.println("   chosen modes: " + sb);
        System.exit(0);
    }
    static Card put(Game game, Player p, String name, ZoneType z) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> { if (z == ZoneType.Battlefield) game.getAction().moveToPlay(c, null, null); else game.getAction().moveTo(z, c, null, null); });
        Thread.sleep(200);
        return c;
    }
}
