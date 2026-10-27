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
 * Equipment-aware removal (2026-10-27). The "Eivor" player has its commander wearing Sword of
 * Fire and Ice, Excalibur and Lightning Greaves, plus a Sol Ring; we hold Anguished Unmaking (any
 * nonland permanent) and Path to Exile (creatures only). Checks ThreatRetarget.equipmentSwap:
 *   - Unmaking aimed at Sol Ring -> the best equipment (Excalibur: mana value 6, on a commander)
 *   - Unmaking aimed at Eivor    -> a piece on Eivor
 *   - Path aimed at Eivor        -> kept (Path can't hit artifacts)
 * Arg "on" / "off" with -Dforge.ai.equipremoval=true / false. Run from forge-installer-2.0.15,
 * tools classes + Forge jar on the classpath (-Djava.awt.headless=true keeps Forge's error
 * window off the screen).
 */
public class EquipRemovalProbe {
    static Card swap(Player me, String spell, Card target) {
        Card s = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(spell), me);
        SpellAbility sa = s.getFirstSpellAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(target);
        Card r = AiPatches.ThreatRetarget.equipmentSwap(me, sa, target, c -> true);
        System.out.println(spell + " aimed at " + target.getName() + " -> " + (r == null ? "kept" : r.getName()));
        return r;
    }

    public static void main(String[] a) throws Exception {
        boolean on = a.length > 0 && a[0].equals("on");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] { "Eivor", "Me" })
            players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "EquipRemovalProbe").createGame();
        GameState st = new GameState();
        st.parse(Arrays.asList("[state]", "turn=5", "activeplayer=ai", "activephase=MAIN1", "humanlife=40", "ailife=40",
            "humanbattlefield=Eivor, Battle-Ready|Id:1|IsCommander;Sol Ring|Id:2;Sword of Fire and Ice|Id:3|AttachedTo:1;"
                + "Excalibur, Sword of Eden|Id:4|AttachedTo:1;Lightning Greaves|Id:5|AttachedTo:1",
            "aibattlefield=Hill Giant"));
        st.applyToGame(game);
        Thread.sleep(3000);
        Player eivorP = game.getPlayers().get(0), me = game.getPlayers().get(1);
        Card eivor = null, rock = null;
        for (Card c : eivorP.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().startsWith("Eivor")) eivor = c;
            if (c.getName().equals("Sol Ring")) rock = c;
        }
        System.out.println("Eivor wears " + eivor.getEquippedBy().size() + " pieces, commander=" + eivor.isCommander());
        Card r1 = swap(me, "Anguished Unmaking", rock);
        Card r2 = swap(me, "Anguished Unmaking", eivor);
        Card r3 = swap(me, "Path to Exile", eivor);
        boolean ok = on
            ? r1 != null && r1.getName().startsWith("Excalibur") && r2 != null && r2.getEquipping() == eivor && r3 == null
            : r1 == null && r2 == null && r3 == null;
        System.out.println(ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
