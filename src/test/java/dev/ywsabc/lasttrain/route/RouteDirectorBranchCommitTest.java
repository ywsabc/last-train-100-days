package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.integration.TongDaTrackBridge;
import dev.ywsabc.lasttrain.integration.TongDaTrackPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * Branch-track commit honesty: a queued, materializing or shape-conflicting
 * branch run keeps the whole segment uncommitted — the segment is never
 * marked generated, its plan is never trimmed, and the next tick retries
 * within a bounded per-tick retry budget. The main line keeps advancing
 * normally and is never resubmitted once physically complete.
 */
class RouteDirectorBranchCommitTest {
    private static final long CAMPAIGN_SEED = 0x4C415354L;
    private static final BlockPos STATION = new BlockPos(0, 64, 0);
    private static final BlockPos SPAWNER = new BlockPos(4, 64, 4);

    @Test
    void branchRunsCompleteOnlyOnAlreadyComplete() {
        assertTrue(RouteDirector.branchRunComplete(result(
                TongDaTrackBridge.SubmissionStatus.ALREADY_COMPLETE, "all tracks present")));
        for (TongDaTrackBridge.SubmissionStatus pending :
                List.of(
                        TongDaTrackBridge.SubmissionStatus.QUEUED,
                        TongDaTrackBridge.SubmissionStatus.MATERIALIZATION_PENDING,
                        TongDaTrackBridge.SubmissionStatus.RETRY_DEFERRED,
                        TongDaTrackBridge.SubmissionStatus.TRACK_SHAPE_CONFLICT,
                        TongDaTrackBridge.SubmissionStatus.CONTROL_POSITION_BLOCKED)) {
            assertFalse(
                    RouteDirector.branchRunComplete(result(pending, "not complete")),
                    pending + " must not count as complete");
        }
    }

    @Test
    void commitNeedsTheMainlineAndEveryBranchComplete() {
        TongDaTrackBridge.SubmissionResult complete = result(
                TongDaTrackBridge.SubmissionStatus.ALREADY_COMPLETE, "done");
        TongDaTrackBridge.SubmissionResult queued = result(
                TongDaTrackBridge.SubmissionStatus.QUEUED, "pending placement");
        TongDaTrackBridge.SubmissionResult conflict = result(
                TongDaTrackBridge.SubmissionStatus.TRACK_SHAPE_CONFLICT, "shape mismatch");

        // Mainline incomplete: never eligible, whatever the branches report.
        assertFalse(RouteDirector.commitEligible(false, List.of(complete)));
        assertFalse(RouteDirector.commitEligible(false, List.of()));
        // A queued or conflicting branch keeps the segment uncommitted.
        assertFalse(RouteDirector.commitEligible(true, List.of(queued)));
        assertFalse(RouteDirector.commitEligible(true, List.of(conflict)));
        assertFalse(RouteDirector.commitEligible(true, List.of(complete, queued)));
        // Mainline complete plus every branch complete (or no branch): eligible.
        assertTrue(RouteDirector.commitEligible(true, List.of(complete)));
        assertTrue(RouteDirector.commitEligible(true, List.of()));
    }

    @Test
    void conflictingBranchKeepsTheSegmentUngeneratedAndUntrimmedThenRetries() {
        CampaignSavedData data = started();
        data.commitRoutePlans(List.of(straight(1), city(2)));

        TongDaTrackBridge.SubmissionResult conflict = result(
                TongDaTrackBridge.SubmissionStatus.TRACK_SHAPE_CONFLICT,
                "2 existing Create track(s) do not match the straight shape");
        // Tick 1: mainline done, branch conflicts → no progress, no trimming.
        assertFalse(RouteDirector.commitMaterializedSegment(data, 1, true, List.of(conflict)));
        assertEquals(0, data.generatedRouteSegment());
        assertEquals(2, data.plannedRouteSegments().size());

        // A queued branch defers the same way.
        assertFalse(RouteDirector.commitMaterializedSegment(
                data, 1, true, List.of(result(TongDaTrackBridge.SubmissionStatus.QUEUED, "queued"))));
        assertEquals(0, data.generatedRouteSegment());
        assertEquals(2, data.plannedRouteSegments().size());

        // Next tick: the branch reports physically complete → the segment
        // commits exactly once: generated marker advances and the plan list
        // is trimmed through the committed segment.
        assertTrue(RouteDirector.commitMaterializedSegment(
                data,
                1,
                true,
                List.of(result(TongDaTrackBridge.SubmissionStatus.ALREADY_COMPLETE, "complete"))));
        assertEquals(1, data.generatedRouteSegment());
        assertTrue(
                data.plannedRouteSegments().stream()
                        .noneMatch(plan -> plan.segmentIndex() <= 1));
    }

    @Test
    void completedMainlineIsNeverResubmittedWhileTheBranchKeepsRetrying() {
        TongDaTrackBridge.SegmentInspection complete = new TongDaTrackBridge.SegmentInspection(
                TongDaTrackPolicy.CompletionState.COMPLETE, 64, 64, 0, SPAWNER);
        TongDaTrackBridge.SegmentInspection materializing = new TongDaTrackBridge.SegmentInspection(
                TongDaTrackPolicy.CompletionState.MATERIALIZING, 40, 30, 0, SPAWNER);
        TongDaTrackBridge.SegmentInspection unverified = new TongDaTrackBridge.SegmentInspection(
                TongDaTrackPolicy.CompletionState.UNVERIFIED, 20, 20, 0, SPAWNER);

        // Idempotency: once the 64 XO tracks are observed complete, later
        // rounds — even while a branch still fails — never resubmit the main
        // line and never re-place the deck run.
        assertFalse(RouteDirector.mustSubmitMainline(complete));
        assertTrue(RouteDirector.mustSubmitMainline(materializing));
        assertTrue(RouteDirector.mustSubmitMainline(unverified));
    }

    @Test
    void physicallyCommittedStationBecomesAWaitingRallyPointUntilReached() {
        CampaignSavedData data = started();
        data.commitRoutePlans(List.of(station(1)));

        assertTrue(RouteDirector.commitMaterializedSegment(data, 1, true, List.of(
                result(TongDaTrackBridge.SubmissionStatus.ALREADY_COMPLETE, "complete"))));
        assertTrue(data.nearestActivatedStation().isEmpty());

        assertTrue(data.advanceRouteTo(1));
        assertTrue(data.activatePreparedStationsThrough(1));
        assertEquals(
                RouteSegmentLayout.compute(STATION, 1, station(1))
                        .platformAnchor()
                        .offset(0, 1, 3),
                data.nearestActivatedStation().orElseThrow());
    }

    @Test
    void branchRetryBudgetBoundsAttemptsAndStopsAtCompletion() {
        RouteSegmentLayout.BranchTrackSection section = new RouteSegmentLayout.BranchTrackSection(
                new BlockPos(10, 64, 0),
                Direction.NORTH,
                RouteSegmentLayout.CITY_SPUR_LENGTH,
                RouteSegmentLayout.BranchTrackKind.CITY_SPUR);
        TongDaTrackBridge.SubmissionResult complete = result(
                TongDaTrackBridge.SubmissionStatus.ALREADY_COMPLETE, "done");
        TongDaTrackBridge.SubmissionResult conflict = result(
                TongDaTrackBridge.SubmissionStatus.TRACK_SHAPE_CONFLICT, "mismatch");

        // Completes on the first attempt: the budget is not burned further.
        assertEquals(1, RouteDirector.attemptBranchRun(5, section, candidate -> complete).attempts());

        // Completes on the second attempt within the budget.
        AtomicInteger calls = new AtomicInteger();
        List<TongDaTrackBridge.SubmissionResult> script = List.of(
                result(TongDaTrackBridge.SubmissionStatus.QUEUED, "queued"), complete);
        RouteDirector.BranchRunAttempt recovered = RouteDirector.attemptBranchRun(
                2, section, candidate -> script.get(calls.getAndIncrement()));
        assertEquals(2, recovered.attempts());
        assertTrue(recovered.complete());

        // A run that never completes is bounded by the budget — the per-tick
        // cap that prevents an infinite retry loop — and stays incomplete, so
        // the segment waits for the next tick.
        RouteDirector.BranchRunAttempt stuck =
                RouteDirector.attemptBranchRun(3, section, candidate -> conflict);
        assertEquals(3, stuck.attempts());
        assertFalse(stuck.complete());
        assertFalse(RouteDirector.commitEligible(true, List.of(stuck.result())));

        // A zero or negative budget is rejected outright.
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteDirector.attemptBranchRun(0, section, candidate -> complete));
    }

    private static TongDaTrackBridge.SubmissionResult result(
            TongDaTrackBridge.SubmissionStatus status, String detail) {
        return new TongDaTrackBridge.SubmissionResult(status, SPAWNER, detail);
    }

    private static RouteSegmentPlan straight(int segment) {
        return new RouteSegmentPlan(
                segment,
                CAMPAIGN_SEED,
                SegmentTemplate.STRAIGHT,
                List.of(),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
    }

    private static RouteSegmentPlan city(int segment) {
        return new RouteSegmentPlan(
                segment,
                CAMPAIGN_SEED,
                SegmentTemplate.CITY_BYPASS,
                List.of(new RoutePoi(RoutePoiType.CITY, 24)),
                List.of(
                        new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH),
                        new RouteExit(RouteExitKind.BRANCH, 24)));
    }

    private static RouteSegmentPlan station(int segment) {
        return new RouteSegmentPlan(
                segment,
                CAMPAIGN_SEED,
                SegmentTemplate.STATION,
                List.of(new RoutePoi(RoutePoiType.STATION, 24)),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
    }

    private static CampaignSavedData started() {
        CampaignSavedData data = new CampaignSavedData();
        data.initialize(CAMPAIGN_SEED);
        data.start();
        data.markStarterStationBuilt(STATION);
        return data;
    }
}
