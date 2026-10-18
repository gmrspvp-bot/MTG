import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// Face slot: Basilisk Collar (deathtouch) stays, Excalibur thrown for 12 -> best 1-2 creatures + rest at a face.
// Run with and without -Dforge.captain.faceslot=true.
public class FaceSlotProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2", "P3"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "FaceSlotProbe").createGame();
        Player me = game.getPlayers().get(0), o1 = game.getPlayers().get(1), o2 = game.getPlayers().get(2);
        Card cap = add(game, me, "Captain America, First Avenger");
        Card collar = add(game, me, "Basilisk Collar");
        Card exc = add(game, me, "Excalibur, Sword of Eden");
        add(game, o1, "Colossal Dreadmaw"); add(game, o1, "Grizzly Bears"); add(game, o2, "Serra Angel");
        game.getAction().invoke(() -> { collar.attachToEntity(cap, null); exc.attachToEntity(cap, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(1500);
        for (SpellAbility sa : cap.getSpellAbilities()) {
            if (!sa.isActivatedAbility()) continue;
            sa.setActivatingPlayer(me);
            if (AiPatches.CaptainPolicy.totalDamage(sa) != 12) continue;
            System.out.println("faceslot=" + AiPatches.CaptainPolicy.FACE_SLOT + " keepsDeathtouch=" + AiPatches.CaptainPolicy.keepsDeathtouch(sa)
                + " -> " + AiPatches.CaptainPolicy.splitKill(me, sa) + " " + alloc(sa));
        }
        System.exit(0);
    }
    static Card add(Game game, Player p, String name) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        try { Thread.sleep(300); } catch (InterruptedException e) {}
        return c;
    }
    static String alloc(SpellAbility sa) {
        StringBuilder sb = new StringBuilder("[");
        for (forge.game.GameObject o : sa.getTargets()) sb.append(o).append("=").append(sa.getDividedValue(o)).append(" ");
        return sb.append("]").toString();
    }
}
