package org.bacon.ruthenium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.debug.RegionDebug;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;
import org.bacon.ruthenium.world.RegionTickStats;
import org.bacon.ruthenium.world.RegionizedServerWorld;

import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /region: Operators-only command that reports the executor’s current region,
 * total region count in the world, and basic tick stats (TPS/MSPT).
 */
public final class RegionCommand {

    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    private RegionCommand() {}

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
                final double avgMspt = stats == null ? 0.0D : stats.getAverageTickMillis();
                final double lastMs = stats == null ? 0.0D : (stats.getLastTickNanos() / 1_000_000.0D);
                final double maxMs = stats == null ? 0.0D : (stats.getMaxTickNanos() / 1_000_000.0D);
                final double minMs = stats == null ? 0.0D : (stats.getMinTickNanos() / 1_000_000.0D);
                final int samples = stats == null ? 0 : stats.getSampleCount();
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
                    sb.append(" - TPS: ").append(DF2.format(tps))
                      .append("  MSPT(avg): ").append(DF2.format(avgMspt))
                      .append("  last: ").append(DF2.format(lastMs))
                      .append("  min/max: ").append(DF2.format(minMs)).append("/").append(DF2.format(maxMs)).append('\n');
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
            );

        dispatcher.register(root);
    }
}
