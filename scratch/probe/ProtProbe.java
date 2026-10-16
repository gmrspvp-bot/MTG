import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// protectionOk with -Dforge.captain.protthrow=user: Helm + Cloak (both MV 3) vs Cloak alone, in main 2.
public class ProtProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "ProtProbe").createGame();
        Player me = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, me);
        Card cap = add(game, me, "Captain America, First Avenger");
        Card helm = add(game, me, "Champion's Helm");
        Card cloak = add(game, me, "Whispersilk Cloak");
        game.getAction().invoke(() -> { cloak.attachToEntity(cap, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(800);
        SpellAbility throw3 = null;
        for (SpellAbility sa : cap.getSpellAbilities()) { if (sa.isActivatedAbility()) { sa.setActivatingPlayer(me); if (AiPatches.CaptainPolicy.totalDamage(sa) == 3) throw3 = sa; } }
        System.out.println("phase=" + game.getPhaseHandler().getPhase() + " turn of " + game.getPhaseHandler().getPlayerTurn());
        System.out.println("Cloak only:   protectionOk=" + AiPatches.CaptainPolicy.protectionOk(me, throw3));
        game.getAction().invoke(() -> { helm.attachToEntity(cap, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(800);
        System.out.println("Helm + Cloak: protectionOk=" + AiPatches.CaptainPolicy.protectionOk(me, throw3));
        System.exit(0);
    }
    static Card add(Game game, Player p, String name) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        try { Thread.sleep(300); } catch (InterruptedException e) {}
        return c;
    }
}
