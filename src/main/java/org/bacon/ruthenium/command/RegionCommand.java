package org.bacon.ruthenium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.debug.RegionDebug;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;
import org.bacon.ruthenium.world.RegionTickStats;
import org.bacon.ruthenium.world.RegionizedServerWorld;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /region: Operators-only command that reports the executor’s current region,
 * total region count in the world, and basic tick stats (TPS/MSPT).
 */
public final class RegionCommand {

    private RegionCommand() {}

    /**
     * Registers the {@code /region} command and its sub-commands with the Brigadier dispatcher.
     *
     * @param dispatcher command dispatcher supplied during server command registration
     */
    public static void register(final CommandDispatcher<ServerCommandSource> dispatcher) {
        final LiteralArgumentBuilder<ServerCommandSource> root = literal("region")
            .requires(src -> src.hasPermissionLevel(2))
            .executes(ctx -> {
                final ServerCommandSource source = ctx.getSource();
                final ServerPlayerEntity player;
                try {
                    player = source.getPlayer();
                } catch (final Exception e) {
                    source.sendError(Text.literal("This command must be run by a player."));
                    return 0;
                }

                final var world = source.getWorld();
                if (!(world instanceof RegionizedServerWorld regionized)) {
                    source.sendError(Text.literal("World is not regionized."));
                    return 0;
                }

                final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = regionized.ruthenium$getRegionizer();
                final ChunkPos playerChunk = player.getChunkPos();
                final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region =
                    regionizer.getRegionForChunk(playerChunk.x, playerChunk.z);

                final AtomicInteger totalRegions = new AtomicInteger();
                regionizer.computeForAllRegions(r -> totalRegions.incrementAndGet());

                if (region == null) {
                    source.sendFeedback(() -> Text.literal(
                        "Region info:\n" +
                        " - total regions: " + totalRegions.get() + "\n" +
                        " - you are not currently in a region (chunk " + playerChunk.x + ", " + playerChunk.z + ")"
                    ), false);
                    return 1;
                }

                final long regionId = region.id;
                final int sectionCount = region.getOwnedSections().size();
                final int chunkCount = region.getData().getChunks().size();
                final ChunkPos center = region.getCenterChunk();

                final RegionTickStats stats = region.getData().getTickStats();
                final RegionTickStats.Snapshot snapshot = stats == null ? RegionTickStats.Snapshot.EMPTY : stats.snapshot();
                final double avgMspt = snapshot.averageTickMillis();
                final double lastMs = snapshot.lastTickMillis();
                final double maxMs = snapshot.maxTickMillis();
                final double minMs = snapshot.minTickMillis();
                final int samples = snapshot.sampleCount();
                final double tps = avgMspt <= 0.0D ? 0.0D : Math.min(20.0D, 1000.0D / avgMspt);

                final StringBuilder sb = new StringBuilder(256);
                sb.append("Region info:\n");
                sb.append(" - region id: ").append(regionId).append('\n');
                sb.append(" - your chunk: ").append(playerChunk.x).append(", ").append(playerChunk.z).append('\n');
                if (center != null) {
                    sb.append(" - center chunk: ").append(center.x).append(", ").append(center.z).append('\n');
                }
                sb.append(" - total regions: ").append(totalRegions.get()).append('\n');
                sb.append(" - sections: ").append(sectionCount).append("  chunks: ").append(chunkCount).append('\n');
                if (stats == null || samples == 0) {
                    sb.append(" - TPS: (warming up)  MSPT: (no samples yet)\n");
                } else {
                    sb.append(" - TPS: ").append(formatDouble(tps))
                      .append("  MSPT(avg): ").append(formatDouble(avgMspt))
                      .append("  last: ").append(formatDouble(lastMs))
                      .append("  min/max: ").append(formatDouble(minMs)).append("/").append(formatDouble(maxMs)).append('\n');
                }

                // basic queue state (no size available)
                final boolean hasTasks = !region.getData().getTaskQueue().isEmpty();
                sb.append(" - tasks queued: ").append(hasTasks ? "yes" : "no");

                source.sendFeedback(() -> Text.literal(sb.toString()), false);
                return 1;
            })
            .then(literal("logging")
                // global toggles remain for convenience
                .then(literal("on").executes(ctx -> {
                    RegionDebug.setAll(true);
                    ctx.getSource().sendFeedback(() -> Text.literal("Region logging: all categories enabled"), true);
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    RegionDebug.setAll(false);
                    ctx.getSource().sendFeedback(() -> Text.literal("Region logging: all categories disabled"), true);
                    return 1;
                }))
                .then(literal("toggle").executes(ctx -> {
                    // toggle all by flipping lifecycle; if off, turn all on; if any on, turn all off
                    final boolean anyEnabled = RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE)
                        || RegionDebug.isEnabled(RegionDebug.LogCategory.MOVEMENT)
                        || RegionDebug.isEnabled(RegionDebug.LogCategory.SCHEDULER);
                    RegionDebug.setAll(!anyEnabled);
                    ctx.getSource().sendFeedback(() -> Text.literal("Region logging toggled for all categories: " + (!anyEnabled ? "enabled" : "disabled")), true);
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Region logging categories: " + RegionDebug.statusLine()), false);
                    return 1;
                }))
                // category management
                .then(literal("category")
                    .then(literal("lifecycle")
                        .then(literal("on").executes(ctx -> { RegionDebug.enable(RegionDebug.LogCategory.LIFECYCLE); ctx.getSource().sendFeedback(() -> Text.literal("Lifecycle logging enabled"), true); return 1; }))
                        .then(literal("off").executes(ctx -> { RegionDebug.disable(RegionDebug.LogCategory.LIFECYCLE); ctx.getSource().sendFeedback(() -> Text.literal("Lifecycle logging disabled"), true); return 1; }))
                        .then(literal("toggle").executes(ctx -> { final boolean s = RegionDebug.toggle(RegionDebug.LogCategory.LIFECYCLE); ctx.getSource().sendFeedback(() -> Text.literal("Lifecycle logging " + (s ? "enabled" : "disabled")), true); return 1; }))
                        .then(literal("status").executes(ctx -> { ctx.getSource().sendFeedback(() -> Text.literal("Lifecycle logging is: " + (RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE) ? "enabled" : "disabled")), false); return 1; }))
                    )
                    .then(literal("movement")
                        .then(literal("on").executes(ctx -> { RegionDebug.enable(RegionDebug.LogCategory.MOVEMENT); ctx.getSource().sendFeedback(() -> Text.literal("Movement logging enabled"), true); return 1; }))
                        .then(literal("off").executes(ctx -> { RegionDebug.disable(RegionDebug.LogCategory.MOVEMENT); ctx.getSource().sendFeedback(() -> Text.literal("Movement logging disabled"), true); return 1; }))
                        .then(literal("toggle").executes(ctx -> { final boolean s = RegionDebug.toggle(RegionDebug.LogCategory.MOVEMENT); ctx.getSource().sendFeedback(() -> Text.literal("Movement logging " + (s ? "enabled" : "disabled")), true); return 1; }))
                        .then(literal("status").executes(ctx -> { ctx.getSource().sendFeedback(() -> Text.literal("Movement logging is: " + (RegionDebug.isEnabled(RegionDebug.LogCategory.MOVEMENT) ? "enabled" : "disabled")), false); return 1; }))
                    )
                    .then(literal("scheduler")
                        .then(literal("on").executes(ctx -> { RegionDebug.enable(RegionDebug.LogCategory.SCHEDULER); ctx.getSource().sendFeedback(() -> Text.literal("Scheduler logging enabled"), true); return 1; }))
                        .then(literal("off").executes(ctx -> { RegionDebug.disable(RegionDebug.LogCategory.SCHEDULER); ctx.getSource().sendFeedback(() -> Text.literal("Scheduler logging disabled"), true); return 1; }))
                        .then(literal("toggle").executes(ctx -> { final boolean s = RegionDebug.toggle(RegionDebug.LogCategory.SCHEDULER); ctx.getSource().sendFeedback(() -> Text.literal("Scheduler logging " + (s ? "enabled" : "disabled")), true); return 1; }))
                        .then(literal("status").executes(ctx -> { ctx.getSource().sendFeedback(() -> Text.literal("Scheduler logging is: " + (RegionDebug.isEnabled(RegionDebug.LogCategory.SCHEDULER) ? "enabled" : "disabled")), false); return 1; }))
                    )
                )
            )
            .then(literal("list").executes(ctx -> {
                final ServerCommandSource source = ctx.getSource();
                final ServerWorld world = source.getWorld();
                if (!(world instanceof RegionizedServerWorld regionized)) {
                    source.sendError(Text.literal("World is not regionized."));
                    return 0;
                }

                final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = regionized.ruthenium$getRegionizer();
                final List<RegionInfo> regions = new ArrayList<>();

                regionizer.computeForAllRegions(region -> {
                    final RegionTickStats stats = region.getData().getTickStats();
                    final RegionTickStats.Snapshot snapshot = stats == null ? RegionTickStats.Snapshot.EMPTY : stats.snapshot();
                    final ChunkPos center = region.getCenterChunk();
                    regions.add(new RegionInfo(
                        region.id,
                        region.getData().getChunks().size(),
                        snapshot.getTPS(),
                        snapshot.getMSPT(),
                        snapshot.isLagging(),
                        center != null ? center.x : 0,
                        center != null ? center.z : 0
                    ));
                });

                if (regions.isEmpty()) {
                    source.sendFeedback(() -> Text.literal("No active regions in this world."), false);
                    return 1;
                }

                // Sort by MSPT descending (laggiest first)
                regions.sort(Comparator.comparingDouble(RegionInfo::mspt).reversed());

                final StringBuilder sb = new StringBuilder(256);
                sb.append("§6--- Region List (").append(regions.size()).append(" regions) ---§r\n");
                for (final RegionInfo info : regions) {
                    final String statusColor = info.lagging() ? "§c" : "§a";
                    sb.append(statusColor)
                      .append("Region ").append(info.id())
                      .append("§r: TPS=").append(formatDouble(info.tps()))
                      .append(" MSPT=").append(formatDouble(info.mspt()))
                      .append(" chunks=").append(info.chunks())
                      .append(" center=(").append(info.centerX()).append(",").append(info.centerZ()).append(")")
                      .append('\n');
                }

                // Summary
                final long laggingCount = regions.stream().filter(RegionInfo::lagging).count();
                if (laggingCount > 0) {
                    sb.append("§c").append(laggingCount).append(" region(s) lagging (>50 MSPT)§r");
                } else {
                    sb.append("§aAll regions running at full speed§r");
                }

                source.sendFeedback(() -> Text.literal(sb.toString()), false);
                return 1;
            }));

        dispatcher.register(root);
    }

    private record RegionInfo(long id, int chunks, double tps, double mspt, boolean lagging, int centerX, int centerZ) {}

    /**
     * Formats an MSPT or TPS value using a precision appropriate for its magnitude.
     *
     * @param value numeric value to format
     * @return string suitable for chat output
     */
    static String formatDouble(final double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }

        final double abs = Math.abs(value);
        final int maxFractionDigits;
        if (abs >= 10.0D) {
            maxFractionDigits = 2;
        } else if (abs >= 1.0D) {
            maxFractionDigits = 3;
        } else if (abs >= 0.1D) {
            maxFractionDigits = 4;
        } else if (abs >= 0.01D) {
            maxFractionDigits = 5;
        } else {
            maxFractionDigits = 6;
        }

        final NumberFormat format = NumberFormat.getInstance(Locale.ROOT);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(Math.min(2, maxFractionDigits));
        format.setMaximumFractionDigits(Math.max(2, maxFractionDigits));
        return format.format(value);
    }
}
