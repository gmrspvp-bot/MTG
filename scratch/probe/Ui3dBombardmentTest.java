package mtg3d;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * The Goblin Bombardment guard (owner's joined game, 2026-10-21: two shots went at his own
 * robots because Forge asks for the target before the sacrifice). No window: builds a Table3D,
 * fills its snapshot and targeting by hand and calls the helpers the click and banner code use.
 *
 * Run from forge-installer-2.0.15 with ui3d\classes, scratch\probe\out and both Forge jars on
 * the classpath (package mtg3d, so it can reach the package-private fields).
 */
public class Ui3dBombardmentTest {
    static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    static Snapshot.CardV card(int id, String name, String text, boolean creature) {
        Snapshot.CardV c = new Snapshot.CardV();
        c.id = id; c.name = name; c.text = text; c.creature = creature;
        return c;
    }

    public static void main(String[] a) throws Exception {
        // Gui3D.active() needs a controller or a remote; a do-nothing remote will do.
        Field rf = Gui3D.class.getDeclaredField("remote");
        rf.setAccessible(true);
        rf.set(null, Proxy.newProxyInstance(Ui3dBombardmentTest.class.getClassLoader(),
            new Class<?>[]{forge.interfaces.IGameController.class}, (p, m, args) -> null));

        Table3D t = new Table3D(() -> null);
        Snapshot s = new Snapshot();
        Snapshot.SeatV me = new Snapshot.SeatV(); me.name = "2nd Captain"; me.isYou = true;
        Snapshot.SeatV opp = new Snapshot.SeatV(); opp.name = "Captain";
        Snapshot.CardV bomb = card(10, "Goblin Bombardment", "Sacrifice a creature: Goblin Bombardment deals 1 damage to any target.", false);
        Snapshot.CardV robot = card(11, "Robot Token", "", true);
        Snapshot.CardV oppCat = card(20, "Cat Beast Token", "", true);
        me.others.add(bomb); me.creatures.add(robot); opp.creatures.add(oppCat);
        s.seats.add(me); s.seats.add(opp);
        t.snap = s;

        TableBase.Drawn onRobot = new TableBase.Drawn(); onRobot.card = robot; onRobot.seat = me;
        TableBase.Drawn onCat = new TableBase.Drawn(); onCat.card = oppCat; onCat.seat = opp;

        // 1. Bombardment's target step.
        t.lastCastId = bomb.id;
        TableBase.Targeting tg = new TableBase.Targeting();
        tg.wired = true;
        tg.legal.add(robot.id); tg.legal.add(oppCat.id);
        // Forge's own wording (InputSelectTargets.showMessage): host card, " - ", the ability, then
        // the target restriction's selection text.
        tg.what = "Goblin Bombardment (10) - Sacrifice a creature: Goblin Bombardment deals 1 damage to any target.\nSelect any target";
        t.targeting = tg;
        Gui3D.PROMPT.message = tg.what;
        // Without knowing which card was clicked, the prompt alone is enough.
        t.lastCastId = 999;
        check("no source known: prompt alone says harmful", t.harmful());
        check("no source known: prompt alone says sacrifice next", t.sacrificeComesNext());
        check("no source known: own robot still held", t.ownTargetHeld(onRobot));
        t.ownTargetId = -1;
        t.lastCastId = bomb.id;
        check("source is Goblin Bombardment", t.source() == bomb);
        check("banner says the sacrifice comes next", t.sacrificeComesNext());
        check("it hurts what it hits", t.harmful());
        check("not a sacrifice prompt while targeting", !t.sacrificePrompt());
        check("first click on our own robot is held", t.ownTargetHeld(onRobot));
        check("second click on it goes through", !t.ownTargetHeld(onRobot));
        check("an opponent's creature is never held", !t.ownTargetHeld(onCat));

        // 2. A pump spell on our own creature is never held.
        Snapshot.CardV pump = card(30, "Giant Growth", "Target creature gets +3/+3 until end of turn.", false);
        s.hand.add(pump);
        t.lastCastId = pump.id;
        tg.what = "Giant Growth - Select target creature";
        t.ownTargetId = -1;
        check("a pump is not harmful", !t.harmful());
        check("a pump on our robot is not held", !t.ownTargetHeld(onRobot));
        check("a pump has no sacrifice next", !t.sacrificeComesNext());

        // 3. Removal that can only hit our own things (nothing else legal): not held.
        Snapshot.CardV edict = card(31, "Self Bolt", "Self Bolt deals 3 damage to target creature you control.", false);
        s.hand.add(edict);
        t.lastCastId = edict.id;
        tg.legal.clear(); tg.legal.add(robot.id);
        tg.what = "Self Bolt - Select target creature you control";
        t.ownTargetId = -1;
        check("harmful but only our own things legal: not held", !t.ownTargetHeld(onRobot));

        // 4. A stale lastCastId (a trigger asking for targets) is not taken as the source.
        t.lastCastId = bomb.id;
        tg.what = "Soul Warden - Select target player";
        check("a prompt that doesn't name the card: no source", t.source() == null);

        // 5. The sacrifice step after the target.
        t.targeting = null;
        t.lastTargetLabel = "Captain";
        Gui3D.PROMPT.message = "Select a creature to sacrifice (1 left)";
        check("sacrifice prompt recognised", t.sacrificePrompt());
        check("the target is remembered for its banner", "Captain".equals(t.lastTarget()));
        Gui3D.PROMPT.message = "Goblin Bombardment - Select any target";
        check("an ability's text mentioning sacrifice is not the sacrifice step", !t.sacrificePrompt());

        System.out.println(fail == 0 ? "PASS " + pass + "/" + (pass + fail) : "FAIL " + fail + " of " + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
