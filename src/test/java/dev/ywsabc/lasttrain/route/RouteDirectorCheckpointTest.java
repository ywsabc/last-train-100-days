package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import org.junit.jupiter.api.Test;

/**
 * Integration contract between the route director and the mission system:
 * only an ACTIVE route-blocking mainline mission holds the train; optional
 * missions and PROPOSED offers never produce a checkpoint.
 */
class RouteDirectorCheckpointTest {
    @Test
    void noMissionMeansNoCheckpoint() {
        assertNull(RouteDirector.blockingCheckpoint(null));
    }

    @Test
    void activeMainlineMissionsHoldTheirSegment() {
        ActiveMission rail = ActiveMission.create(MissionType.RAIL_BREAK, 5, 12);
        assertEquals(12, RouteDirector.blockingCheckpoint(rail));
    }

    @Test
    void readyMainlineMissionsReleasedTheirPhysicalBarrier() {
        ActiveMission rail = ActiveMission.create(MissionType.RAIL_BREAK, 5, 12);
        rail.setObservedProgress(rail.target());
        assertEquals(MissionStage.READY_TO_TURN_IN, rail.stage());
        assertNull(RouteDirector.blockingCheckpoint(rail));
    }

    @Test
    void proposedOptionalMissionsNeverBlockTheRoute() {
        ActiveMission proposal = ActiveMission.createProposal(
                MissionType.RESCUE_SURVIVOR,
                9,
                20,
                9_000L);
        assertEquals(MissionStage.PROPOSED, proposal.stage());
        assertNull(RouteDirector.blockingCheckpoint(proposal));

        proposal.transitionTo(MissionStage.ACTIVE);
        assertNull(RouteDirector.blockingCheckpoint(proposal));

        ActiveMission salvage = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                9,
                20,
                9_000L);
        salvage.transitionTo(MissionStage.ACTIVE);
        assertNull(RouteDirector.blockingCheckpoint(salvage));
    }

    @Test
    void activeZombieBlockadeStillHoldsTheRoute() {
        ActiveMission blockade = ActiveMission.create(MissionType.ZOMBIE_BLOCKADE, 30, 33);
        assertEquals(33, RouteDirector.blockingCheckpoint(blockade));
    }
}
