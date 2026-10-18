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

// Sword of Kaldra stays on Captain America while a 3-mana sword is thrown: does the AI split
// 1 damage each (indestructible included), and does Forge exile the creatures hit?
public class KaldraSwordProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2", "P3"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "KaldraSwordProbe").createGame();
        Player me = game.getPlayers().get(0), o1 = game.getPlayers().get(1), o2 = game.getPlayers().get(2);
        Card cap = add(game, me, "Captain America, First Avenger");
        Card ks = add(game, me, "Sword of Kaldra");
        Card sls = add(game, me, "Sword of Light and Shadow");
        Card myr = add(game, o1, "Darksteel Myr"); Card angel = add(game, o1, "Serra Angel"); Card maw = add(game, o2, "Colossal Dreadmaw");
        game.getAction().invoke(() -> { ks.attachToEntity(cap, null); sls.attachToEntity(cap, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(1000);
        SpellAbility throw3 = null;
        for (SpellAbility sa : cap.getSpellAbilities()) { if (sa.isActivatedAbility()) { sa.setActivatingPlayer(me); if (AiPatches.CaptainPolicy.totalDamage(sa) == 3) throw3 = sa; } }
        System.out.println("keepsSwordOfKaldra=" + AiPatches.CaptainPolicy.keepsSwordOfKaldra(throw3));
        System.out.println("splitKill -> " + AiPatches.CaptainPolicy.splitKill(me, throw3));
        StringBuilder sb = new StringBuilder();
        for (forge.game.GameObject o : throw3.getTargets()) sb.append(o).append("=").append(throw3.getDividedValue(o)).append(" ");
        System.out.println("allocation: " + sb);
        final SpellAbility fs = throw3;
        game.getAction().invoke(() -> { forge.game.ability.AbilityUtils.resolve(fs); game.getTriggerHandler().runWaitingTriggers(); System.out.println("stack after throw: " + game.getStack().size() + "  damage on Myr/Angel/Maw: " + myr.getDamage() + "/" + angel.getDamage() + "/" + maw.getDamage()); int guard = 0; while (!game.getStack().isEmpty() && guard++ < 10) { game.getStack().resolveStack(); game.getTriggerHandler().runWaitingTriggers(); } game.getAction().checkStateEffects(true); });
        Thread.sleep(2500);
        for (Card c : new Card[]{myr, angel, maw}) {
            Card now = game.getCardState(c);
            System.out.println("after throw: " + c.getName() + " is in " + (now == null ? "?" : now.getZone() == null ? "?" : now.getZone().getZoneType()));
        }
        System.exit(0);
    }
    static Card add(Game game, Player p, String name) throws Exception {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        Thread.sleep(250);
        return c;
    }
}
