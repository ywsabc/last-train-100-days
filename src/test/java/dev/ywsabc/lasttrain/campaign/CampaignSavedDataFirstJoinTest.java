package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataFirstJoinTest {
    @Test
    void firstJoinedMarkerIsPerPlayerIdempotentAndPersistent() {
        CampaignSavedData data = new CampaignSavedData();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(data.markFirstJoined(first));
        assertFalse(data.markFirstJoined(first));
        assertTrue(data.markFirstJoined(second));

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertTrue(loaded.hasFirstJoined(first));
        assertTrue(loaded.hasFirstJoined(second));
        assertFalse(loaded.markFirstJoined(first));
    }

    @Test
    void legacySaveTreatsEveryPlayerAsNotYetTaught() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        old.putString("campaign_id", UUID.randomUUID().toString());

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertFalse(loaded.hasFirstJoined(UUID.randomUUID()));
    }
}
