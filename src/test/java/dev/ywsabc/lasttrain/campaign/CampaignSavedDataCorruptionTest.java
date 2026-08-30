package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataCorruptionTest {
    @Test
    void truncatedAndWrongTypeNbtFallsBackAndRemainsWritable() {
        CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        corrupt.putString("campaign_id", UUID.randomUUID().toString());
        corrupt.putString("status", CampaignStatus.RUNNING.name());
        corrupt.putString("day", "not-an-int");
        corrupt.put("active_mission", StringTag.valueOf("truncated"));
        corrupt.putString("infection_ticks", "not-a-long");

        CampaignSavedData loaded = assertDoesNotThrow(
                () -> CampaignSavedData.load(corrupt, null));

        assertEquals(1, loaded.day());
        assertEquals(0L, loaded.infectionTicks());
        assertNull(loaded.activeMission());
        assertTrue(CampaignIntegrityPolicy.audit(loaded).issues().stream()
                .anyMatch(issue -> issue.code()
                        == CampaignIntegrityPolicy.Code.CORRUPT_SAVE_DATA));

        CompoundTag healed = assertDoesNotThrow(
                () -> loaded.save(new CompoundTag(), null));
        CampaignSavedData reloaded = assertDoesNotThrow(
                () -> CampaignSavedData.load(healed, null));
        assertTrue(reloaded.integrityEvents().stream()
                .anyMatch(issue -> issue.code()
                        == CampaignIntegrityPolicy.Code.CORRUPT_SAVE_DATA));
    }

    @Test
    void illegalReceiptEnumsAndNonRewardTypesAreDroppedBeforeTick() {
        CompoundTag corrupt = runningTag();
        ListTag receipts = new ListTag();
        receipts.add(receipt("rescue_survivor", "HALF_WRITTEN"));
        receipts.add(receipt("supply_recovery", "PENDING"));
        receipts.add(receipt("future_mission", "PENDING"));
        corrupt.put("reward_receipts", receipts);

        CampaignSavedData loaded = assertDoesNotThrow(
                () -> CampaignSavedData.load(corrupt, null));

        assertTrue(loaded.rewardReceipts().isEmpty());
        assertDoesNotThrow(() -> loaded.tick(1));
        assertDoesNotThrow(() -> loaded.save(new CompoundTag(), null));
        assertTrue(loaded.integrityEvents().stream()
                .anyMatch(issue -> issue.detail().startsWith("reward_receipts[")));
    }

    @Test
    void unknownCampaignStatusFailsClosedAndTrainHealthCannotClearIt() {
        CompoundTag corrupt = runningTag();
        corrupt.putString("status", "FUTURE_RECOVERY_STATE");

        CampaignSavedData loaded = CampaignSavedData.load(corrupt, null);

        assertEquals(CampaignStatus.SAFE_MODE, loaded.status());
        assertTrue(loaded.safeModeReasons().contains(SafeModeReason.SAVE_INTEGRITY));
        loaded.observeTrain(true, true, true, true, false, 1);
        assertEquals(CampaignStatus.SAFE_MODE, loaded.status());
        assertTrue(loaded.safeModeReasons().contains(SafeModeReason.SAVE_INTEGRITY));
    }

    private static CompoundTag runningTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        return tag;
    }

    private static CompoundTag receipt(String type, String state) {
        CompoundTag receipt = new CompoundTag();
        receipt.putString("mission_id", UUID.randomUUID().toString());
        receipt.putString("type", type);
        receipt.putString("state", state);
        return receipt;
    }
}
