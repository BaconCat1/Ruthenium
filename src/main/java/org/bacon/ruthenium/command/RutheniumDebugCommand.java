package org.bacon.ruthenium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.util.List;
import java.util.Map;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.config.RutheniumConfig;
import org.bacon.ruthenium.config.RutheniumConfigManager;
import org.bacon.ruthenium.world.MainThreadTickGuard;
import org.bacon.ruthenium.world.RegionTickMonitor;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.bacon.ruthenium.world.network.PlayerRegionTransferHandler;
import org.bacon.ruthenium.world.network.RegionNetworkManager;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Provides /ruthenium administrative utilities for debugging the scheduler.
 */
public final class RutheniumDebugCommand {

    private RutheniumDebugCommand() {
    }

    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        final LiteralArgumentBuilder<ServerCommandSource> root = literal("ruthenium")
            .requires(CommandManager.requirePermissionLevel(new PermissionCheck.Require(DefaultPermissions.GAMEMASTERS)))
            .then(literal("dump").executes(ctx -> executeSchedulerDump(ctx.getSource())))
            .then(literal("threaddump").executes(ctx -> executeThreadDump(ctx.getSource())))
            .then(literal("memorymapdump").executes(ctx -> executeMemoryDump(ctx.getSource())))
            .then(literal("tickreport").executes(ctx -> executeTickReport(ctx.getSource())))
            .then(literal("failuredump").executes(ctx -> executeFailureDump(ctx.getSource())))
            .then(literal("tickguarddump").executes(ctx -> executeTickGuardDump(ctx.getSource())))
            .then(literal("config")
                .then(literal("path").executes(ctx -> executeConfigPath(ctx.getSource())))
                .then(literal("reload").executes(ctx -> executeConfigReload(ctx.getSource())))
                .then(literal("dump").executes(ctx -> executeConfigDump(ctx.getSource()))));

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

    private static int executeTickReport(final ServerCommandSource source) {
        final List<String> report = RegionTickMonitor.getInstance().buildReport();
        report.forEach(line -> Ruthenium.getLogger().info("[ruthenium ticks] {}", line));
        if (report.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No region tick data recorded."), false);
        } else {
            source.sendFeedback(() -> Text.literal("Region tick report written to console."), false);
        }
        return report.size();
    }

    private static int executeFailureDump(final ServerCommandSource source) {
        final String report = TickRegionScheduler.getInstance().getFailureHandler().buildDiagnosticReport();
        for (final String line : report.split("\n")) {
            Ruthenium.getLogger().info("[ruthenium failure] {}", line);
        }
        final boolean gracefulDegradation = TickRegionScheduler.getInstance().isGracefulDegradationActive();
        source.sendFeedback(() -> Text.literal("Failure handler diagnostics written to console."), false);
        source.sendFeedback(() -> Text.literal("Graceful Degradation Active: " + gracefulDegradation), false);
        return 1;
    }

    private static int executeTickGuardDump(final ServerCommandSource source) {
        final String report = MainThreadTickGuard.buildMetricsReport();
        for (final String line : report.split("\n")) {
            Ruthenium.getLogger().info("[ruthenium tickguard] {}", line);
        }
        final MainThreadTickGuard.TickMetrics metrics = MainThreadTickGuard.getMetrics();
        source.sendFeedback(() -> Text.literal("Tick guard metrics written to console."), false);
        source.sendFeedback(() -> Text.literal("Total Violations: " + metrics.totalViolations() +
            " (should be 0 in production)"), false);
        if (metrics.hasViolations()) {
            source.sendFeedback(() -> Text.literal("WARNING: Vanilla tick paths detected while regions active!"), false);
        }
        return 1;
    }

    private static int executeNetworkDump(final ServerCommandSource source) {
        final net.minecraft.server.MinecraftServer server = source.getServer();
        source.sendFeedback(() -> Text.literal("=== Per-Region Network Metrics ==="), false);

        int totalPendingTransfers = org.bacon.ruthenium.world.network.PlayerRegionTransferHandler.getPendingTransferCount();
        source.sendFeedback(() -> Text.literal("Pending Player Transfers: " + totalPendingTransfers), false);

        for (final net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
            if (world instanceof org.bacon.ruthenium.world.RegionizedServerWorld regionized) {
                final org.bacon.ruthenium.world.RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
                final org.bacon.ruthenium.world.network.RegionNetworkManager networkManager = worldData.getNetworkManager();
                final org.bacon.ruthenium.world.network.RegionNetworkManager.NetworkMetrics metrics = networkManager.getMetrics();

                final String worldName = world.getRegistryKey().getValue().toString();
                Ruthenium.getLogger().info("[ruthenium network] World: {}", worldName);
                Ruthenium.getLogger().info("[ruthenium network]   Packets Processed: {}", metrics.packetsProcessed());
                Ruthenium.getLogger().info("[ruthenium network]   Region Transfers: {}", metrics.regionTransfers());
                Ruthenium.getLogger().info("[ruthenium network]   Cross-Region Packets: {}", metrics.crossRegionPackets());
                Ruthenium.getLogger().info("[ruthenium network]   Pending Packet Queues: {}", metrics.pendingPacketQueues());
                Ruthenium.getLogger().info("[ruthenium network]   Pending Transfers: {}", metrics.pendingTransfers());
                Ruthenium.getLogger().info("[ruthenium network]   Pending Disconnects: {}", metrics.pendingDisconnects());

                source.sendFeedback(() -> Text.literal("World " + worldName + ": " +
                    metrics.packetsProcessed() + " packets, " +
                    metrics.regionTransfers() + " transfers"), false);
            }
        }

        source.sendFeedback(() -> Text.literal("Network metrics written to console."), false);
        return 1;
    }

    private static long toMiB(final long bytes) {
        return bytes <= 0L ? 0L : bytes / (1024L * 1024L);
    }

    private static int executeConfigPath(final ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Ruthenium config path: " + RutheniumConfigManager.getConfigPath()), false);
        return 1;
    }

    private static int executeConfigReload(final ServerCommandSource source) {
        final RutheniumConfig config = Ruthenium.reloadConfig();
        source.sendFeedback(() -> Text.literal("Reloaded Ruthenium config: " + config.describe()), true);
        return 1;
    }

    private static int executeConfigDump(final ServerCommandSource source) {
        final RutheniumConfig config = Ruthenium.getConfig();
        source.sendFeedback(() -> Text.literal("Ruthenium config: " + config.describe()), false);
        source.sendFeedback(() -> Text.literal(" - scheduler.verbose=" + config.logging.schedulerVerbose
            + " threadCount=" + config.scheduler.threadCount
            + " maxScheduledTicksPerRegion=" + config.scheduler.maxScheduledTicksPerRegion), false);
        source.sendFeedback(() -> Text.literal(" - scheduler.logFallbacks=" + config.logging.schedulerLogFallbacks
            + " logFallbackStacks=" + config.logging.schedulerLogFallbackStackTraces
            + " logRegionSummaries=" + config.logging.schedulerLogRegionSummaries
            + " logTaskQueue=" + config.logging.schedulerLogTaskQueueProcessing), false);
        source.sendFeedback(() -> Text.literal(" - fallbackValidator.log=" + config.fallback.logFallbacks
            + " assert=" + config.fallback.assertMode), false);
        return 1;
    }
}
