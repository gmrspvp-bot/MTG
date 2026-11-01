package mtg3d;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;

/**
 * A memory line in the log, once a minute.
 *
 * "it was using like 9gb on the host and lots on my mates pc" (2026-11-01). Nobody could say
 * more than that, because nothing ever wrote a number down - and by the time a machine is in
 * trouble the person at it is trying to finish a game, not read Task Manager.
 *
 * The three numbers that matter are deliberately together on one line:
 *
 *   heap     what -Xmx caps, and the only part most people think about
 *   direct   off-heap buffers, which is where this one actually went. Netty's ceiling for
 *            these defaults to the heap size, so a 4 GB -Xmx quietly authorised a second 4 GB
 *            outside it, and this protocol pushes whole serialised game views - 64 KB on
 *            average, 93 KB at worst in a measured game - to every client several times a turn.
 *   mapped   memory-mapped files, so a jar or a card image cache shows up rather than hiding
 *
 * Once a minute is enough to see a shape over a game and cheap enough to ignore: three MXBean
 * reads on a daemon thread. It never allocates in a loop and it never touches the game.
 */
final class Mem {

    private Mem() {}

    private static volatile boolean started;
    private static final long EVERY_MS =
        Long.getLong("forge.ui3d.memevery", 60000L);

    /** Idempotent: Bridge.openWindow is called on every setGameView, so this is called a lot. */
    static synchronized void start() {
        if (started || EVERY_MS <= 0) return;
        started = true;
        Thread t = new Thread(Mem::loop, "ui3d-mem");
        t.setDaemon(true);                 // must never hold the JVM open at the end of a game
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void loop() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        while (true) {
            try {
                Thread.sleep(EVERY_MS);
                System.out.println(line(mem));
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                return;                    // a log line is never worth a thread that keeps failing
            }
        }
    }

    static String line(MemoryMXBean mem) {
        long heapUsed = mem.getHeapMemoryUsage().getUsed();
        long heapMax = mem.getHeapMemoryUsage().getMax();
        long nonHeap = mem.getNonHeapMemoryUsage().getUsed();
        long direct = 0, directCap = 0, mapped = 0;
        try {
            List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            for (BufferPoolMXBean p : pools) {
                if ("direct".equals(p.getName())) { direct = p.getMemoryUsed(); directCap = p.getTotalCapacity(); }
                else if ("mapped".equals(p.getName())) mapped = p.getMemoryUsed();
            }
        } catch (Throwable ignored) {
            // older or stranger JVMs: the heap numbers alone are still worth printing
        }
        return String.format("UI3D: MEM heap %s/%s   direct %s (reserved %s)   mapped %s   "
                + "non-heap %s   threads %d",
            gb(heapUsed), gb(heapMax), gb(direct), gb(directCap), gb(mapped), gb(nonHeap),
            Thread.activeCount());
    }

    private static String gb(long b) {
        if (b <= 0) return "0";
        return b >= 1L << 30 ? String.format("%.2fGB", b / (double) (1L << 30))
                             : String.format("%dMB", b >> 20);
    }
}
