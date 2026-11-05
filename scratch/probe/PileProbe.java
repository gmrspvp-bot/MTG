import java.io.File;
import java.util.*;
import forge.GuiDesktop;
import forge.ai.AvailableActions;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;

/**
 * What Forge says, and does, about cards in piles (3D table, 2026-11-05). Real permissions on a real
 * board: Dire Fleet Daredevil exiling from an opponent's graveyard, The Reality Chip on the top of
 * your library. Prints the three answers the table can use: Forge's pushed list, the engine's
 * ability list and the click itself.
 *
 * Run from forge-installer-2.0.15 with the Forge jar and this folder on the classpath.
 */
public class PileProbe {
    static Game g; static Player me, opp;

    static Card make(Player p, String name) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        c.setGameTimestamp(g.getNextTimestamp());
        return c;
    }
    static Card put(Player p, String name) {
        Card c = make(p, name);
        g.getAction().moveTo(p.getZone(ZoneType.Battlefield), c, null, null);
        c.setSickness(false);
        return c;
    }
    static void clearMine() {
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Battlefield))) g.getAction().moveTo(ZoneType.Graveyard, c, null, null);
    }
    static void report(String what, Card c) throws Exception {
        g.getAction().checkStaticAbilities();
        Set<CardView> pushed = AvailableActions.collectActionable(me, 5000L);
        boolean inPush = pushed.contains(c.getView());
        int engine = c.getAllPossibleAbilities(me, true).size();
        Object clickable = "?";
        try {
            PlayerControllerHuman pch = (PlayerControllerHuman) me.getController();
            clickable = pch.getGame() != null && g.findByView(c.getView()) == c;
        } catch (Throwable t) { clickable = t.toString(); }
        System.out.println("PROBE " + what + ": zone=" + c.getZone().getZoneType() + "/" + c.getZone().getPlayer()
            + " pushed=" + inPush + " (" + pushed.size() + " in list) engineAbilities=" + engine + " viewFound=" + clickable);
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable e) { e.printStackTrace(System.out); }
        System.exit(0);
    }
    static void run() throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Deck d = DeckSerializer.fromFile(new File("../decks/Derand - Mr House v4.dck"));
            RegisteredPlayer rp = RegisteredPlayer.forCommander(d);
            rp.setPlayer(new forge.player.LobbyPlayerHuman("P" + i));
            players.add(rp);
        }
        g = new Match(new GameRules(GameType.Commander), players, "PileProbe").createGame();
        Object stub = java.lang.reflect.Proxy.newProxyInstance(PileProbe.class.getClassLoader(),
            new Class<?>[] { forge.gui.interfaces.IGuiGame.class }, (x, m, args) -> {
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                return null;
            });
        for (Player p : g.getPlayers()) ((PlayerControllerHuman) p.getController()).setGui((forge.gui.interfaces.IGuiGame) stub);
        me = g.getPlayers().get(0); opp = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, me, 3);
        g.setAge(GameStage.Play);

        // Daredevil: exile an opponent's Lightning Bolt, castable this turn with any mana.
        Card bolt = make(opp, "Lightning Bolt");
        g.getAction().moveTo(ZoneType.Graveyard, bolt, null, null);
        Card dev = put(me, "Dire Fleet Daredevil");
        SpellAbility sa = dev.getTriggers().iterator().next().ensureAbility();
        sa.setActivatingPlayer(me);
        sa.getTargets().add(bolt);
        AbilityUtils.resolve(sa);
        bolt = g.getCardState(bolt);
        report("enemy exile, no mana", bolt);
        put(me, "Mountain");
        report("enemy exile, Mountain", bolt);
        clearMine(); put(me, "Island");
        report("enemy exile, Island (any type)", bolt);

        // A permission that needs nothing unusual: the grant itself on a graveyard card.
        Card fb = make(opp, "Think Twice");
        g.getAction().moveTo(ZoneType.Graveyard, fb, null, null);
        report("enemy graveyard, no permission", fb);

        // The Reality Chip, attached: top of the library.
        clearMine();
        Card top = make(me, "Lightning Bolt");
        g.getAction().moveTo(ZoneType.Library, top, 0, null, null);
        Card chip = put(me, "The Reality Chip");
        report("Chip unattached, Mountain none", top);
        Card host = put(me, "Grizzly Bears");
        SpellAbility rc = chip.getSpellAbilities().get(0); rc.setActivatingPlayer(me); chip.attachToEntity(host, rc);
        put(me, "Mountain");
        report("Chip attached, Mountain", top);
        System.out.println("PROBE top card view visible to me: " + top.getView().canBeShownTo(me.getView())
            + ", library views " + me.getView().getCards(ZoneType.Library).size());

        // The click, as Forge's window makes it.
        PlayerControllerHuman pch = (PlayerControllerHuman) me.getController();
        InputPassPriority in = new InputPassPriority(pch);
        java.lang.reflect.Method on = InputPassPriority.class.getDeclaredMethod("onCardSelected", Card.class, List.class, forge.util.ITriggerEvent.class);
        on.setAccessible(true);
        Object taken = null;
        try { taken = on.invoke(in, top, new ArrayList<Card>(), null); } catch (Throwable t) { taken = t.getCause(); }
        System.out.println("PROBE click on library top: " + taken);
    }
}
