// Proliferate's "Mine" button left out the player's own counters (2026-11-01).
//
// Forge 2.0.15 turns the Cancel button of a proliferate prompt into a mass-select button that
// cycles Mine -> All -> Clear all. "Mine" only ever picked the permanents you control: it tests
// each choice for being a Card, so YOU - a player with experience, poison, energy or rad
// counters - were never in it. In the owner's game of 2026-11-01 (console_2026-11-01_005117.log)
// KDAWG proliferated with Patrolling Peacemaker and his experience counters went nowhere.
//
// EvalCacheAgent routes InputSelectEntitiesFromList.onCancel here (original renamed
// onCancel$orig). Forge's own handling runs first; then, when the mode it switched to is Mine,
// the player answering is added too if they are one of the choices. Nothing changes for any
// other prompt, since only proliferate has a mass-select mode. -Dforge.mine.self=false turns it
// off. Test: scratch\probe\MassSelectProbe.java.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;

import forge.game.GameEntity;
import forge.game.player.Player;
import forge.gamemodes.match.input.InputSelectEntitiesFromList;
import forge.gamemodes.match.input.MassSelectMode;
import forge.util.collect.FCollectionView;

public final class MassSelectHook {
    private MassSelectHook() {}

    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("forge.mine.self", "true"));
    private static volatile MethodHandle orig, mode, choices, maxOf, changed, refresh;

    public static void onCancel(InputSelectEntitiesFromList<?> self) throws Throwable {
        if (orig == null) {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(InputSelectEntitiesFromList.class, MethodHandles.lookup());
            Class<?> c = InputSelectEntitiesFromList.class;
            Class<?> base = c.getSuperclass();
            MethodHandles.Lookup lb = MethodHandles.privateLookupIn(base, MethodHandles.lookup());
            mode = l.findGetter(c, "massSelectMode", MassSelectMode.class);
            choices = l.findGetter(c, "validChoices", FCollectionView.class);
            maxOf = lb.findGetter(base, "max", int.class);
            changed = lb.findVirtual(base, "onSelectStateChanged", MethodType.methodType(void.class, GameEntity.class, boolean.class));
            refresh = lb.findVirtual(base, "refresh", MethodType.methodType(void.class));
            orig = l.findVirtual(c, "onCancel$orig", MethodType.methodType(void.class));
        }
        orig.invoke(self);
        if (!ENABLED) return;
        try {
            if ((MassSelectMode) mode.invoke(self) != MassSelectMode.MINE) return;
            Player me = self.getController() == null ? null : self.getController().getPlayer();
            FCollectionView<?> valid = (FCollectionView<?>) choices.invoke(self);
            @SuppressWarnings("unchecked")
            Collection<GameEntity> selected = (Collection<GameEntity>) self.getSelected();
            if (me == null || valid == null || !valid.contains(me) || selected.contains(me)) return;
            if (selected.size() >= (int) maxOf.invoke(self)) return;
            selected.add(me);
            changed.invoke(self, (GameEntity) me, true);
            refresh.invoke(self);
            System.out.println("MASSSELECT " + me.getName() + ": Mine now includes the player's own counters");
        } catch (Throwable t) {
            System.out.println("MASSSELECT: could not add the player (" + t + ")");
        }
    }
}
