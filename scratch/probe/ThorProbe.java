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

// Thor's attack flicker target, and Sword of Kaldra not thrown away (run with -Dforge.aipatches=threat,captain).
public class ThorProbe {
    public static void main(String[] a) throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        AiPatches.apply();
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[]{"P1", "P2"}) players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "ThorProbe").createGame();
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, me);
        Card cap = add(game, me, "Captain America, First Avenger");
        Card thor = add(game, me, "The Mighty Thor, Jane Foster");
        Card kaldra = add(game, me, "Kaldra Compleat");
        Card ks = add(game, me, "Sword of Kaldra");
        Card sls = add(game, me, "Sword of Light and Shadow");
        Card angel = add(game, opp, "Serra Angel"); Card bears = add(game, opp, "Grizzly Bears");
        game.getAction().invoke(() -> { kaldra.attachToEntity(cap, null); ks.attachToEntity(cap, null); sls.attachToEntity(cap, null); bears.tap(true, null, null); game.getAction().checkStaticAbilities(); });
        Thread.sleep(1000);
        SpellAbility flick = null;
        for (forge.game.trigger.Trigger t : thor.getTriggers()) { SpellAbility s = t.ensureAbility(); if (s != null && "Exile".equals(s.getParam("Destination"))) flick = s; }
        flick.setActivatingPlayer(me);
        forge.ai.AiAbilityDecision d = forge.game.ability.ApiType.ChangeZone == flick.getApi()
            ? AiPatches.ThreatChangeZoneAi.thorFlicker(me, flick) : null;
        System.out.println("Thor flicker decision: " + d + " targets " + flick.getTargets().getTargetCards());
        for (SpellAbility s : cap.getSpellAbilities()) {
            if (!s.isActivatedAbility()) continue; s.setActivatingPlayer(me);
            int dmg = AiPatches.CaptainPolicy.totalDamage(s);
            if (dmg == 4 || dmg == 3) System.out.println("throw " + dmg + ": throwsSwordOfKaldra=" + AiPatches.CaptainPolicy.throwsSwordOfKaldra(s) + " decideThrow -> " + AiPatches.CaptainPolicy.decideThrow(me, s, false));
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
