// The panels hooked up (2026-10-21): does every new taken-over question get answered - by the 3D
// window when it answers, and by Forge when it does not?
//
// A stand-in for Forge's window records that it was asked and answers with "FORGE" values, so a
// result can be traced to whoever produced it. A second thread plays the 3D window, answering the
// way the panels do (Gui3D.answerChoice / answerNumber / answerOrder).
//
//   javac -cp "<desktop jar>;ui3d\classes" -d scratch\probe\classes scratch\probe\Ui3dTakeoverTest.java
//   java -Dforge.ui3d.handoff.ms=700 -cp "<desktop jar>;ui3d\classes;scratch\probe\classes" Ui3dTakeoverTest

import forge.gui.interfaces.IGuiGame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Ui3dTakeoverTest {

    static boolean pass = true;
    static final AtomicBoolean forgeAsked = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        // Building a proxy of IGuiGame loads every type it mentions, PhaseType among them, which
        // needs Forge's localiser - so Forge is initialised first (run from forge-installer-2.0.15).
        forge.gui.GuiBase.setInterface(new forge.GuiDesktop());
        forge.model.FModel.initialize(null, null);
        IGuiGame real = (IGuiGame) Proxy.newProxyInstance(
            Ui3dTakeoverTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class },
            (p, m, a) -> {
                switch (m.getName()) {
                    case "one": case "oneOrNone": forgeAsked.set(true); return "FORGE";
                    case "getChoices": case "many": forgeAsked.set(true); return Arrays.asList("FORGE");
                    case "getInteger": forgeAsked.set(true); return -1;
                    case "order":
                        forgeAsked.set(true);
                        if (IGuiGame.OrderResult.class.isAssignableFrom(m.getReturnType()))
                            return new IGuiGame.OrderResult<>(Arrays.asList("FORGE"), false);
                        return Arrays.asList("FORGE");
                    case "assignCombatDamage": forgeAsked.set(true); return new java.util.HashMap<>();
                    case "showErrorDialog": case "alertUser": case "flashIncorrectAction": case "showRevealedCards":
                        forgeAsked.set(true); return null;
                    case "showInputDialog": forgeAsked.set(true); return "FORGE";
                    case "manipulateCardList": case "insertInList": forgeAsked.set(true); return Arrays.asList();
                    case "assignGenericAmount": forgeAsked.set(true); return new java.util.HashMap<>();
                    default: return defaultValue(m.getReturnType());
                }
            });
        IGuiGame g = wrap(real);

        Thread beat = new Thread(() -> { while (true) { mtg3d.Handoff.alive(); sleep(40); } });
        beat.setDaemon(true);
        beat.start();
        sleep(120);
        List<String> abc = Arrays.asList("A", "B", "C");

        // one(): the 3D window picks the second option; labels from the display function.
        forgeAsked.set(false);
        answerChoiceLater(Arrays.asList(1), c -> check("one: text options go to OptionPanel",
            !c.cards && c.labels.equals(Arrays.asList("<A>", "<B>", "<C>")), "cards=" + c.cards + " labels=" + c.labels));
        Object r = g.one("Pick", abc, (forge.util.FSerializableFunction<String, String>) s -> "<" + s + ">");
        check("one: answered in 3D", "B".equals(r) && !forgeAsked.get(), "result=" + r + " forgeAsked=" + forgeAsked.get());

        // oneOrNone(): the 3D window picks nothing -> null, Forge not asked.
        forgeAsked.set(false);
        answerChoiceLater(Arrays.asList(), null);
        r = g.oneOrNone("Maybe", abc);
        check("oneOrNone: none picked", r == null && !forgeAsked.get(), "result=" + r + " forgeAsked=" + forgeAsked.get());

        // one() cancelled: nothing picked for a must-choose -> Forge asks.
        forgeAsked.set(false);
        answerChoiceLater(Arrays.asList(), null);
        r = g.one("Must pick", abc);
        check("one: cancel hands to Forge", "FORGE".equals(r) && forgeAsked.get(), "result=" + r);

        // getChoices(title, 2, 2, list): two picked.
        forgeAsked.set(false);
        answerChoiceLater(Arrays.asList(0, 2), c -> check("getChoices: min/max read", c.min == 2 && c.max == 2, c.min + ".." + c.max));
        r = g.getChoices("Two of these", 2, 2, abc);
        check("getChoices: answered in 3D", Arrays.asList("A", "C").equals(r) && !forgeAsked.get(), "result=" + r);

        // many(title, caption, -1, list, card): "any number" (how scry asks for the bottom cards).
        forgeAsked.set(false);
        answerChoiceLater(Arrays.asList(), c -> check("many: negative count = any", c.min == 0 && c.max == 3, c.min + ".." + c.max));
        r = g.many("Bottom", "Cards to put on the bottom", -1, abc, null);
        check("many: none picked is allowed", r instanceof List && ((List<?>) r).isEmpty() && !forgeAsked.get(), "result=" + r);

        // getInteger: answered 4; cancelled -> null; unanswered -> Forge after the timeout.
        forgeAsked.set(false);
        answerNumberLater(4);
        r = g.getInteger("X", 0, 9, false);
        check("getInteger: answered", Integer.valueOf(4).equals(r) && !forgeAsked.get(), "result=" + r);
        forgeAsked.set(false);
        answerNumberLater(null);
        r = g.getInteger("X", 0, 9, false);
        check("getInteger: cancel = null", r == null && !forgeAsked.get(), "result=" + r);
        forgeAsked.set(false);
        r = g.getInteger("Nobody answers", 0, 9, false);
        check("getInteger: timeout -> Forge", Integer.valueOf(-1).equals(r) && forgeAsked.get(), "result=" + r);

        // order(title, caption, list, card): reversed in 3D.
        forgeAsked.set(false);
        answerOrderLater(Arrays.asList(2, 1, 0));
        r = g.order("Order", "Top", abc, null);
        check("order: answered in 3D", Arrays.asList("C", "B", "A").equals(r) && !forgeAsked.get(), "result=" + r);
        // the long form moves between two lists (sideboarding): left to Forge, at once.
        forgeAsked.set(false);
        r = g.order("Sideboard", "Deck", 0, 3, abc, Arrays.asList("D"), null, true, false);
        check("order: two-list form stays with Forge", forgeAsked.get(), "forgeAsked=" + forgeAsked.get());

        // assignCombatDamage with no blockers to split between: nothing to ask, Forge does it.
        forgeAsked.set(false);
        long t0 = System.currentTimeMillis();
        g.assignCombatDamage(null, Arrays.asList(), 5, new forge.game.player.PlayerView(1, null), false, false);
        check("damage with nothing to split -> Forge", forgeAsked.get() && System.currentTimeMillis() - t0 < 300,
            "forgeAsked=" + forgeAsked.get());

        // getAbilityToPlay (2026-10-20): a card with two things it can do. Real abilities from a
        // Weathered Wayfarer - casting it, and its {W},{T} search - marked playable by hand.
        List<forge.game.player.RegisteredPlayer> rp = new java.util.ArrayList<>();
        rp.add(new forge.game.player.RegisteredPlayer(new forge.deck.Deck("Me")).setPlayer(forge.player.GamePlayerUtil.createAiPlayer("Me")));
        rp.add(new forge.game.player.RegisteredPlayer(new forge.deck.Deck("Op")).setPlayer(forge.player.GamePlayerUtil.createAiPlayer("Op")));
        forge.game.Game game = new forge.game.Match(new forge.game.GameRules(forge.game.GameType.Commander), rp, "abilities").createGame();
        forge.game.card.Card way = forge.game.card.Card.fromPaperCard(
            forge.model.FModel.getMagicDb().getCommonCards().getCard("Weathered Wayfarer"), game.getPlayers().get(0));
        List<forge.game.spellability.SpellAbilityView> sas = new java.util.ArrayList<>();
        for (forge.game.spellability.SpellAbility sa : way.getSpellAbilities()) {
            forge.game.spellability.SpellAbilityView v = forge.game.spellability.SpellAbilityView.get(sa);
            v.set(forge.trackable.TrackableProperty.CanPlay, true);
            sas.add(v);
        }
        check("ability: Wayfarer has two", sas.size() == 2, String.valueOf(sas.size()));
        // A click on the card carries a trigger event; Forge putting a trigger on the stack does not.
        forge.util.ITriggerEvent click = (forge.util.ITriggerEvent) Proxy.newProxyInstance(
            Ui3dTakeoverTest.class.getClassLoader(), new Class<?>[] { forge.util.ITriggerEvent.class },
            (p, m, a2) -> defaultValue(m.getReturnType()));
        forgeAsked.set(false);
        answerChoiceLater(Arrays.asList(1), c -> check("ability: panel lists both, in Forge's words",
            !c.cards && c.labels.size() == 2 && c.labels.get(0).equals("Cast Weathered Wayfarer")
            && c.labels.get(1).contains("Search your library"), "labels=" + c.labels));
        r = g.getAbilityToPlay(way.getView(), sas, click);
        check("ability: picked one comes back", r == sas.get(1) && !forgeAsked.get(), "result=" + r);
        answerChoiceLater(Arrays.asList(), null);
        r = g.getAbilityToPlay(way.getView(), sas, click);
        check("ability: ESC = nothing played", r == null && !forgeAsked.get(), "result=" + r);
        sas.get(0).set(forge.trackable.TrackableProperty.CanPlay, false);
        answerChoiceLater(Arrays.asList(0), c -> check("ability: unplayable one not offered",
            c.labels.size() == 1 && c.labels.get(0).contains("Search"), "labels=" + c.labels));
        r = g.getAbilityToPlay(way.getView(), sas, click);
        check("ability: the playable one comes back", r == sas.get(1), "result=" + r);
        r = g.getAbilityToPlay(way.getView(), sas.subList(1, 2), click);
        check("ability: a single one plays without asking", r == sas.get(1) && mtg3d.Gui3D.pendingChoice() == null, "result=" + r);
        // The bug of 2026-10-20 23:31: EVERY human trigger arrives as one option, no click, and
        // not "playable" - it must come straight back, or the trigger is lost.
        sas.get(1).set(forge.trackable.TrackableProperty.CanPlay, false);
        r = g.getAbilityToPlay(way.getView(), sas.subList(1, 2), null);
        check("ability: a trigger (no click) is never dropped", r == sas.get(1) && mtg3d.Gui3D.pendingChoice() == null, "result=" + r);

        // ---- 2026-10-21 (Stoneforge): trample, and Forge's own windows -------------------------
        // Trample: a 6-power attacker blocked by a 2/2. Lethal 2 to the blocker, 4 to the player,
        // which Forge's own dialog returns under the NULL key (VAssignCombatDamage.getDamageMap).
        forge.game.card.Card attacker = forge.game.card.Card.fromPaperCard(
            forge.model.FModel.getMagicDb().getCommonCards().getCard("Colossal Dreadmaw"), game.getPlayers().get(0));
        forge.game.card.Card bear = forge.game.card.Card.fromPaperCard(
            forge.model.FModel.getMagicDb().getCommonCards().getCard("Grizzly Bears"), game.getPlayers().get(1));
        forgeAsked.set(false);
        final boolean[] sawTrample = { false };
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingDamage() == null; i++) sleep(5);
            mtg3d.Gui3D.DamageAsk d = mtg3d.Gui3D.pendingDamage();
            if (d != null) { sawTrample[0] = d.trample; mtg3d.Gui3D.answerDamage(new int[] { 2 }, 4); }
        }).start();
        Object m = g.assignCombatDamage(attacker.getView(), Arrays.asList(bear.getView()), 6,
            game.getPlayers().get(1).getView(), false, false);
        check("trample: panel has the player column", sawTrample[0], "");
        check("trample: answered in 3D, player share under null",
            m instanceof java.util.Map && !forgeAsked.get()
            && Integer.valueOf(4).equals(((java.util.Map<?, ?>) m).get(null))
            && Integer.valueOf(2).equals(((java.util.Map<?, ?>) m).get(bear.getView())), "result=" + m);
        // A split that breaks trample's rule (player before the blocker has lethal) goes to Forge.
        forgeAsked.set(false);
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingDamage() == null; i++) sleep(5);
            mtg3d.Gui3D.answerDamage(new int[] { 1 }, 5);
        }).start();
        g.assignCombatDamage(attacker.getView(), Arrays.asList(bear.getView()), 6,
            game.getPlayers().get(1).getView(), false, false);
        check("trample: illegal split -> Forge's dialog", forgeAsked.get(), "forgeAsked=" + forgeAsked.get());

        // Forge's error box: shown in the 3D window (dismissed there), not Forge's.
        forgeAsked.set(false);
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingReveal() == null; i++) sleep(5);
            mtg3d.Gui3D.dismissReveal();
        }).start();
        g.showErrorDialog("Something went wrong", "Error");
        check("error box shown in 3D", !forgeAsked.get(), "forgeAsked=" + forgeAsked.get());
        // The reminder flashes are swallowed.
        forgeAsked.set(false);
        g.alertUser();
        g.flashIncorrectAction();
        check("alert / flash do not reach Forge", !forgeAsked.get(), "forgeAsked=" + forgeAsked.get());
        // Revealed cards: mirrored, Forge's zone window stays shut; hide closes the mirror.
        forgeAsked.set(false);
        g.showRevealedCards(Arrays.asList(bear.getView()));
        mtg3d.Gui3D.Peek pk = mtg3d.Gui3D.pendingPeek();
        check("revealed cards mirrored, not Forge's", pk != null && "Revealed".equals(pk.title) && !forgeAsked.get(),
            pk == null ? "no peek" : pk.title + " " + pk.items.size());
        g.hideRevealedCards();
        check("hideRevealedCards closes it", mtg3d.Gui3D.pendingPeek() == null, "");

        // ---- the last decisions (Xenil's Claude's three, untested until now, and two more) ------
        forge.game.card.Card c3 = forge.game.card.Card.fromPaperCard(
            forge.model.FModel.getMagicDb().getCommonCards().getCard("Hill Giant"), game.getPlayers().get(0));
        forge.game.card.CardView A = attacker.getView(), B = bear.getView(), C = c3.getView();
        // Free text: typed in 3D; Cancel = null, as Forge's box.
        forgeAsked.set(false);
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingText() == null; i++) sleep(5);
            mtg3d.Gui3D.answerText("Kenrith");
        }).start();
        r = g.showInputDialog("Name a card", "Name", null, "", null, false);
        check("free text typed in 3D", "Kenrith".equals(r) && !forgeAsked.get(), "result=" + r);
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingText() == null; i++) sleep(5);
            mtg3d.Gui3D.answerText(null);
        }).start();
        r = g.showInputDialog("Name a card", "Name", null, "", null, false);
        check("free text cancelled = null", r == null && !forgeAsked.get(), "result=" + r);
        // manipulateCardList: A and B movable; B to the top, A to the bottom; C keeps its place.
        forgeAsked.set(false);
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingOrder() == null; i++) sleep(5);
            mtg3d.Gui3D.OrderAsk o = mtg3d.Gui3D.pendingOrder();
            if (o != null && o.rowNames != null && o.rowNames.length == 2) mtg3d.Gui3D.answerOrderRows(Arrays.asList(1), Arrays.asList(0));
            else mtg3d.Gui3D.answerOrder(null);
        }).start();
        r = g.manipulateCardList("Arrange", Arrays.asList(A, B, C), Arrays.asList(A, B), true, true, false);
        check("manipulateCardList: top / rest / bottom", Arrays.asList(B, C, A).equals(r) && !forgeAsked.get(), "result=" + r);
        // order, two-list form, everything to be ordered (simultaneous triggers): reversed.
        forgeAsked.set(false);
        answerOrderLater(Arrays.asList(2, 1, 0));
        r = g.order("Select order for simultaneous abilities", "Resolve first", 0, 0, abc, new java.util.ArrayList<String>(), null, false, false);
        Object list = r instanceof IGuiGame.OrderResult ? ((IGuiGame.OrderResult<?>) r).ordered() : r;
        check("order, two lists: answered in 3D", Arrays.asList("C", "B", "A").equals(list) && !forgeAsked.get(), "result=" + list);
        // insertInList: the item joins at the front; moved to the end.
        forgeAsked.set(false);
        answerOrderLater(Arrays.asList(1, 2, 0));
        r = g.insertInList("Where?", "X", Arrays.asList("A", "B"));
        check("insertInList: answered in 3D", Arrays.asList("A", "B", "X").equals(r) && !forgeAsked.get(), "result=" + r);
        // assignGenericAmount: 3 split 2 / 1 over two cards.
        forgeAsked.set(false);
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingDamage() == null; i++) sleep(5);
            mtg3d.Gui3D.answerDamage(new int[] { 2, 1 });
        }).start();
        java.util.Map<Object, Integer> targets = new java.util.LinkedHashMap<>();
        targets.put(B, 3);
        targets.put(C, 3);
        r = g.assignGenericAmount(A, targets, 3, false, "counters");
        check("assignGenericAmount: split in 3D", r instanceof java.util.Map && Integer.valueOf(2).equals(((java.util.Map<?, ?>) r).get(B))
            && Integer.valueOf(1).equals(((java.util.Map<?, ?>) r).get(C)) && !forgeAsked.get(), "result=" + r);

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    interface ChoiceCheck { void on(mtg3d.Gui3D.Choice c); }

    static void answerChoiceLater(List<Integer> picks, ChoiceCheck checkFirst) {
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingChoice() == null; i++) sleep(5);
            mtg3d.Gui3D.Choice c = mtg3d.Gui3D.pendingChoice();
            if (c != null && checkFirst != null) checkFirst.on(c);
            mtg3d.Gui3D.answerChoice(picks);
        }).start();
    }

    static void answerNumberLater(Integer v) {
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingNumber() == null; i++) sleep(5);
            mtg3d.Gui3D.answerNumber(v);
        }).start();
    }

    static void answerOrderLater(List<Integer> idx) {
        new Thread(() -> {
            for (int i = 0; i < 300 && mtg3d.Gui3D.pendingOrder() == null; i++) sleep(5);
            mtg3d.Gui3D.answerOrder(idx);
        }).start();
    }

    static IGuiGame wrap(IGuiGame real) throws Exception {
        java.lang.reflect.Constructor<?> c = Class.forName("mtg3d.Gui3D")
            .getDeclaredConstructor(IGuiGame.class, forge.player.PlayerControllerHuman.class);
        c.setAccessible(true);
        InvocationHandler h = (InvocationHandler) c.newInstance(real, null);
        return (IGuiGame) Proxy.newProxyInstance(
            Ui3dTakeoverTest.class.getClassLoader(), new Class<?>[] { IGuiGame.class }, h);
    }

    static synchronized void check(String name, boolean ok, String detail) {
        if (!ok) pass = false;
        System.out.printf("  %-42s %s   (%s)%n", name, ok ? "ok" : "FAILED", detail);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        return null;
    }
}
