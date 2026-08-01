package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActiveMissionTest {
    @Test
    void progressStopsAtTargetAndBecomesReady() {
        ActiveMission mission = ActiveMission.create(MissionType.RAIL_BREAK, 12, 4);

        assertTrue(mission.addProgress(2));
        assertEquals(2, mission.progress());
        assertEquals(MissionStage.ACTIVE, mission.stage());

        assertTrue(mission.addProgress(99));
        assertEquals(mission.target(), mission.progress());
        assertEquals(MissionStage.READY_TO_TURN_IN, mission.stage());
    }

    @Test
    void completedMissionCannotReceiveMoreProgress() {
        ActiveMission mission = ActiveMission.create(MissionType.STATION_GATE, 3, 1);
        mission.complete();

        assertFalse(mission.addProgress(1));
        assertEquals(MissionStage.COMPLETED, mission.stage());
    }

    @Test
    void serializedNamesAreStableAndCaseInsensitive() {
        assertEquals(MissionType.STATION_POWER, MissionType.parse("STATION_POWER").orElseThrow());
        assertTrue(MissionType.parse("unknown").isEmpty());
    }

    @Test
    void observedProgressCanRecoverAfterAWorldStateRegression() {
        ActiveMission mission = ActiveMission.create(MissionType.STATION_POWER, 4, 2);

        assertTrue(mission.setObservedProgress(3));
        assertTrue(mission.setObservedProgress(1));
        assertEquals(1, mission.progress());
        assertEquals(MissionStage.ACTIVE, mission.stage());
    }

    @Test
    void callerCanProvideAPersistentMissionId() {
        UUID id = UUID.randomUUID();

        ActiveMission mission = ActiveMission.create(
                id,
                MissionType.ZOMBIE_BLOCKADE,
                100,
                42);

        assertEquals(id, mission.id());
        assertEquals(MissionType.ZOMBIE_BLOCKADE, mission.type());
    }
}
