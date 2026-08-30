package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignPacingPolicy;
import dev.ywsabc.lasttrain.campaign.PopulationScalingPolicy;
import org.junit.jupiter.api.Test;

class TrackClearanceMissionTest {
    @Test
    void tunnelMilestoneUsesTheRealClearanceObjective() {
        assertEquals(
                MissionType.TRACK_CLEARANCE,
                CampaignPacingPolicy.KeyMission.TUNNEL.missionType());
        assertTrue(MissionType.TRACK_CLEARANCE.blocksRoute());
        assertTrue(MissionType.TRACK_CLEARANCE.occupiesMainlineSlot());
    }

    @Test
    void clearanceDebrisUsesStableContiguousOffsetsAndPopulationScaling() {
        assertArrayEquals(
                new int[]{-2, -1, 0, 1, 2},
                MissionWorldDirector.objectiveXOffsets(
                        MissionType.TRACK_CLEARANCE,
                        MissionType.TRACK_CLEARANCE.defaultTarget()));
        int scaled = PopulationScalingPolicy.missionTarget(MissionType.TRACK_CLEARANCE, 6);
        assertTrue(scaled > MissionType.TRACK_CLEARANCE.defaultTarget());
        int[] offsets = MissionWorldDirector.objectiveXOffsets(
                MissionType.TRACK_CLEARANCE, scaled);
        assertEquals(scaled, offsets.length);
        for (int index = 1; index < offsets.length; index++) {
            assertEquals(1, offsets[index] - offsets[index - 1]);
        }
    }

    @Test
    void clearanceHasTheSameRecoverableGraceAsOtherShortObstacles() {
        assertEquals(4, MissionFallbackPolicy.graceDays(MissionType.TRACK_CLEARANCE));
        assertTrue(MissionPoolPolicy.isShortOrSupplied(MissionType.TRACK_CLEARANCE));
    }
}
