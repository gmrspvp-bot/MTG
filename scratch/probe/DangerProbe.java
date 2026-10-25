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
 * Threat detection overhaul (2026-10-26). A table like the owner's game of 2026-10-22: "Angels"
 * on 100 life with Lyra Dawnbringer, Seluma and Karmic Guide; "Doom" on 30 with two plain
 * creatures; us (the AI) with a little board. Prints each player's threat() and, for a Scorching
 * Shot Forge aimed at Doom's creature, what ThreatRetarget.leaderSwap makes of it.
 * Arg "on": expects Angels clearly ahead and the shot moved to an Angel; "off": no swap.
 * Run with -Dforge.ai.danger=true / false, from forge-installer-2.0.15, tools classes + Forge jar.
 */
public class DangerProbe {
    static Game game;

    static Card put(Player p, String name) {
        Card c = forge.game.card.Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        game.getAction().invoke(() -> game.getAction().moveTo(ZoneType.Battlefield, c, null, null));
        return c;
    }

    public static void main(String[] a) throws Exception {
        boolean on = a.length > 0 && a[0].equals("on");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] { "Me", "Angels", "Doom" })
            players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        game = new Match(new GameRules(GameType.Commander), players, "DangerProbe").createGame();
        game.setAge(GameStage.Play);
        Player me = game.getPlayers().get(0), angels = game.getPlayers().get(1), doom = game.getPlayers().get(2);
        put(me, "Grizzly Bears"); put(me, "Hill Giant");
        Card lyra = put(angels, "Lyra Dawnbringer"); put(angels, "Seluma, Light of Aysen"); put(angels, "Karmic Guide");
        Card wurm = put(doom, "Craw Wurm"); put(doom, "Hill Giant");
        game.getAction().invoke(() -> { angels.setLife(100, null); doom.setLife(30, null); me.setLife(30, null); });
        Thread.sleep(800);
        for (Player p : game.getPlayers())
            System.out.printf("%-7s life %3d  board %6.1f  threat %6.1f%n", p.getName(), p.getLife(), HumanHooks.boardThreat(p), HumanHooks.threat(p));
        Card shot = forge.game.card.Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Scorching Shot"), me);
        SpellAbility sa = shot.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(wurm);
        Card swap = AiPatches.ThreatRetarget.leaderSwap(me, sa, wurm, c -> c.isCreature() && 5 >= c.getNetToughness() - c.getDamage());
        System.out.println("Scorching Shot aimed at Doom's " + wurm.getName() + " -> " + (swap == null ? "kept" : swap.getName() + " (" + swap.getController().getName() + ")"));
        boolean angelsAhead = HumanHooks.threat(angels) >= 1.3 * HumanHooks.threat(doom);
        boolean ok = on ? angelsAhead && swap != null && swap.getController() == angels : swap == null;
        System.out.println(ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
