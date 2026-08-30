package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.RewardOutboxPolicy;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataMainlineRewardTest {
    @Test
    void mainlineTurnInPersistsOnePendingRewardBeforeWorldDelivery() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.createMission(MissionType.TRACK_CLEARANCE));
        ActiveMission mission = data.activeMission();
        UUID id = mission.id();
        assertTrue(data.addMissionProgress(mission.target()));

        assertTrue(data.turnInMission());
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                data.rewardReceipt(id).orElseThrow().state());
        assertTrue(RewardOutboxPolicy.isLegalPayload(
                RewardOutboxPolicy.payload(MissionType.TRACK_CLEARANCE, id)));

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null), null);
        assertEquals(
                RewardOutboxPolicy.ReceiptState.PENDING,
                loaded.rewardReceipt(id).orElseThrow().state());
        assertTrue(loaded.markRewardClaimed(id));
        assertEquals(
                RewardOutboxPolicy.ReceiptState.CLAIMED,
                loaded.rewardReceipt(id).orElseThrow().state());
    }

    @Test
    void supplyRecoveryDoesNotDuplicateTheLootAlreadyTakenFromItsBarrels() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.createMission(MissionType.SUPPLY_RECOVERY));
        UUID id = data.activeMission().id();
        assertTrue(data.addMissionProgress(data.activeMission().target()));
        assertTrue(data.turnInMission());

        assertFalse(RewardOutboxPolicy.rewardedOnTurnIn(MissionType.SUPPLY_RECOVERY));
        assertTrue(data.rewardReceipt(id).isEmpty());
    }

    @Test
    void everyOutboxRewardUsesLegalVanillaStacks() {
        UUID id = UUID.randomUUID();
        for (MissionType type : MissionType.values()) {
            if (!RewardOutboxPolicy.rewardedOnTurnIn(type)) {
                continue;
            }
            assertTrue(
                    RewardOutboxPolicy.isLegalPayload(RewardOutboxPolicy.payload(type, id)),
                    type.name());
        }
    }
}
