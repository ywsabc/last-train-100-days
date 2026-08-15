package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataScalingTest {
    @Test
    void stableTeamSizeIsAdoptedAndFrozenIntoTheNextMissionTarget() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        for (int tick = 1; tick <= PopulationScalingPolicy.CONFIRM_TICKS; tick++) {
            data.tick(6);
        }

        assertEquals(6, data.effectivePlayers());
        int expectedTarget = PopulationScalingPolicy.missionTarget(MissionType.RAIL_BREAK, 6);
        assertTrue(expectedTarget > MissionType.RAIL_BREAK.defaultTarget());
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        assertEquals(expectedTarget, data.activeMission().target());

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(6, loaded.effectivePlayers());
        assertEquals(expectedTarget, loaded.activeMission().target());
    }

    @Test
    void legacySavesWithoutScalingKeysDefaultToOneAndKeepBaselineTargets() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        old.putString("campaign_id", UUID.randomUUID().toString());
        old.putString("status", CampaignStatus.NOT_STARTED.name());

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertEquals(1, loaded.effectivePlayers());
        assertTrue(loaded.start());
        assertTrue(loaded.createMission(MissionType.SUPPLY_RECOVERY));
        assertEquals(
                MissionType.SUPPLY_RECOVERY.defaultTarget(),
                loaded.activeMission().target());
    }
}
