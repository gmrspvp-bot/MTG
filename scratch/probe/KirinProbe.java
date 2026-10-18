import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

// Cloudsteel Kirin: is it a valid target for Catch ("Equipment.YouCtrl"), and does attaching it
// to Captain America stop us losing at 0 life?
public class KirinProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "KirinProbe").createGame();
        Player me = game.getPlayers().get(0);
        Card cap = add(game, me, "Captain America, First Avenger");
        Card kirin = add(game, me, "Cloudsteel Kirin");
        System.out.println("Kirin valid Catch target: " + kirin.isValid("Equipment.YouCtrl", me, cap, null) + "  isEquipment=" + kirin.isEquipment());
        forge.game.spellability.SpellAbility catchSa = null;
        for (forge.game.trigger.Trigger t : cap.getTriggers()) { if (t.ensureAbility() != null) catchSa = t.ensureAbility(); }
        catchSa.setActivatingPlayer(me);
        System.out.println("Catch ability: " + catchSa + "  can target Kirin: " + catchSa.canTarget(kirin));
        final forge.game.spellability.SpellAbility fsa = catchSa;
        game.getAction().invoke(() -> { kirin.attachToEntity(cap, fsa); game.getAction().checkStaticAbilities(); });
        Thread.sleep(1000);
        System.out.println("attached: " + cap.getEquippedBy() + "  Kirin still a creature: " + kirin.isCreature() + "  Cap flying: " + cap.hasKeyword(forge.game.keyword.Keyword.FLYING));
        game.getAction().invoke(() -> { me.setLife(0, null); game.getAction().checkStateEffects(true); });
        Thread.sleep(1000);
        System.out.println("at 0 life: hasLost=" + me.hasLost() + "  gameOver=" + game.isGameOver());
        System.exit(0);
    }
    static Card add(Game game, Player p, String name) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveToPlay(c, null, null));
        try { Thread.sleep(300); } catch (InterruptedException e) {}
        return c;
    }
}
