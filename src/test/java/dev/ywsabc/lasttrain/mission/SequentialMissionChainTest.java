package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** A07：供电、开门、防守必须属于同一可恢复顺序任务。 */
class SequentialMissionChainTest {
    @Test
    void stationPowerForcesPowerThenGateThenDefense() {
        assertFalse(MissionPoolPolicy.MAINLINE_TYPES.contains(MissionType.STATION_GATE));
        assertEquals(
                java.util.List.of(
                        MissionType.MissionPhase.RESTORE_POWER,
                        MissionType.MissionPhase.OPEN_GATE,
                        MissionType.MissionPhase.DEFEND_GATE),
                MissionType.STATION_POWER.phaseChain());
        CampaignSavedData director = new CampaignSavedData();
        director.start();
        assertFalse(director.createMission(MissionType.STATION_GATE));
        ActiveMission mission = ActiveMission.create(
                UUID.randomUUID(),
                MissionType.STATION_POWER,
                12,
                4,
                4);
        UUID id = mission.id();

        assertEquals(MissionType.MissionPhase.RESTORE_POWER, mission.currentPhase());
        assertTrue(mission.addProgress(mission.target(), 12));
        assertEquals(id, mission.id());
        assertEquals(MissionType.MissionPhase.OPEN_GATE, mission.currentPhase());
        assertEquals(MissionStage.ACTIVE, mission.stage());

        assertTrue(mission.addProgress(mission.target(), 13));
        assertEquals(MissionType.MissionPhase.DEFEND_GATE, mission.currentPhase());
        assertEquals(8, mission.target());
        assertEquals(MissionStage.ACTIVE, mission.stage());

        assertTrue(mission.addProgress(mission.target(), 14));
        assertEquals(MissionStage.READY_TO_TURN_IN, mission.stage());
    }

    @Test
    void phaseFailureDegradesButNeverSkipsTheCurrentPhase() {
        ActiveMission mission = ActiveMission.create(MissionType.STATION_POWER, 20, 8, 4);
        mission.addProgress(mission.target(), 20);
        assertEquals(MissionType.MissionPhase.OPEN_GATE, mission.currentPhase());

        assertTrue(mission.addProgress(1, 21));
        assertTrue(mission.failCurrentPhase(22, 1));
        assertEquals(MissionType.MissionPhase.OPEN_GATE, mission.currentPhase());
        assertEquals(0, mission.progress());
        assertEquals(1, mission.phaseFailures());
        assertEquals(22, mission.phaseStartedDay());
        assertFalse(mission.stage().terminal());

        mission.addProgress(mission.target(), 23);
        assertEquals(MissionType.MissionPhase.DEFEND_GATE, mission.currentPhase());
    }

    @Test
    void savedDataFailureKeepsTheSameMissionIdAndPhase() {
        CampaignSavedData data = new CampaignSavedData();
        data.start();
        assertTrue(data.createMission(MissionType.STATION_POWER));
        UUID id = data.activeMission().id();
        data.addMissionProgress(data.activeMission().target());
        data.addMissionProgress(1);

        assertTrue(data.failMissionPhase(1, 6));
        assertEquals(id, data.activeMission().id());
        assertEquals(MissionType.MissionPhase.OPEN_GATE, data.activeMission().currentPhase());
        assertEquals(0, data.activeMission().progress());
        assertEquals(6, data.threat());
    }

    @Test
    void reloadKeepsTheExactPhaseAndContinuesFromItsProgress() {
        ActiveMission mission = ActiveMission.create(MissionType.STATION_POWER, 30, 11, 4);
        mission.addProgress(mission.target(), 30);
        mission.addProgress(1, 31);

        ActiveMission loaded = ActiveMission.load(mission.save(null), null);

        assertEquals(mission.id(), loaded.id());
        assertEquals(MissionType.MissionPhase.OPEN_GATE, loaded.currentPhase());
        assertEquals(1, loaded.progress());
        assertEquals(30, loaded.phaseStartedDay());
        assertTrue(loaded.addProgress(1, 32));
        assertEquals(MissionType.MissionPhase.DEFEND_GATE, loaded.currentPhase());
        assertEquals(0, loaded.progress());
    }

    @Test
    void legacyReadyPowerSaveMigratesToFinishedLastPhase() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", UUID.randomUUID().toString());
        legacy.putString("type", "station_power");
        legacy.putString("stage", "READY_TO_TURN_IN");
        legacy.putInt("created_day", 4);
        legacy.putInt("target", 4);
        legacy.putInt("progress", 4);

        ActiveMission loaded = ActiveMission.load(legacy, null);

        assertEquals(MissionType.MissionPhase.DEFEND_GATE, loaded.currentPhase());
        assertEquals(MissionStage.READY_TO_TURN_IN, loaded.stage());
        assertEquals(loaded.target(), loaded.progress());
    }
}
