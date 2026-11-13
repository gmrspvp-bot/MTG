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
 * Counterspell threat rules by kind (AiPatches.ThreatAwareCounterAi.kind, 2026-11-13). Other casts
 * each spell; Me decides whether it is worth countering. Wedding Ring and a 5-mana mana rock are
 * not; Super State, any Equipment, extra turns, 4-drop creatures and 6+ noncreature spells are.
 * Run from forge-installer-2.0.15 with -Dforge.ai.counterkind=true.
 */
public class CounterKindProbe {
    static int fails = 0;
    static Game game;
    static Player me, other;

    static void check(String name, boolean worth) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), other);
        game.getAction().invoke(() -> game.getAction().moveTo(ZoneType.Hand, c, null, null));
        try { Thread.sleep(150); } catch (InterruptedException e) { }
        SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(other);
        String why = AiPatches.ThreatAwareCounterAi.threat(me, sa);
        boolean ok = (why != null) == worth;
        if (!ok) fails++;
        System.out.println("PROBE " + (ok ? "ok  " : "FAIL") + " " + name + (worth ? " countered" : " let through") + ": " + why);
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable e) { e.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "PASS" : "FAIL " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        if (!AiPatches.ThreatAwareCounterAi.BY_KIND) { System.out.println("run with -Dforge.ai.counterkind=true"); fails++; return; }
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] { "Me", "Other", "Third" })
            players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        game = new Match(new GameRules(GameType.Commander), players, "CounterKindProbe").createGame();
        game.setAge(GameStage.Play);
        me = game.getPlayers().get(0);
        other = game.getPlayers().get(1);

        check("Wedding Ring", false);
        check("Gilded Lotus", false);
        check("Arcane Signet", false);
        check("Howling Mine", false);
        check("Super State", true);
        check("Lightning Greaves", true);
        check("Batterskull", true);
        check("Shadowspear", true);
        check("Time Warp", true);
        check("Sheoldred, the Apocalypse", true);
        check("Rhystic Study", true);
        check("Expropriate", true);
        check("Grizzly Bears", false);
    }
}
