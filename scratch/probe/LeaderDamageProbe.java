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
 * Damage at the leader (AiPatches.leaderShot, 2026-11-06). The AI has Goblin Bombardment and a
 * creature; Low is on 12 life, High on 40. Through ThrowAwareDamageAi's real trigger path Forge
 * picks Low, and the shot must end on High. On 1 life Low dies to it, so the shot must stay. A
 * shot at a creature, or with the switch off, is not this rule's business.
 *
 * Run from forge-installer-2.0.15 with the tools classes and the Forge jar.
 */
public class LeaderDamageProbe {
    static int fails = 0;

    static void check(String what, Player got, Player want) {
        if (got != want) fails++;
        System.out.println("PROBE " + (got == want ? "ok  " : "FAIL") + " " + what + ": " + got + " (want " + want + ")");
    }
    static Player target(SpellAbility sa) {
        Player t = null;
        for (Player p : sa.getTargets().getTargetPlayers()) t = p;
        return t;
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable e) { e.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "PASS" : "FAIL " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        HumanHooks.enabled = true;   // and run with -Dforge.ai.leaderdamage=true
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] { "Me", "Low", "High" })
            players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "LeaderDamageProbe").createGame();
        game.setAge(GameStage.Play);
        Player me = game.getPlayers().get(0), low = game.getPlayers().get(1), high = game.getPlayers().get(2);
        Card gb = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Goblin Bombardment"), me);
        game.getAction().invoke(() -> {
            game.getAction().moveTo(ZoneType.Battlefield, gb, null, null);
            low.setLife(12, null); high.setLife(40, null);
        });
        Thread.sleep(800);
        SpellAbility sa = null;
        for (SpellAbility s : gb.getSpellAbilities()) if (s.getApi() == forge.game.ability.ApiType.DealDamage) sa = s;
        sa.setActivatingPlayer(me);
        System.out.println("PROBE ability: " + sa.getApi() + ", weakest opponent " + me.getWeakestOpponent());

        sa.resetTargets();
        sa.getTargets().add(low);
        System.out.println("PROBE note: " + AiPatches.leaderShot(me, sa));
        check("non-lethal face moves to the leader", target(sa), high);

        game.getAction().invoke(() -> low.setLife(1, null));
        Thread.sleep(300);
        sa.resetTargets();
        sa.getTargets().add(low);
        AiPatches.leaderShot(me, sa);
        check("a kill stays on the player it kills", target(sa), low);

        game.getAction().invoke(() -> low.setLife(12, null));
        Thread.sleep(300);
        sa.resetTargets();
        sa.getTargets().add(me);
        AiPatches.leaderShot(me, sa);
        check("not an opponent: left alone", target(sa), me);

        // Forge's own trigger path, through the class the patch installs.
        sa.resetTargets();
        new AiPatches.ThrowAwareDamageAi().doTrigger(me, sa, true);
        check("mandatory trigger path ends on the leader", target(sa), high);
    }
}
