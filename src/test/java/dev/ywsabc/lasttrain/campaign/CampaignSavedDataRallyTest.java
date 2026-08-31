package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataRallyTest {
    @Test
    void generatedStationDoesNotActivateUntilTheTrainReachesIt() {
        CampaignSavedData data = runningCampaign();
        BlockPos safeFeet = new BlockPos(80, 65, 3);

        assertTrue(data.markRouteSegmentGenerated(1));
        assertTrue(data.recordPreparedStation(1, safeFeet));
        assertTrue(data.nearestActivatedStation().isEmpty());

        assertTrue(data.advanceRouteTo(1));
        assertTrue(data.activatePreparedStationsThrough(1));
        assertEquals(1, data.activatedStationSegment());
        assertEquals(safeFeet, data.nearestActivatedStation().orElseThrow());
    }

    @Test
    void latestReachedStationWinsAndRoundTrips() {
        CampaignSavedData data = runningCampaign();
        BlockPos first = new BlockPos(80, 65, 3);
        BlockPos latest = new BlockPos(208, 65, 3);

        assertTrue(data.markRouteSegmentGenerated(1));
        assertTrue(data.recordPreparedStation(1, first));
        assertTrue(data.markRouteSegmentGenerated(2));
        assertTrue(data.markRouteSegmentGenerated(3));
        assertTrue(data.recordPreparedStation(3, latest));
        assertTrue(data.advanceRouteTo(3));
        assertTrue(data.activatePreparedStationsThrough(3));

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(3, loaded.activatedStationSegment());
        assertEquals(latest, loaded.nearestActivatedStation().orElseThrow());
        assertFalse(loaded.activatePreparedStationsThrough(3));
    }

    @Test
    void oldSaveWithoutActivatedStationFallsBackCleanly() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        old.putString("campaign_id", "00000000-0000-0000-0000-000000000000");

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertEquals(0, loaded.activatedStationSegment());
        assertTrue(loaded.nearestActivatedStation().isEmpty());
    }

    private static CampaignSavedData runningCampaign() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        return data;
    }
}
