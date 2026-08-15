package dev.ywsabc.lasttrain.route;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignMode;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.integration.TongDaTrackBridge;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.server.SableTrainTracker;
import dev.ywsabc.lasttrain.testing.FaultInjection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;

/**
 * Incrementally materializes an unbounded, deterministic route continuity
 * corridor in front of the party.
 *
 * <p>The director prepares at most one 64-block segment per second and keeps
 * only two segments ahead. Every segment is realized from its committed
 * {@link RouteSegmentPlan} (planned and persisted by
 * {@link RouteSegmentPlanner} in the plan → realize two-phase model): the
 * deck is laid step by step along the layout's plan-derived main-line track
 * steps instead of a fixed eastbound loop, the template drives the deck
 * support density, station platforms sit on their planned POI anchors with a
 * parallel platform siding, and city bypasses derive a real north/south
 * branch track run at their planned branch exit. The main line is submitted
 * first, the branch track runs afterwards — the ordered
 * {@link #trackRuns(RouteSegmentLayout)} sequence — through TongDa's track
 * spawner, which owns physical track placement; persistent route progress
 * advances only after all 64 Create tracks are observed with the expected
 * shape.</p>
 */
public final class RouteDirector {
    private static final int UPDATE_ALL = 3;
    private static final int UPDATE_CLEARING = 2 | 16 | 32;
    private static final int TICK_INTERVAL = 20;
    private static final int SEGMENTS_AHEAD = 2;
    private static final int VEHICLE_CLEARANCE_RADIUS = 3;
    private static final int VEHICLE_CLEARANCE_HEIGHT = 6;
    private static boolean missingCreateTrackLogged;
    private static boolean safetyLimitLogged;
    private static int lastReportedTongDaSegment = -1;
    private static TongDaTrackBridge.SubmissionStatus lastReportedTongDaStatus;

    private RouteDirector() {
    }

    public static void tick(MinecraftServer server, CampaignSavedData data, int serverTick) {
        if (serverTick % TICK_INTERVAL != 0
                || !RouteProgressPolicy.allowsRouteGeneration(data.mode(), data.status())
                || !data.starterStationBuilt()
                || !ModList.get().isLoaded("create")
                || !ModList.get().isLoaded(TongDaTrackBridge.TONGDA_MOD_ID)) {
            return;
        }

        ServerLevel level = server.overworld();
        Block trackBlock = registeredBlock("create:track");
        if (trackBlock == Blocks.AIR) {
            if (!missingCreateTrackLogged) {
                missingCreateTrackLogged = true;
                LastTrain.LOGGER.error(
                        "Create is loaded but create:track is unavailable; route generation is paused");
            }
            return;
        }

        int occupiedSegment = occupiedSegment(level, data);
        if (occupiedSegment > data.routeSegment()) {
            boolean hadMission = data.activeMission() != null;
            data.advanceRouteTo(occupiedSegment);
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "message.lasttrain.route_advanced",
                            data.routeSegment()),
                    false);
            if (!hadMission && data.activeMission() != null) {
                broadcastMission(server, data.activeMission());
            }
        }

        int desiredSegment = Math.max(
                SEGMENTS_AHEAD,
                Math.max(data.routeSegment(), occupiedSegment) + SEGMENTS_AHEAD);
        if (data.routeSafetyLimitReached()) {
            if (!safetyLimitLogged) {
                safetyLimitLogged = true;
                LastTrain.LOGGER.warn(
                        "Route safety limit of {} reached; endless generation is paused with existing world intact",
                        CampaignSavedData.MAX_ROUTE_SEGMENT);
            }
            return;
        }
        safetyLimitLogged = false;
        ActiveMission mission = data.activeMission();
        if (mission != null) {
            desiredSegment = Math.max(
                    desiredSegment,
                    RouteGeometry.missionSegment(mission.routeSegment()));
        }
        if (data.mode() == CampaignMode.STORY_100_DAYS
                && data.finaleHubRouteSegment() > 0) {
            // Once the final window is reserved, materialize through the
            // announced hub even before the day-100 mission is instantiated.
            desiredSegment = Math.max(desiredSegment, data.finaleHubRouteSegment());
        }
        for (ActiveMission optional : data.optionalMissions()) {
            if (optional.site() == null) {
                desiredSegment = Math.max(
                        desiredSegment,
                        RouteGeometry.missionSegment(optional.routeSegment()));
            }
        }
        desiredSegment = Math.min(CampaignSavedData.MAX_ROUTE_SEGMENT, desiredSegment);

        int nextSegment = data.generatedRouteSegment() + 1;
        if (nextSegment <= desiredSegment && generateSegment(level, data, trackBlock, nextSegment)) {
            data.markRouteSegmentGenerated(nextSegment);
            data.dropRoutePlanThrough(nextSegment);
            lastReportedTongDaSegment = -1;
            lastReportedTongDaStatus = null;
            LastTrain.LOGGER.info(
                    "TongDa materialized guaranteed route segment {} (through x offset {})",
                    nextSegment,
                    RouteGeometry.segmentEndOffset(nextSegment));
        }
    }

    public static BlockPos missionAnchor(CampaignSavedData data, int missionRouteSegment) {
        BlockPos station = data.starterStationAnchor();
        return station.offset(
                RouteGeometry.missionCenterOffset(missionRouteSegment),
                1,
                0);
    }

    /**
     * The route checkpoint of a mission, or null when it must not hold the
     * train: only an ACTIVE route-blocking mainline mission locks progress.
     * Optional missions and PROPOSED offers never block the main line, and a
     * READY mainline mission has already resolved its physical barrier.
     */
    public static Integer blockingCheckpoint(ActiveMission mission) {
        if (mission == null
                || mission.stage() != dev.ywsabc.lasttrain.mission.MissionStage.ACTIVE
                || !mission.type().blocksRoute()) {
            return null;
        }
        return mission.routeSegment();
    }

    private static int occupiedSegment(ServerLevel level, CampaignSavedData data) {
        if (!data.starterTrainAssembled() || data.starterTrainSublevelId() == null) {
            return data.routeSegment();
        }

        Optional<net.minecraft.world.phys.Vec3> trainPosition = SableTrainTracker.position(
                level,
                data.starterTrainSublevelId());
        if (trainPosition.isEmpty()) {
            return data.routeSegment();
        }

        int stationX = data.starterStationAnchor().getX();
        int offset = (int) Math.floor(trainPosition.orElseThrow().x) - stationX;
        int observed = RouteGeometry.segmentForOffset(offset);
        // Every ACTIVE route-blocking mainline mission is a route checkpoint.
        // The train, not a player on foot or an admin teleport, must clear it
        // before progression can move past the segment where it was issued.
        return RouteProgressPolicy.nextSegment(
                data.routeSegment(),
                data.generatedRouteSegment(),
                observed,
                blockingCheckpoint(data.activeMission()));
    }

    /**
     * Pure fault-injection gate consulted before the world-bound segment
     * generator touches any block. False means "this materialization attempt
     * failed": the segment is not committed and the next director tick
     * retries the same segment.
     */
    static boolean segmentGenerationAllowed() {
        return !FaultInjection.shouldFail(FaultInjection.FailurePoint.ROUTE_SEGMENT_GENERATION);
    }

    /**
     * The planned realization layout of one segment, ready for the world
     * bound generator. Plans the segment forward (and persists the extension)
     * when the committed plan list does not reach it yet, so the director
     * never falls back to fixed constants.
     */
    static RouteSegmentLayout layoutFor(CampaignSavedData data, int segment) {
        return RouteSegmentLayout.compute(
                data.starterStationAnchor(),
                segment,
                data.routePlan(segment));
    }

    /** Role of one ordered track run handed to the track backend. */
    public enum TrackRunKind {
        /** The only run that continues the main line. */
        MAIN_LINE,
        /** A side run such as a station siding or a city spur. */
        BRANCH
    }

    /**
     * One ordered track run handed to the track backend: start block,
     * horizontal direction and length.
     */
    public record TrackRun(
            BlockPos start,
            net.minecraft.core.Direction direction,
            int length,
            TrackRunKind kind) {
        public TrackRun {
            java.util.Objects.requireNonNull(start, "start");
            java.util.Objects.requireNonNull(direction, "direction");
            java.util.Objects.requireNonNull(kind, "kind");
            if (direction.getAxis() == net.minecraft.core.Direction.Axis.Y) {
                throw new IllegalArgumentException(
                        "Track runs must be horizontal, got " + direction);
            }
            if (length < 1) {
                throw new IllegalArgumentException(
                        "Track runs must be at least one block long, got " + length);
            }
        }
    }

    /**
     * The ordered submission sequence of one segment: the main line first,
     * then the branch track runs in layout order. The world generator
     * submits exactly this sequence, so a branch run can never overtake the
     * main line it diverges from.
     */
    static List<TrackRun> trackRuns(RouteSegmentLayout layout) {
        java.util.Objects.requireNonNull(layout, "layout");
        List<TrackRun> runs = new java.util.ArrayList<>();
        runs.add(new TrackRun(
                layout.trackStart(),
                layout.mainlineDirection(),
                RouteGeometry.SEGMENT_LENGTH,
                TrackRunKind.MAIN_LINE));
        for (RouteSegmentLayout.BranchTrackSection section : layout.branchTrackSections()) {
            runs.add(new TrackRun(
                    section.start(),
                    section.direction(),
                    section.length(),
                    TrackRunKind.BRANCH));
        }
        return List.copyOf(runs);
    }

    private static boolean generateSegment(
            ServerLevel level,
            CampaignSavedData data,
            Block trackBlock,
            int segment) {
        if (!segmentGenerationAllowed()) {
            LastTrain.LOGGER.warn(
                    "Fault injected: route segment {} generation failed",
                    segment);
            return false;
        }
        RouteSegmentLayout layout = layoutFor(data, segment);
        BlockPos borderProbe = layout.borderProbe();
        if (!level.getWorldBorder().isWithinBounds(borderProbe)) {
            LastTrain.LOGGER.warn(
                    "Route segment {} reaches the world border at {}; generation is paused",
                    segment,
                    borderProbe);
            return false;
        }

        // The deck follows the plan-derived main-line steps instead of a
        // fixed eastbound loop: position and direction come from the layout.
        for (RouteSegmentLayout.RouteTrackStep step : layout.mainlineSteps()) {
            BlockPos deckCenter = step.position().below();
            clearVehicleEnvelope(level, deckCenter, trackBlock);
            for (int z = -1; z <= 1; z++) {
                level.setBlock(
                        deckCenter.offset(0, 0, z),
                        Blocks.POLISHED_ANDESITE.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
        for (BlockPos support : layout.deckSupportTops()) {
            placeSupport(level, support);
        }

        TongDaTrackBridge.SegmentInspection inspection =
                TongDaTrackBridge.inspectEastboundSegment(level, layout.trackStart());
        if (!inspection.actuallyComplete()) {
            prepareTongDaControlPosition(level, inspection.spawnerPosition());
            TongDaTrackBridge.SubmissionResult submission =
                    TongDaTrackBridge.submitStraightRun(
                            level,
                            layout.trackStart(),
                            layout.mainlineDirection(),
                            RouteGeometry.SEGMENT_LENGTH);
            reportTongDaStatus(segment, submission);
            return false;
        }

        if (layout.hasPlatform()) {
            buildPlatform(level, layout.platformAnchor(), layout.platformHalfLength());
        }
        // Branch track runs are submitted in layout order after the main
        // line completed; TongDa owns their physical placement.
        for (RouteSegmentLayout.BranchTrackSection section : layout.branchTrackSections()) {
            TongDaTrackBridge.SubmissionResult result = TongDaTrackBridge.submitStraightRun(
                    level, section.start(), section.direction(), section.length());
            LastTrain.LOGGER.info(
                    "Submitted {} branch track run of segment {} at {}: {}",
                    section.kind(),
                    segment,
                    section.start(),
                    result.status());
        }
        return true;
    }

    private static void clearVehicleEnvelope(
            ServerLevel level,
            BlockPos deckCenter,
            Block trackBlock) {
        for (int y = 1; y <= VEHICLE_CLEARANCE_HEIGHT; y++) {
            for (int z = -VEHICLE_CLEARANCE_RADIUS; z <= VEHICLE_CLEARANCE_RADIUS; z++) {
                BlockPos pos = deckCenter.offset(0, y, z);
                if (y == 1 && z == 0 && level.getBlockState(pos).is(trackBlock)) {
                    continue;
                }
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_CLEARING);
                }
            }
        }
    }

    private static void prepareTongDaControlPosition(ServerLevel level, BlockPos spawnerPosition) {
        BlockStateIds ids = new BlockStateIds(
                registeredBlock("tongdarailway:track_spawner"),
                registeredBlock("create:rose_quartz_lamp"));
        Block existing = level.getBlockState(spawnerPosition).getBlock();
        if (existing != Blocks.AIR
                && existing != ids.spawner()
                && existing != ids.completedMarker()) {
            // z=+4 is a reserved control lane outside the train clearance.
            // Clear natural terrain before TongDa owns this deterministic cell.
            level.setBlock(spawnerPosition, Blocks.AIR.defaultBlockState(), UPDATE_CLEARING);
        }
    }

    private static void reportTongDaStatus(
            int segment,
            TongDaTrackBridge.SubmissionResult result) {
        if (segment == lastReportedTongDaSegment && result.status() == lastReportedTongDaStatus) {
            return;
        }
        lastReportedTongDaSegment = segment;
        lastReportedTongDaStatus = result.status();
        switch (result.status()) {
            case QUEUED -> LastTrain.LOGGER.info(
                    "Queued route segment {} through TongDa at {}",
                    segment,
                    result.spawnerPosition());
            case MATERIALIZATION_PENDING -> LastTrain.LOGGER.debug(
                    "TongDa is materializing route segment {} at {}",
                    segment,
                    result.spawnerPosition());
            case ALREADY_COMPLETE -> {
            }
            default -> LastTrain.LOGGER.warn(
                    "TongDa route segment {} is paused ({}): {}",
                    segment,
                    result.status(),
                    result.detail());
        }
    }

    private static void placeSupport(ServerLevel level, BlockPos top) {
        BlockPos cursor = top;
        int limit = Math.max(level.getMinBuildHeight(), top.getY() - 24);
        while (cursor.getY() >= limit && level.getBlockState(cursor).isAir()) {
            level.setBlock(cursor, Blocks.POLISHED_ANDESITE.defaultBlockState(), UPDATE_ALL);
            cursor = cursor.below();
        }
    }

    /**
     * Places the station platform around the planned anchor: stone brick
     * slabs to both sides of the main line with lanterns at the corners.
     */
    private static void buildPlatform(
            ServerLevel level,
            BlockPos anchor,
            int halfLength) {
        for (int offset = -halfLength; offset <= halfLength; offset++) {
            for (int z = -4; z <= 4; z++) {
                if (z == 0) {
                    continue;
                }
                level.setBlock(
                        anchor.offset(offset, 0, z),
                        Blocks.STONE_BRICKS.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
        for (int offset : new int[]{-halfLength + 2, halfLength - 2}) {
            for (int z : new int[]{-3, 3}) {
                level.setBlock(
                        anchor.offset(offset, 1, z),
                        Blocks.LANTERN.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
    }

    private static Block registeredBlock(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        return BuiltInRegistries.BLOCK.containsKey(key)
                ? BuiltInRegistries.BLOCK.get(key)
                : Blocks.AIR;
    }

    private static void broadcastMission(MinecraftServer server, ActiveMission mission) {
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable(
                        "message.lasttrain.mission_generated",
                        Component.translatable(
                                "mission.lasttrain." + mission.type().serializedName()),
                        mission.routeSegment()),
                false);
    }

    private record BlockStateIds(Block spawner, Block completedMarker) {
    }
}
