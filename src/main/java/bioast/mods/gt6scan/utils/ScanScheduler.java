package bioast.mods.gt6scan.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import bioast.mods.gt6scan.ScannerMod;

/**
 * Cooperative scheduler, modelled after GT5u's {@code gregtech.api.task.CooperativeScheduler} that the detrav
 * prospector uses: expensive work runs spread over server ticks on the main thread (no multithreading, so world
 * access stays thread safe) and every job gets a time budget per tick. A big scan therefore only costs a few
 * milliseconds per tick instead of stalling the server for hundreds of milliseconds at once.
 */
public final class ScanScheduler {
    public static final ScanScheduler INSTANCE = new ScanScheduler();

    /** Time budget per server tick. 5ms is ~10% of a 50ms tick. */
    private static final long BUDGET_NANOS = 5_000_000L;

    /** A piece of work. Must return true once it is completely done and must not do anything after that. */
    public interface Job {
        boolean run(long deadlineNanos);
    }

    private final List<Job> jobs = new ArrayList<>();

    private ScanScheduler() {}

    public static void submit(Job job) {
        INSTANCE.jobs.add(job);
    }

    public static boolean cancel(Job job) {
        return INSTANCE.jobs.remove(job);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (jobs.isEmpty()) return;
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Iterator<Job> it = jobs.iterator();
        while (it.hasNext()) {
            try {
                if (it.next()
                    .run(deadline)) it.remove();
            } catch (Exception e) {
                it.remove();
                ScannerMod.debug.error("Failed to run scan job", e);
            }
            if (System.nanoTime() >= deadline) break;
        }
    }

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }
}
