package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataPursuitTest {
    @Test
    void parkedCampaignSamplesPressureAndPersistsIt() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        for (int tick = 1; tick <= PursuitPolicy.SAMPLE_INTERVAL_TICKS; tick++) {
            data.tick();
        }

        assertEquals(
                PursuitPolicy.INITIAL_ATTENTION
                        + PursuitPolicy.PARKED_ATTENTION_GROWTH_PER_SAMPLE,
                data.attention());
        assertEquals(
                PursuitPolicy.INITIAL_PURSUIT_DISTANCE
                        - PursuitPolicy.PARKED_PURSUIT_DRAIN_PER_SAMPLE,
                data.pursuitDistance());

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(data.attention(), loaded.attention());
        assertEquals(data.pursuitDistance(), loaded.pursuitDistance());
    }

    @Test
    void forwardRouteProgressCoolsAttentionAndExtendsPursuit() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.advanceRoute(2));

        for (int tick = 1; tick <= PursuitPolicy.SAMPLE_INTERVAL_TICKS; tick++) {
            data.tick();
        }

        assertEquals(
                Math.max(
                        PursuitPolicy.minAttention(1),
                        PursuitPolicy.INITIAL_ATTENTION
                                - 2 * PursuitPolicy.MOVING_ATTENTION_COOLING_PER_SEGMENT),
                data.attention());
        assertEquals(
                PursuitPolicy.INITIAL_PURSUIT_DISTANCE
                        + 2 * PursuitPolicy.PURSUIT_GAIN_PER_SEGMENT,
                data.pursuitDistance());
    }

    @Test
    void gunfireAndExplosionsApplyImmediatePressure() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        int beforeAttention = data.attention();
        int beforePursuit = data.pursuitDistance();
        data.registerGunfire();
        assertEquals(
                PursuitPolicy.afterGunfireAttention(beforeAttention),
                data.attention());
        assertEquals(
                PursuitPolicy.afterGunfirePursuit(beforePursuit),
                data.pursuitDistance());

        beforeAttention = data.attention();
        beforePursuit = data.pursuitDistance();
        data.registerExplosion();
        assertEquals(
                PursuitPolicy.afterExplosionAttention(beforeAttention),
                data.attention());
        assertEquals(
                PursuitPolicy.afterExplosionPursuit(beforePursuit),
                data.pursuitDistance());
    }

    @Test
    void zeroPursuitDistanceTriggersOneBlockadeAndTurnInRestoresDistance() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        CampaignSavedData.TickOutcome siegeOutcome = CampaignSavedData.TickOutcome.NONE;
        while (data.pursuitDistance() > 0) {
            siegeOutcome = data.tick();
        }

        assertEquals(CampaignSavedData.TickOutcome.SIEGE_TRIGGERED, siegeOutcome);
        assertNotNull(data.activeMission());
        assertEquals(MissionType.ZOMBIE_BLOCKADE, data.activeMission().type());

        int target = data.activeMission().target();
        assertTrue(data.addMissionProgress(target));
        assertTrue(data.turnInMission());
        assertNull(data.activeMission());
        assertTrue(data.pursuitDistance() >= PursuitPolicy.PURSUIT_AFTER_SIEGE);
    }

    @Test
    void legacySavesWithoutPressureKeysUseSafeDefaults() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        old.putString("campaign_id", "00000000-0000-0000-0000-000000000000");
        old.putString("status", CampaignStatus.NOT_STARTED.name());
        old.putInt("route_segment", 9);

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertEquals(PursuitPolicy.INITIAL_ATTENTION, loaded.attention());
        assertEquals(PursuitPolicy.INITIAL_PURSUIT_DISTANCE, loaded.pursuitDistance());
        assertEquals(loaded.routeSegment(), loaded.lastPursuitRouteSegment());
    }
}
