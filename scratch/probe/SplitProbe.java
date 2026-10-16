import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// Split-throw checks: deathtouch wipe (Battle-Mace stays, MV-3 sword thrown) and exact-lethal split.
public class SplitProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2", "P3"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "SplitProbe").createGame();
        Player me = game.getPlayers().get(0), o1 = game.getPlayers().get(1), o2 = game.getPlayers().get(2);
        Card cap = add(game, me, "Captain America, First Avenger");
        Card mace = add(game, me, "Nazgûl Battle-Mace");
        Card sword = add(game, me, "Sword of Light and Shadow");
        add(game, o1, "Colossal Dreadmaw"); add(game, o1, "Grizzly Bears"); add(game, o2, "Serra Angel"); add(game, o2, "Darksteel Myr");
        game.getAction().invoke(() -> { mace.attachToEntity(cap, null); sword.attachToEntity(cap, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(1500);
        System.out.println("cap deathtouch=" + cap.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH) + " equipped=" + cap.getEquippedBy());
        for (SpellAbility sa : cap.getSpellAbilities()) {
            if (!sa.isActivatedAbility()) continue;
            sa.setActivatingPlayer(me);
            int dmg = AiPatches.CaptainPolicy.totalDamage(sa);
            if (dmg != 3 && dmg != 5) continue;
            String why = AiPatches.CaptainPolicy.splitKill(me, sa);
            System.out.println("throw " + dmg + ": keepsDeathtouch=" + AiPatches.CaptainPolicy.keepsDeathtouch(sa) + " -> " + why + " " + alloc(sa));
            if (dmg == 3) {
                o1.setLife(2, null); o2.setLife(20, null);
                System.out.println("lethal split 3 (P2 at 2): " + AiPatches.CaptainPolicy.retargetLethal(me, sa) + " " + alloc(sa));
            }
        }
        System.exit(0);
    }
    static Card add(Game game, Player p, String name) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        final Card fc = c;
        game.getAction().invoke(() -> game.getAction().moveToPlay(fc, null, null));
        try { Thread.sleep(300); } catch (InterruptedException e) {}
        return c;
    }
    static String alloc(SpellAbility sa) {
        StringBuilder sb = new StringBuilder("[");
        for (forge.game.GameObject o : sa.getTargets()) sb.append(o).append("=").append(sa.getDividedValue(o)).append(" ");
        return sb.append("]").toString();
    }
}
