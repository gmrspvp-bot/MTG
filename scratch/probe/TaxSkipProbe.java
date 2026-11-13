import java.io.File;
import java.util.*;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;

/**
 * Tax triggers (TaxSkipHook, 2026-11-05). Part 1: is Forge's AI "can pay" check right for a HUMAN
 * payer, on every kind of mana source a person might rely on? A wrong "can't" silently takes the
 * choice away. Part 2: Smothering Tithe triggers on a real stack, resolved through the agent hooks.
 *
 * Run from forge-installer-2.0.15 with the evalcache agent, the Forge jar and scripts\tools\classes.
 */
public class TaxSkipProbe {
    static int fails = 0;
    static volatile String answer = TaxSkipHook.SKIP_ALL;
    static volatile int asked = 0;

    static Card put(Game g, Player p, String name) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        c.setGameTimestamp(g.getNextTimestamp());
        g.getAction().moveTo(p.getZone(ZoneType.Battlefield), c, null, null);
        c.setSickness(false);
        return c;
    }
    static void clear(Game g, Player p) {
        for (Card c : new ArrayList<>(p.getCardsIn(ZoneType.Battlefield))) g.getAction().moveTo(ZoneType.Exile, c, null, null);
        p.getManaPool().resetPool();
    }
    static void check(Game g, Player p, Cost cost, SpellAbility sa, String what, boolean want, String... cards) {
        clear(g, p);
        for (String n : cards) put(g, p, n);
        boolean got = TaxSkipHook.canPay(cost, sa, p);
        if (got != want) fails++;
        System.out.println("PROBE " + (got == want ? "ok  " : "FAIL") + " " + what + ": canPay=" + got + " (want " + want + ")");
    }

    static void expect(String what, int got, int want) {
        if (got != want) fails++;
        System.out.println("PROBE " + (got == want ? "ok  " : "FAIL") + " " + what + ": " + got + " (want " + want + ")");
    }
    static void draw(Game g, Player p, int n) {
        Card tithe = null;
        for (Player q : g.getPlayers()) for (Card c : q.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals("Smothering Tithe")) tithe = c;
        Trigger tr = tithe.getTriggers().iterator().next();
        for (int i = 0; i < n; i++) {
            Card drawn = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Island"), p);
            drawn.setGameTimestamp(g.getNextTimestamp());
            g.getAction().moveTo(ZoneType.Hand, drawn, null, null);
            SpellAbility s = tr.ensureAbility().copy();
            s.setActivatingPlayer(tithe.getController());
            forge.game.trigger.WrappedAbility w = new forge.game.trigger.WrappedAbility(tr, s, null);
            w.setActivatingPlayer(tithe.getController());
            w.setTriggeringObject(forge.game.ability.AbilityKey.Card, drawn);
            s.setTriggeringObject(forge.game.ability.AbilityKey.Card, drawn);
            g.getStack().add(w);
        }
        System.out.println("PROBE stack after draw: " + g.getStack().size());
    }
    static void runStack(Game g) {
        int guard = 60;
        while (g.getStack().size() > 0 && guard-- > 0) g.getStack().resolveStack();
        System.out.println("PROBE stack size after run: " + g.getStack().size());
    }
    static int tokens(Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) if (c.isToken()) n++;
        return n;
    }
    static int tappedCount(Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) if (c.isTapped()) n++;
        return n;
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable e) { e.printStackTrace(System.out); System.exit(2); }
    }

    static void run() throws Exception {
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Deck d = DeckSerializer.fromFile(new File("..\\decks\\Derand - Mr House v4.dck"));
            RegisteredPlayer rp = RegisteredPlayer.forCommander(d);
            rp.setPlayer(new forge.player.LobbyPlayerHuman("P" + i));
            players.add(rp);
        }
        Match match = new Match(new GameRules(GameType.Commander), players, "TaxSkipProbe");
        Game game = match.createGame();
        Object stub = java.lang.reflect.Proxy.newProxyInstance(TaxSkipProbe.class.getClassLoader(),
            new Class<?>[] { forge.gui.interfaces.IGuiGame.class }, (x, m, args) -> {
                if (m.getName().equals("getChoices")) {
                    asked++;
                    System.out.println("PROBE asked: " + args[0] + " " + args[3] + " -> " + answer);
                    for (Object o : (List<?>) args[3]) if (String.valueOf(o).startsWith(answer)) return new ArrayList<>(List.of(o));
                    return new ArrayList<>();
                }
                if (m.getName().equals("order")) {
                    List<Object> all = new ArrayList<>();
                    if (args.length == 4) all.addAll((List<?>) args[2]);
                    else { all.addAll((List<?>) args[5]); all.addAll((List<?>) args[4]); }
                    if (forge.gui.interfaces.IGuiGame.OrderResult.class.isAssignableFrom(m.getReturnType()))
                        return new forge.gui.interfaces.IGuiGame.OrderResult<>(all, false);
                    return all;
                }
                Class<?> t = m.getReturnType();
                if (t == boolean.class) return false;
                if (t == int.class) return 0;
                return null;
            });
        for (Player p : game.getPlayers()) ((PlayerControllerHuman) p.getController()).setGui((forge.gui.interfaces.IGuiGame) stub);
        Player me = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, me, 3);

        game.setAge(GameStage.Play);
        Card tithe = put(game, me, "Smothering Tithe");
        Trigger trig = tithe.getTriggers().iterator().next();
        SpellAbility sa = trig.ensureAbility();
        sa.setActivatingPlayer(me);
        Cost two = new Cost("2", true);

        check(game, opp, two, sa, "nothing at all", false);
        check(game, opp, two, sa, "one Island", false, "Island");
        check(game, opp, two, sa, "two Islands", true, "Island", "Island");
        check(game, opp, two, sa, "Sol Ring alone", true, "Sol Ring");
        check(game, opp, two, sa, "Island + Mind Stone", true, "Island", "Mind Stone");
        check(game, opp, two, sa, "Island + Lotus Petal (sacrifice)", true, "Island", "Lotus Petal");
        check(game, opp, two, sa, "Island + Phyrexian Tower creature-sac only", true, "Island", "Phyrexian Tower", "Llanowar Elves");
        check(game, opp, two, sa, "Island + Caves of Koilos (pain)", true, "Island", "Caves of Koilos");
        check(game, opp, two, sa, "Birds + Island", true, "Birds of Paradise", "Island");

        // Real Treasure tokens and floating mana.
        clear(game, opp);
        for (int i = 0; i < 2; i++) {
            Card t = forge.game.card.token.TokenInfo.getProtoType("c_a_treasure_sac", sa, opp);
            t.setOwner(opp); t.setController(opp, game.getNextTimestamp());
            game.getAction().moveTo(opp.getZone(ZoneType.Battlefield), t, null, null);
            t.setSickness(false);
        }
        boolean tr = TaxSkipHook.canPay(two, sa, opp);
        System.out.println("PROBE " + (tr ? "ok  " : "FAIL") + " two Treasure tokens: canPay=" + tr);
        if (!tr) fails++;

        clear(game, opp);
        opp.getManaPool().addMana(new forge.game.mana.Mana((byte) 2, tithe, null, opp));
        opp.getManaPool().addMana(new forge.game.mana.Mana((byte) 2, tithe, null, opp));
        boolean fl = TaxSkipHook.canPay(two, sa, opp);
        System.out.println("PROBE " + (fl ? "ok  " : "FAIL") + " two floating mana: canPay=" + fl);
        if (!fl) fails++;
        opp.getManaPool().resetPool();

        clear(game, opp);
        put(game, opp, "Island").tap(true, null, null); put(game, opp, "Island").tap(true, null, null);
        boolean tapped = TaxSkipHook.canPay(two, sa, opp);
        System.out.println("PROBE " + (!tapped ? "ok  " : "FAIL") + " two tapped Islands: canPay=" + tapped);
        if (tapped) fails++;
        System.out.println("PROBE part 1 done, fails=" + fails);

        PlayerControllerHuman oppH = (PlayerControllerHuman) opp.getController();

        // A: can pay, 7 triggers, says Skip all. One question, seven Treasures, no mana spent.
        clear(game, opp); clear(game, me); tithe = put(game, me, "Smothering Tithe");
        for (int i = 0; i < 4; i++) put(game, opp, "Island");
        draw(game, opp, 7);
        answer = TaxSkipHook.SKIP_ALL; asked = 0;
        runStack(game);
        expect("A asked once", asked, 1);
        expect("A seven Treasures", tokens(me), 7);
        expect("A no land tapped", tappedCount(opp), 0);

        // B: broke, 7 triggers. Never asked, still seven Treasures.
        clear(game, opp); clear(game, me); tithe = put(game, me, "Smothering Tithe");
        draw(game, opp, 7);
        asked = 0;
        runStack(game);
        expect("B never asked", asked, 0);
        expect("B seven Treasures", tokens(me), 7);

        // C: can pay, only 3 triggers. No question, left to Forge's normal prompt.
        clear(game, opp); clear(game, me); tithe = put(game, me, "Smothering Tithe");
        for (int i = 0; i < 4; i++) put(game, opp, "Island");
        draw(game, opp, 3);
        asked = 0;
        SpellAbility top = game.getStack().peekAbility();
        expect("C not declined", TaxSkipHook.declines(oppH, two, top) ? 1 : 0, 0);
        expect("C not asked", asked, 0);

        // D: the stack grows past the limit. "Pay" leaves it to Forge, "Don't pay this one" declines
        // just this one, and the next trigger still offers "Skip all", which then holds without asking.
        draw(game, opp, 4);
        answer = TaxSkipHook.PAY; asked = 0;
        top = game.getStack().peekAbility();
        expect("D pay: not declined", TaxSkipHook.declines(oppH, two, top) ? 1 : 0, 0);
        answer = TaxSkipHook.NOT_THIS;
        expect("D don't pay this one: declined", TaxSkipHook.declines(oppH, two, top) ? 1 : 0, 1);
        answer = TaxSkipHook.SKIP_ALL;
        expect("D skip all, offered again: declined", TaxSkipHook.declines(oppH, two, top) ? 1 : 0, 1);
        expect("D then skipped without asking", TaxSkipHook.declines(oppH, two, top) ? 1 : 0, 1);
        expect("D asked three times", asked, 3);

        // E: the same player goes broke mid-stack, so the rest clear on their own, and a new big
        // stack asks afresh: the answer did not outlive the stack.
        clear(game, opp);
        runStack(game);
        for (int i = 0; i < 4; i++) put(game, opp, "Island");
        draw(game, opp, 7);
        answer = TaxSkipHook.SKIP_ALL; asked = 0;
        runStack(game);
        expect("E asked again on a new stack", asked, 1);

        // F: not a trigger (Mana Leak on your own spell). Never offered, never skipped.
        System.out.println("PROBE skippable trigger=" + top.getRootAbility().isTrigger());

        System.out.println("PROBE all done, fails=" + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
