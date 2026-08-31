package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.OptionalMissionPolicy;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Accelerated 100-day full-flow state machine smoke test (A14/A15/A16).
 *
 * <p>Runs the whole story from NOT_STARTED to COMPLETED on the strategy and
 * saved-data layer only — no world access, no wall clock — then switches to
 * endless mode and keeps generating content past day 100. Every simulated
 * day asserts the campaign invariants: day and route monotonicity, bounded
 * threat/attention/pursuit, mission slot mutual exclusion, in-order segment
 * generation, and the finale double gate.</p>
 */
class CampaignHundredDaySmokeTest {
    private static final int FAST_TICKS_PER_DAY = 60;
    private static final long CAMPAIGN_SEED = 0x4C415354L;
    private static final UUID CAPTAIN =
            UUID.fromString("00000000-0000-0000-0000-000000000041");

    @Test
    void hundredDayStorySmokeRunsTheFullStateMachineAndContinuesInEndless() {
        CampaignSavedData data = new CampaignSavedData();
        data.initialize(CAMPAIGN_SEED);
        assertTrue(data.start(CAPTAIN));
        FastForwardMode.enable(data, FAST_TICKS_PER_DAY);
        assertEquals(CampaignStatus.RUNNING, data.status());

        FastForwardMode.AdvanceSummary finalSummary = null;
        int simulatedDays = 0;
        while (data.day() < CampaignSavedData.FINAL_DAY && simulatedDays < 300) {
            int dayBefore = data.day();
            int routeBefore = data.routeSegment();
            long ticksBefore = data.totalActiveTicks();

            FastForwardMode.AdvanceSummary summary = FastForwardMode.simulateDay(data);
            finalSummary = summary;

            assertEquals(FAST_TICKS_PER_DAY, summary.ticksAdvanced());
            assertEquals(1, summary.daysAdvanced());
            assertEquals(dayBefore + 1, data.day());
            assertEquals(ticksBefore + FAST_TICKS_PER_DAY, data.totalActiveTicks());
            assertTrue(data.routeSegment() >= routeBefore);
            assertBoundedPressure(data);

            settleActiveMissions(data);
            settleOptionalRound(data);
            advanceRouteByOne(data);

            assertEquals(data.routeSegment(), data.generatedRouteSegment());
            assertMissionSlotMutualExclusion(data);
            simulatedDays++;
        }

        // 99 simulated days carry the story from day 1 onto day 100, where
        // the finale mission opened; the last accelerated day ends either
        // having started the finale directly or waiting for a mainline
        // roadblock that the settlement loop clears right after.
        assertEquals(99, simulatedDays);
        assertEquals(CampaignSavedData.FINAL_DAY, data.day());
        assertNotNull(finalSummary);
        assertTrue(
                finalSummary.lastOutcome() == CampaignSavedData.TickOutcome.DAY_ADVANCED
                        || finalSummary.lastOutcome()
                                == CampaignSavedData.TickOutcome.DAY_ADVANCED_WITH_FINALE);

        settleActiveMissions(data);
        // The finale double gate: the mission turn-in alone leaves the
        // campaign RUNNING — the day-100 active timer must elapse as well.
        for (int gate = 0; gate < 2 && data.status() == CampaignStatus.RUNNING; gate++) {
            FastForwardMode.simulateDay(data);
            settleActiveMissions(data);
        }
        assertEquals(CampaignStatus.COMPLETED, data.status());
        assertTrue(data.finaleMissionCompleted());
        assertTrue(data.finalDayElapsed());
        assertNull(data.activeMission());
        assertEquals(CampaignSavedData.FINAL_DAY, data.day());
        assertEquals(99, data.routeSegment());

        // A16: after completion the campaign enters endless pacing and keeps
        // generating missions and route beyond the story cap.
        assertTrue(data.enableEndlessMode());
        assertEquals(CampaignMode.ENDLESS, data.mode());
        assertEquals(CampaignStatus.RUNNING, data.status());
        for (int extraDay = 0; extraDay < 10; extraDay++) {
            int dayBefore = data.day();
            int routeBefore = data.routeSegment();

            FastForwardMode.AdvanceSummary summary = FastForwardMode.simulateDay(data);
            assertEquals(1, summary.daysAdvanced());
            assertEquals(dayBefore + 1, data.day());
            assertTrue(data.routeSegment() >= routeBefore);

            settleActiveMissions(data);
            settleOptionalRound(data);
            advanceRouteByOne(data);
            assertMissionSlotMutualExclusion(data);
            assertBoundedPressure(data);
        }
        assertEquals(CampaignSavedData.FINAL_DAY + 10, data.day());
        assertEquals(109, data.routeSegment());
        assertEquals(CampaignStatus.RUNNING, data.status());
        assertFalse(data.finalDayElapsed());
        assertTrue(data.generatedRouteSegment() >= data.routeSegment() - 1);
    }

    /** Completes every active mainline mission; at day 100 also the finale. */
    private static void settleActiveMissions(CampaignSavedData data) {
        if (data.day() >= CampaignSavedData.FINAL_DAY
                && data.finaleHubRouteSegment() > 0
                && !data.finaleHubMaterialized()) {
            while (data.generatedRouteSegment() < data.finaleHubRouteSegment()) {
                assertTrue(data.markRouteSegmentGenerated(data.generatedRouteSegment() + 1));
            }
            assertTrue(data.markFinaleHubMaterialized(data.finaleHubRouteSegment()));
            if (data.routeSegment() < data.finaleHubRouteSegment()) {
                assertTrue(data.advanceRouteTo(data.finaleHubRouteSegment()));
            }
        }
        for (int guard = 0; guard < 16; guard++) {
            if (data.status() != CampaignStatus.RUNNING) {
                return;
            }
            ActiveMission mission = data.activeMission();
            if (mission == null) {
                if (data.day() >= CampaignSavedData.FINAL_DAY
                        && !data.finaleMissionCompleted()) {
                    // Reconcile the finale window; tick() creates the finale
                    // mission when the slot is free.
                    data.tick();
                    continue;
                }
                return;
            }
            do {
                assertTrue(data.addMissionProgress(mission.target()));
                mission = data.activeMission();
            } while (mission != null && mission.stage() == MissionStage.ACTIVE);
            assertTrue(data.turnInMission());
        }
        fail("active mission settlement did not converge");
    }

    /** Accepts/empties the optional-mission pipeline like the world director. */
    private static void settleOptionalRound(CampaignSavedData data) {
        if (data.proposedMission() != null) {
            CampaignSavedData.ProposalAnswer answer = data.acceptProposal(null, null);
            if (answer == CampaignSavedData.ProposalAnswer.SLOTS_FULL) {
                assertEquals(
                        CampaignSavedData.ProposalAnswer.SKIPPED,
                        data.rejectProposal(null, null));
            } else {
                assertEquals(CampaignSavedData.ProposalAnswer.ACCEPTED, answer);
            }
        }
        for (ActiveMission mission : data.optionalMissions()) {
            if (mission.stage() == MissionStage.REWARD_PENDING) {
                assertTrue(data.markRewardClaimed(mission.id()));
                continue;
            }
            if (mission.stage() == MissionStage.ACTIVE) {
                completeOptionalObjective(data, mission);
            }
        }
        data.settleOptionalTimeouts();
    }

    private static void completeOptionalObjective(
            CampaignSavedData data,
            ActiveMission mission) {
        switch (mission.type()) {
            case RESCUE_SURVIVOR -> assertTrue(data.recordSurvivorRescued(mission.id()));
            case SALVAGE_CAR -> {
                for (int index = 0; index < mission.target(); index++) {
                    assertTrue(data.recordSalvageRepair(mission.id(), index));
                }
            }
            default -> fail("unexpected optional mission type " + mission.type());
        }
        assertEquals(MissionStage.READY_TO_TURN_IN, mission.stage());
        assertTrue(data.completeOptionalMission(mission.id()));
        assertTrue(data.markRewardClaimed(mission.id()));
    }

    /** One route segment per simulated day, committed in order. */
    private static void advanceRouteByOne(CampaignSavedData data) {
        if (data.status() != CampaignStatus.RUNNING) {
            return;
        }
        assertTrue(data.advanceRoute(1));
        if (data.generatedRouteSegment() < data.routeSegment()) {
            assertTrue(data.markRouteSegmentGenerated(data.generatedRouteSegment() + 1));
        }
    }

    private static void assertMissionSlotMutualExclusion(CampaignSavedData data) {
        if (data.activeMission() != null) {
            assertFalse(data.createMission(MissionType.SUPPLY_RECOVERY));
            assertFalse(data.createKeyMission(CampaignPacingPolicy.KeyMission.PROLOGUE_DEPARTURE));
        }
        assertTrue(
                data.optionalMissions().size()
                        <= OptionalMissionPolicy.MAX_ACTIVE_OPTIONAL_MISSIONS);
        assertTrue(data.rewardReceipts().size() <= RewardOutboxPolicy.MAX_RECEIPTS);
        assertTrue(data.pendingSiteCleanups().size() <= CampaignSavedData.MAX_PENDING_CLEANUPS);
    }

    private static void assertBoundedPressure(CampaignSavedData data) {
        assertTrue(data.threat() >= 0 && data.threat() <= 100, "threat " + data.threat());
        assertTrue(
                data.attention() >= 0 && data.attention() <= PursuitPolicy.MAX_ATTENTION,
                "attention " + data.attention());
        assertTrue(
                data.pursuitDistance() >= 0
                        && data.pursuitDistance() <= PursuitPolicy.MAX_PURSUIT_DISTANCE,
                "pursuit " + data.pursuitDistance());
    }
}
