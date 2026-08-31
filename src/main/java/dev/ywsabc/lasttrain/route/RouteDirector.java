package dev.ywsabc.lasttrain.route;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignMode;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.integration.TongDaTrackBridge;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.server.SableTrainTracker;
import dev.ywsabc.lasttrain.testing.FaultInjection;
import dev.ywsabc.lasttrain.text.TranslationKeys;
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
 * shape AND every branch run of the segment is physically complete. A
 * queued, materializing or conflicting branch keeps the segment uncommitted
 * — never marked generated, never plan-trimmed — and the next tick retries
 * within a bounded per-tick retry budget.</p>
 */
public final class RouteDirector {
    private static final int UPDATE_ALL = 3;
    private static final int UPDATE_CLEARING = 2 | 16 | 32;
    private static final int TICK_INTERVAL = 20;
    private static final int SEGMENTS_AHEAD = 2;
    private static final int VEHICLE_CLEARANCE_RADIUS = 3;
    private static final int VEHICLE_CLEARANCE_HEIGHT = 6;
    /**
     * Per-tick submission attempts for one branch track run. The bound keeps
     * a failing branch from spinning the director into an infinite loop: the
     * segment simply waits for the next tick and retries then.
     */
    static final int BRANCH_SUBMISSION_ATTEMPTS_PER_TICK = 1;
    private static boolean missingCreateTrackLogged;
    private static boolean safetyLimitLogged;
    private static int lastReportedTongDaSegment = -1;
    private static TongDaTrackBridge.SubmissionStatus lastReportedTongDaStatus;
    private static int lastReportedBranchSegment = -1;
    private static TongDaTrackBridge.SubmissionStatus lastReportedBranchStatus;

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
            data.activatePreparedStationsThrough(data.routeSegment());
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "message.lasttrain.route_advanced",
                            data.routeSegment()),
                    false);
            if (!hadMission && data.activeMission() != null) {
                broadcastMission(server, data.activeMission());
            }
        } else {
            // 重启可能发生在逻辑里程写盘之后、站点激活写盘之前；重复提升是幂等的。
            data.activatePreparedStationsThrough(data.routeSegment());
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
        if (nextSegment <= desiredSegment) {
            SegmentGeneration outcome = generateSegment(level, data, trackBlock, nextSegment);
            if (outcome != null
                    && commitMaterializedSegment(
                            data,
                            nextSegment,
                            outcome.mainlineComplete(),
                            outcome.branchResults())) {
                data.recordRouteTurnouts(outcome.turnouts());
                lastReportedTongDaSegment = -1;
                lastReportedTongDaStatus = null;
                LastTrain.LOGGER.info(
                        "TongDa materialized guaranteed route segment {} (through x offset {})",
                        nextSegment,
                        RouteGeometry.segmentEndOffset(nextSegment));
            }
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

    private static SegmentGeneration generateSegment(
            ServerLevel level,
            CampaignSavedData data,
            Block trackBlock,
            int segment) {
        if (!segmentGenerationAllowed()) {
            LastTrain.LOGGER.warn(
                    "Fault injected: route segment {} generation failed",
                    segment);
            return null;
        }
        RouteSegmentLayout layout = layoutFor(data, segment);
        BlockPos borderProbe = layout.borderProbe();
        if (!level.getWorldBorder().isWithinBounds(borderProbe)) {
            LastTrain.LOGGER.warn(
                    "Route segment {} reaches the world border at {}; generation is paused",
                    segment,
                    borderProbe);
            return null;
        }

        // The deck follows the plan-derived main-line steps instead of a
        // fixed eastbound loop: position and direction come from the layout.
        // Re-placement is idempotent: already-placed blocks are set to their
        // own state again, nothing is duplicated.
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
        if (mustSubmitMainline(inspection)) {
            prepareTongDaControlPosition(level, inspection.spawnerPosition());
            TongDaTrackBridge.SubmissionResult submission =
                    TongDaTrackBridge.submitStraightRun(
                            level,
                            layout.trackStart(),
                            layout.mainlineDirection(),
                            RouteGeometry.SEGMENT_LENGTH);
            reportTongDaStatus(segment, submission);
        }
        if (!inspection.actuallyComplete()) {
            // The main line is not physically complete yet: branch runs are
            // not attempted and the segment cannot commit. The eastbound XO
            // corridor keeps advancing normally across ticks.
            return new SegmentGeneration(false, List.of(), turnouts(layout));
        }

        if (layout.hasPlatform()) {
            buildPlatform(level, layout.platformAnchor(), layout.platformHalfLength());
        }
        // Branch track runs are submitted in layout order after the main
        // line completed; TongDa owns their physical placement. Each run
        // gets a bounded per-tick retry budget — a shape conflict or a still
        // queued run keeps the whole segment uncommitted and the next tick
        // retries.
        List<TongDaTrackBridge.SubmissionResult> branchResults = new java.util.ArrayList<>();
        for (RouteSegmentLayout.BranchTrackSection section : layout.branchTrackSections()) {
            BranchRunAttempt attempt = attemptBranchRun(
                    BRANCH_SUBMISSION_ATTEMPTS_PER_TICK,
                    section,
                    candidate -> TongDaTrackBridge.submitStraightRun(
                            level, candidate.start(), candidate.direction(), candidate.length()));
            reportBranchStatus(segment, section, attempt);
            branchResults.add(attempt.result());
        }
        return new SegmentGeneration(true, branchResults, turnouts(layout));
    }

    /**
     * Whether the main line must be (re)submitted: only while the physical
     * inspection is not yet complete. A completed main line is therefore
     * never resubmitted on later ticks while a failing branch keeps the
     * segment uncommitted — the already-placed main line stays untouched.
     */
    static boolean mustSubmitMainline(TongDaTrackBridge.SegmentInspection inspection) {
        java.util.Objects.requireNonNull(inspection, "inspection");
        return !inspection.actuallyComplete();
    }

    /** Submits one branch track run candidate; the director wraps the TongDa bridge. */
    @FunctionalInterface
    interface BranchSubmitter {
        TongDaTrackBridge.SubmissionResult submit(RouteSegmentLayout.BranchTrackSection section);
    }

    /**
     * One bounded retry batch of a branch run submission: how many attempts
     * the tick spent and the final submission result.
     */
    public record BranchRunAttempt(
            int attempts,
            TongDaTrackBridge.SubmissionResult result) {
        public BranchRunAttempt {
            java.util.Objects.requireNonNull(result, "result");
            if (attempts < 1) {
                throw new IllegalArgumentException(
                        "A branch submission batch must spend at least one attempt, got "
                                + attempts);
            }
        }

        /** Only an ALREADY_COMPLETE report finishes the run. */
        public boolean complete() {
            return branchRunComplete(result);
        }
    }

    /**
     * Pure bounded retry loop for one branch run: at most
     * {@code attemptsPerTick} submissions — the per-tick cap that prevents a
     * failing branch from looping forever — stopping at the first completed
     * report. A run still incomplete after the budget keeps the segment
     * waiting for the next tick.
     */
    static BranchRunAttempt attemptBranchRun(
            int attemptsPerTick,
            RouteSegmentLayout.BranchTrackSection section,
            BranchSubmitter submitter) {
        java.util.Objects.requireNonNull(section, "section");
        java.util.Objects.requireNonNull(submitter, "submitter");
        if (attemptsPerTick < 1) {
            throw new IllegalArgumentException(
                    "Attempts per tick must be at least 1, got " + attemptsPerTick);
        }
        TongDaTrackBridge.SubmissionResult result = submitter.submit(section);
        int attempts = 1;
        while (attempts < attemptsPerTick && !branchRunComplete(result)) {
            result = submitter.submit(section);
            attempts++;
        }
        return new BranchRunAttempt(attempts, result);
    }

    /** Only an ALREADY_COMPLETE report finishes a branch run; everything else is pending work or failure. */
    static boolean branchRunComplete(TongDaTrackBridge.SubmissionResult result) {
        java.util.Objects.requireNonNull(result, "result");
        return result.status() == TongDaTrackBridge.SubmissionStatus.ALREADY_COMPLETE;
    }

    /**
     * Pure completion arithmetic for one segment: the commit needs the main
     * line physically complete AND every branch run already complete — a
     * queued, materializing or conflicting branch keeps the whole segment
     * uncommitted.
     */
    static boolean commitEligible(
            boolean mainlineComplete,
            List<TongDaTrackBridge.SubmissionResult> branchResults) {
        if (!mainlineComplete) {
            return false;
        }
        for (TongDaTrackBridge.SubmissionResult result :
                java.util.Objects.requireNonNull(branchResults, "branchResults")) {
            if (!branchRunComplete(result)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pure commit gate applied by the director tick after one materialization
     * round: persistent route progress advances and the plan is trimmed only
     * when the whole segment — main line plus every branch run — is
     * physically complete. A conflicting or still-queued branch keeps the
     * segment ungenerated and the plan untrimmed, so the next tick retries
     * the same segment.
     */
    static boolean commitMaterializedSegment(
            CampaignSavedData data,
            int segment,
            boolean mainlineComplete,
            List<TongDaTrackBridge.SubmissionResult> branchResults) {
        java.util.Objects.requireNonNull(data, "data");
        if (!commitEligible(mainlineComplete, branchResults)) {
            return false;
        }
        // 必须在删掉已实现计划前取得站台坐标。只有完成主线和全部支线的
        // 物理提交才会记录候选站，尚在预生成队列中的站不会用于玩家汇合。
        RouteSegmentLayout layout = layoutFor(data, segment);
        BlockPos safeStation = layout.hasPlatform()
                ? layout.platformAnchor().offset(0, 1, 3)
                : null;
        if (!data.markRouteSegmentGenerated(segment)) {
            return false;
        }
        if (safeStation != null) {
            data.recordPreparedStation(segment, safeStation);
        }
        data.dropRoutePlanThrough(segment);
        return true;
    }

    /** Evidence of one materialization round, feeding the pure commit gate. */
    public record SegmentGeneration(
            boolean mainlineComplete,
            List<TongDaTrackBridge.SubmissionResult> branchResults,
            List<RouteTurnout> turnouts) {
        public SegmentGeneration(
                boolean mainlineComplete,
                List<TongDaTrackBridge.SubmissionResult> branchResults) {
            this(mainlineComplete, branchResults, List.of());
        }

        public SegmentGeneration {
            branchResults = List.copyOf(
                    java.util.Objects.requireNonNull(branchResults, "branchResults"));
            turnouts = List.copyOf(java.util.Objects.requireNonNull(turnouts, "turnouts"));
        }
    }

    /** 从 RouteDirector 已提交的真实分支线路提取任务现场，不另造虚假道岔。 */
    static List<RouteTurnout> turnouts(RouteSegmentLayout layout) {
        List<RouteTurnout> result = new java.util.ArrayList<>();
        List<BlockPos> junctions = layout.turnPoints();
        List<RouteSegmentLayout.BranchTrackSection> branches = layout.branchTrackSections();
        for (int index = 0; index < branches.size(); index++) {
            RouteSegmentLayout.BranchTrackSection branch = branches.get(index);
            BlockPos junction = index < junctions.size() ? junctions.get(index) : branch.start();
            result.add(new RouteTurnout(
                    layout.plan().segmentIndex(),
                    junction,
                    branch.direction()));
        }
        return List.copyOf(result);
    }

    private static void reportBranchStatus(
            int segment,
            RouteSegmentLayout.BranchTrackSection section,
            BranchRunAttempt attempt) {
        if (attempt.complete()) {
            LastTrain.LOGGER.info(
                    "Branch track run {} of segment {} is physically complete",
                    section.kind(),
                    segment);
            return;
        }
        if (segment == lastReportedBranchSegment
                && attempt.result().status() == lastReportedBranchStatus) {
            return;
        }
        lastReportedBranchSegment = segment;
        lastReportedBranchStatus = attempt.result().status();
        if (attempt.attempts() >= BRANCH_SUBMISSION_ATTEMPTS_PER_TICK) {
            LastTrain.LOGGER.warn(
                    "Branch track run {} of segment {} used its per-tick retry budget "
                            + "({} attempt(s)) without completing ({}): {}; the segment stays "
                            + "ungenerated and waits for the next tick",
                    section.kind(),
                    segment,
                    BRANCH_SUBMISSION_ATTEMPTS_PER_TICK,
                    attempt.result().status(),
                    attempt.result().detail());
        } else {
            LastTrain.LOGGER.info(
                    "Branch track run {} of segment {} is not complete yet: {}",
                    section.kind(),
                    segment,
                    attempt.result().status());
        }
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
                        Component.translatable(TranslationKeys.mission(mission.type())),
                        mission.routeSegment()),
                false);
    }

    private record BlockStateIds(Block spawner, Block completedMarker) {
    }
}
