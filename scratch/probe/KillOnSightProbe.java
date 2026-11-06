import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

/**
 * Kill on sight (AiPatches.KillOnSight, 2026-11-06). Other owns Alexios and Me holds him. Me must
 * aim Murder, Swords and a big enough burn spell at him, not a small burn spell; Greaves must go
 * on Me's other creature; and Other, his owner, must do none of it. Run from
 * forge-installer-2.0.15 with -Dforge.ai.killonsight=true.
 */
public class KillOnSightProbe {
    static int fails = 0;
    static void check(String what, boolean ok, Object got) {
        if (!ok) fails++;
        System.out.println("PROBE " + (ok ? "ok  " : "FAIL") + " " + what + ": " + got);
    }
    static Card make(Player owner, String name) {
        return Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), owner);
    }
    static Card put(Game g, Player owner, String name, ZoneType zone) {
        Card c = make(owner, name);
        g.getAction().invoke(() -> g.getAction().moveTo(zone, c, null, null));
        return c;
    }
    static SpellAbility ab(Card c, ApiType api, Player who) {
        for (SpellAbility s : c.getSpellAbilities()) {
            if (s.getApi() == api) { s.setActivatingPlayer(who); s.resetTargets(); return s; }
        }
        throw new IllegalStateException(c + " has no " + api);
    }
    public static void main(String[] a) {
        try { run(); } catch (Throwable e) { e.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "PASS" : "FAIL " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
    static void run() throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (String n : new String[] { "Me", "Other", "Third" })
            players.add(new RegisteredPlayer(new Deck(n)).setPlayer(GamePlayerUtil.createAiPlayer(n)));
        Game game = new Match(new GameRules(GameType.Commander), players, "KillOnSightProbe").createGame();
        game.setAge(GameStage.Play);
        Player me = game.getPlayers().get(0), other = game.getPlayers().get(1);
        Card alexios = put(game, other, "Alexios, Deimos of Kosmos", ZoneType.Battlefield);
        Thread.sleep(400);
        game.getAction().invoke(() -> alexios.addTempController(me, game.getNextTimestamp()));
        Thread.sleep(400);
        Card bear = put(game, me, "Grizzly Bears", ZoneType.Battlefield);
        Thread.sleep(300);
        check("Me holds Alexios, Other owns him", alexios.getController() == me && alexios.getOwner() == other, alexios.getController());

        AiPatches.ThreatDestroyAi destroy = new AiPatches.ThreatDestroyAi();
        SpellAbility murder = ab(put(game, me, "Murder", ZoneType.Hand), ApiType.Destroy, me);
        forge.ai.AiAbilityDecision d = destroy.checkApiLogic(me, murder);
        check("Murder at Alexios on our own field", d.willingToPlay() && murder.getTargets().getTargetCards().contains(alexios), murder.getTargets());

        SpellAbility mine = ab(put(game, other, "Murder", ZoneType.Hand), ApiType.Destroy, other);
        check("his owner does not", AiPatches.KillOnSight.aim(other, mine, c -> true) == null, mine.getTargets());

        SpellAbility swords = ab(put(game, me, "Swords to Plowshares", ZoneType.Hand), ApiType.ChangeZone, me);
        d = new AiPatches.ThreatChangeZoneAi().checkApiLogic(me, swords);
        check("Swords at Alexios", d.willingToPlay() && swords.getTargets().getTargetCards().contains(alexios), swords.getTargets());

        SpellAbility bolt = ab(put(game, me, "Lightning Bolt", ZoneType.Hand), ApiType.DealDamage, me);
        check("3 damage does not kill a 4/4: no shot", AiPatches.ThrowAwareDamageAi.killShot(me, bolt) == null, bolt.getTargets());
        SpellAbility slash = ab(put(game, me, "Flame Slash", ZoneType.Hand), ApiType.DealDamage, me);
        d = AiPatches.ThrowAwareDamageAi.killShot(me, slash);
        check("4 damage kills him", d != null && slash.getTargets().getTargetCards().contains(alexios), slash.getTargets());

        Card greaves = put(game, me, "Lightning Greaves", ZoneType.Battlefield);
        Thread.sleep(300);
        SpellAbility equip = null;
        for (SpellAbility s : greaves.getSpellAbilities()) if (s.isEquip()) equip = s;
        equip.setActivatingPlayer(me);
        equip.resetTargets();
        equip.getTargets().add(alexios);
        AiPatches.KillOnSight.noGear(me, equip, new forge.ai.AiAbilityDecision(100, forge.ai.AiPlayDecision.WillPlay));
        check("Greaves go on the Bears instead", equip.getTargets().getTargetCards().contains(bear), equip.getTargets());
    }
}
