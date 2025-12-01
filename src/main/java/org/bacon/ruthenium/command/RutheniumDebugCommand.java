package org.bacon.ruthenium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.util.List;
import java.util.Map;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.world.TickRegionScheduler;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Provides /ruthenium administrative utilities for debugging the scheduler.
 */
public final class RutheniumDebugCommand {

    private RutheniumDebugCommand() {
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        final LiteralArgumentBuilder<ServerCommandSource> root = literal("ruthenium")
            .requires(src -> src.hasPermissionLevel(2))
            .then(literal("dump").executes(ctx -> executeSchedulerDump(ctx.getSource())))
            .then(literal("threaddump").executes(ctx -> executeThreadDump(ctx.getSource())))
            .then(literal("memorymapdump").executes(ctx -> executeMemoryDump(ctx.getSource())));

        dispatcher.register(root);
    }

    private static int executeSchedulerDump(final ServerCommandSource source) {
        final List<String> lines = TickRegionScheduler.getInstance().buildDebugDump();
        if (lines.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No scheduler information available."), false);
            return 0;
        }
        lines.forEach(line -> {
            source.sendFeedback(() -> Text.literal(line), false);
            Ruthenium.getLogger().info("[ruthenium dump] {}", line);
        });
        return lines.size();
    }

    private static int executeThreadDump(final ServerCommandSource source) {
        final Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        Ruthenium.getLogger().info("[ruthenium thread dump] Captured {} threads", stacks.size());
        stacks.forEach((thread, trace) -> {
            Ruthenium.getLogger().info("Thread '{}' state={} daemon={} priority={}",
                thread.getName(), thread.getState(), thread.isDaemon(), thread.getPriority());
            for (final StackTraceElement element : trace) {
                Ruthenium.getLogger().info("    at {}", element);
            }
        });
        source.sendFeedback(() -> Text.literal("Thread dump written to console."), false);
        return stacks.size();
    }

    private static int executeMemoryDump(final ServerCommandSource source) {
        final Runtime runtime = Runtime.getRuntime();
        final long used = runtime.totalMemory() - runtime.freeMemory();
        Ruthenium.getLogger().info("[ruthenium memory dump] Heap used={} MB, committed={} MB, max={} MB",
            toMiB(used), toMiB(runtime.totalMemory()), toMiB(runtime.maxMemory()));
        final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        Ruthenium.getLogger().info("  Heap: {}", memoryMXBean.getHeapMemoryUsage());
        Ruthenium.getLogger().info("  Non-heap: {}", memoryMXBean.getNonHeapMemoryUsage());
        final List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (final MemoryPoolMXBean pool : pools) {
            Ruthenium.getLogger().info("  Pool {} usage={}", pool.getName(), pool.getUsage());
        }
        final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (final GarbageCollectorMXBean collector : collectors) {
            Ruthenium.getLogger().info("  GC {} collections={} time={}ms", collector.getName(),
                collector.getCollectionCount(), collector.getCollectionTime());
        }
        source.sendFeedback(() -> Text.literal("Memory map dump written to console."), false);
        return pools.size() + collectors.size();
    }

    private static long toMiB(final long bytes) {
        return bytes <= 0L ? 0L : bytes / (1024L * 1024L);
    }
}
