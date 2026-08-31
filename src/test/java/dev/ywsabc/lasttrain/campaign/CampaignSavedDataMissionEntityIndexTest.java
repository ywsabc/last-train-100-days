package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionEntityContainer;
import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataMissionEntityIndexTest {
    @Test
    void missionSnapshotsPersistUuidsAndEnforceOneGlobalHardLimit() {
        CampaignSavedData data = started();
        assertTrue(data.createMission(MissionType.ZOMBIE_BLOCKADE));
        UUID blockadeId = data.activeMission().id();
        Set<UUID> expected = new LinkedHashSet<>();
        for (int index = 0;
                index < MissionEntityContainer.MAX_REGISTERED_ENTITIES - 1;
                index++) {
            UUID entityId = UUID.randomUUID();
            expected.add(entityId);
            assertTrue(data.registerMissionEntity(blockadeId, entityId));
        }

        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID rescueId = data.proposedMission().id();
        assertEquals(CampaignSavedData.ProposalAnswer.ACCEPTED, data.acceptProposal(rescueId, null));
        UUID survivorId = UUID.randomUUID();
        assertTrue(data.registerMissionEntity(rescueId, survivorId));
        assertFalse(data.registerMissionEntity(rescueId, UUID.randomUUID()));
        assertEquals(MissionEntityContainer.MAX_REGISTERED_ENTITIES, data.missionEntityCount());

        CampaignSavedData loaded = CampaignSavedData.load(data.save(new CompoundTag(), null), null);

        assertEquals(expected, loaded.missionEntityIds(blockadeId));
        assertEquals(Set.of(survivorId), loaded.missionEntityIds(rescueId));
        assertEquals(MissionEntityContainer.MAX_REGISTERED_ENTITIES, loaded.missionEntityCount());
    }

    @Test
    void settledOptionalMissionCarriesEntityIndexIntoDeferredCleanup() {
        CampaignSavedData data = started();
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID missionId = data.proposedMission().id();
        assertEquals(CampaignSavedData.ProposalAnswer.ACCEPTED, data.acceptProposal(missionId, null));
        assertTrue(data.assignOptionalMissionSite(missionId, new BlockPos(200, 70, -40)));
        UUID survivorId = UUID.randomUUID();
        assertTrue(data.registerMissionEntity(missionId, survivorId));

        assertEquals(
                CampaignSavedData.OptionalSkipResult.SKIPPED,
                data.skipOptionalMission(missionId, null));
        CampaignSavedData.PendingSiteCleanup cleanup = data.pendingSiteCleanups().get(0);
        assertEquals(Set.of(survivorId), cleanup.entityIds());
        assertEquals(Set.of(survivorId), data.missionEntityIds(missionId));

        CampaignSavedData loaded = CampaignSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(Set.of(survivorId), loaded.pendingSiteCleanups().get(0).entityIds());
        assertTrue(loaded.unregisterMissionEntity(missionId, survivorId));
        assertEquals(0, loaded.missionEntityCount());
    }

    @Test
    void loadTrimsCombinedMissionSnapshotsToTheGlobalHardLimit() {
        CampaignSavedData data = started();
        assertTrue(data.createMission(MissionType.ZOMBIE_BLOCKADE));
        assertTrue(data.proposeOptionalMission(MissionType.RESCUE_SURVIVOR));
        UUID rescueId = data.proposedMission().id();
        assertEquals(CampaignSavedData.ProposalAnswer.ACCEPTED, data.acceptProposal(rescueId, null));

        // 模拟手工篡改/旧实现写出的两个各自“合法”但合计超限的任务快照。
        for (int index = 0; index < MissionEntityContainer.MAX_REGISTERED_ENTITIES; index++) {
            assertTrue(data.activeMission().registerEntity(UUID.randomUUID()));
            assertTrue(data.optionalMission(rescueId).orElseThrow().registerEntity(UUID.randomUUID()));
        }
        assertEquals(96, data.missionEntityCount());

        CampaignSavedData loaded = CampaignSavedData.load(data.save(new CompoundTag(), null), null);

        assertEquals(MissionEntityContainer.MAX_REGISTERED_ENTITIES, loaded.missionEntityCount());
        assertTrue(loaded.integrityEvents().stream().anyMatch(issue ->
                issue.code() == CampaignIntegrityPolicy.Code.CORRUPT_SAVE_DATA
                        && issue.detail().contains("mission_entity_index")));
    }

    private static CampaignSavedData started() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start(UUID.randomUUID()));
        return data;
    }
}
