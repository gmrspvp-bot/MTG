// Forge's network sync racing the game (owner's networked games, 2026-10-22..24).
//
// forge.trackable.Tracker keeps the property changes made while it is frozen in a plain
// ArrayList, delayedPropChanges. The game thread adds to it (addDelayedPropChange) while the
// host's network sync reads it from another thread (getDelayedPropsFor, from
// DeltaSyncManager's walk), and a plain ArrayList throws ConcurrentModificationException when
// that happens: "Walk attempt n of 3 failed", the walk "shipping what was collected" (stale or
// nameless cards on the joined players' screens), and one crash window on the host. It was the
// most frequent error in the crash-log review of 2026-10-25.
//
// EvalCacheAgent routes addDelayedPropChange here (original renamed addDelayedPropChange$orig).
// The first time a Tracker records a change, its list is replaced by a CopyOnWriteArrayList
// holding the same entries: every read then walks a snapshot and can never see the list change
// under it. The swap happens on the game thread, the only one that writes the list, so it does
// not race either. Forge's own flush / clearDelayed / getDelayedPropsFor work on whatever list
// the field holds, unchanged. The list is small (changes made during one freeze), so copying
// on each add costs nothing noticeable. Logged once: "TRACKERSYNC ...".

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;

public final class TrackerSyncHook {
    private TrackerSyncHook() {}

    private static volatile MethodHandle orig;
    private static volatile Field listField;
    private static volatile boolean reported, broken;

    public static void addDelayedPropChange(Tracker self, TrackableObject object, TrackableProperty prop, Object value) throws Throwable {
        MethodHandle h = orig;
        if (h == null) {
            h = MethodHandles.privateLookupIn(Tracker.class, MethodHandles.lookup()).findVirtual(Tracker.class,
                "addDelayedPropChange$orig", MethodType.methodType(void.class, TrackableObject.class, TrackableProperty.class, Object.class));
            orig = h;
        }
        if (!broken) {
            try {
                makeSafe(self);
            } catch (Throwable t) {
                broken = true;      // never stop the game over this; Forge carries on as it was
                System.out.println("TRACKERSYNC: could not make the change list thread-safe (" + t + ")");
            }
        }
        h.invokeExact(self, object, prop, value);
    }

    /** Swap the Tracker's change list for a copy-on-write one, once. */
    @SuppressWarnings("unchecked")
    static void makeSafe(Tracker t) throws ReflectiveOperationException {
        Field f = listField;
        if (f == null) {
            f = Tracker.class.getDeclaredField("delayedPropChanges");
            f.setAccessible(true);
            listField = f;
        }
        Object list = f.get(t);
        if (list instanceof CopyOnWriteArrayList) return;
        f.set(t, new CopyOnWriteArrayList<>((List<Object>) list));
        if (!reported) {
            reported = true;
            System.out.println("TRACKERSYNC: Forge's delayed-change list is now safe to read while the game writes it");
        }
    }
}
