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

/**
 * The lethal shot (2026-10-26): the AI has Rin and Seri, Inseparable and five Dogs (Spirited Companion); "Low" is on
 * 4 life, "High" on 30. Rin and Seri's tap ability (damage = the Dogs it controls) must be aimed at Low and
 * played. Then Low goes to 9 life: no longer lethal, so no shot. Run from forge-installer-2.0.15
 * with the tools classes (or a test build of them) and the Forge jar.
 */
public class LethalShotProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] { "Me", "Low", "High" })
            players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "LethalShotProbe").createGame();
        game.setAge(GameStage.Play);
        Player me = game.getPlayers().get(0), low = game.getPlayers().get(1), high = game.getPlayers().get(2);
        Card rin = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Rin and Seri, Inseparable"), me);
        game.getAction().invoke(() -> {
            game.getAction().moveTo(ZoneType.Battlefield, rin, null, null);
            rin.setSickness(false);
            for (int i = 0; i < 5; i++) {
                Card cat = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Spirited Companion"), me);   // a Dog
                game.getAction().moveTo(ZoneType.Battlefield, cat, null, null);
            }
            low.setLife(4, null); high.setLife(30, null);
        });
        Thread.sleep(800);
        SpellAbility ab = null;
        for (SpellAbility s : rin.getSpellAbilities()) if (s.isActivatedAbility() && s.getApi() == forge.game.ability.ApiType.DealDamage) ab = s;
        if (ab == null) { System.out.println("no damage ability found on Rin and Seri"); System.exit(2); }
        ab.setActivatingPlayer(me);
        String first = AiPatches.lethalShot(me, ab);
        System.out.println("Low on 4: " + first + " | target " + ab.getTargets().getTargetPlayers());
        boolean ok1 = false;
        if (first != null) for (Player p : ab.getTargets().getTargetPlayers()) if (p == low) ok1 = true;
        final SpellAbility sa2 = ab;
        game.getAction().invoke(() -> low.setLife(9, null));
        Thread.sleep(300);
        sa2.resetTargets();
        String second = AiPatches.lethalShot(me, sa2);
        System.out.println("Low on 9: " + second);
        boolean ok2 = second == null;
        System.out.println(ok1 && ok2 ? "PASS" : "FAIL");
        System.exit(ok1 && ok2 ? 0 : 1);
    }
}
