package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataFinaleTest {
    private static final long FULL_CAMPAIGN_TICKS =
            (long) CampaignSavedData.FINAL_DAY
                    * CampaignSavedData.DEFAULT_ACTIVE_TICKS_PER_DAY;

    @Test
    void fullActiveTimerAndFinaleTurnInAreBothRequired() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        CampaignSavedData.TickOutcome lastOutcome = CampaignSavedData.TickOutcome.NONE;
        for (long tick = 0; tick < FULL_CAMPAIGN_TICKS; tick++) {
            lastOutcome = data.tick();
            if (data.day() < CampaignSavedData.FINAL_DAY && data.activeMission() != null) {
                // Daily random missions are outside this timing assertion.
                assertTrue(data.clearMission());
            }
        }

        assertEquals(FULL_CAMPAIGN_TICKS, data.totalActiveTicks());
        assertEquals(CampaignSavedData.FINAL_DAY, data.day());
        assertEquals(CampaignSavedData.TickOutcome.FINAL_DAY_ELAPSED, lastOutcome);
        assertTrue(data.finalDayElapsed());
        assertFalse(data.finaleMissionCompleted());
        assertEquals(CampaignStatus.RUNNING, data.status());

        ActiveMission finale = data.activeMission();
        assertNotNull(finale);
        assertEquals(MissionType.ZOMBIE_BLOCKADE, finale.type());
        assertTrue(data.isFinaleMission(finale));
        assertTrue(data.addMissionProgress(finale.target()));
        assertTrue(data.turnInMission());

        assertTrue(data.finaleMissionCompleted());
        assertNull(data.activeMission());
        assertEquals(CampaignStatus.COMPLETED, data.status());
        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
    }

    @Test
    void existingMissionDelaysFinaleAndRecoveryReusesItsUuid() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        UUID ordinaryId = data.activeMission().id();

        data.advanceDays(CampaignSavedData.FINAL_DAY - 1);
        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
        assertEquals(ordinaryId, data.activeMission().id());
        assertNull(data.finaleMissionId());

        finishActiveMission(data);
        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());
        UUID finaleId = data.activeMission().id();
        assertNotEquals(ordinaryId, finaleId);
        assertTrue(data.isFinaleMission(data.activeMission()));

        assertTrue(data.clearMission());
        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());
        assertEquals(finaleId, data.activeMission().id());
    }

    @Test
    void schemaFiveCompletionReopensWithElapsedTimerAndPendingFinale() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 5);
        old.putString("campaign_id", UUID.randomUUID().toString());
        old.putString("status", CampaignStatus.COMPLETED.name());
        old.putInt("day", CampaignSavedData.FINAL_DAY);

        CampaignSavedData data = CampaignSavedData.load(old, null);

        assertEquals(CampaignStatus.RUNNING, data.status());
        assertTrue(data.finalDayElapsed());
        assertFalse(data.finaleMissionCompleted());
        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());
        assertTrue(data.isFinaleMission(data.activeMission()));

        CompoundTag migrated = data.save(new CompoundTag(), null);
        assertEquals(CampaignSavedData.CURRENT_SCHEMA, migrated.getInt("schema_version"));
        assertEquals(data.finaleMissionId().toString(), migrated.getString("finale_mission_id"));
    }

    @Test
    void dayNinetyReservesAFinaleHubInTheForwardWindowAndPersistsItsLocation() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.advanceRoute(12));
        data.advanceDays(FinalePolicy.FINALE_HUB_START_DAY - 1);

        data.tick();

        assertEquals(FinalePolicy.FINALE_HUB_START_DAY, data.day());
        assertTrue(data.finaleHubRouteSegment() > data.routeSegment());
        assertTrue(
                FinalePolicy.isFinaleHubInForwardWindow(
                        data.routeSegment(),
                        data.finaleHubRouteSegment()));

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(data.finaleHubRouteSegment(), loaded.finaleHubRouteSegment());
    }

    @Test
    void dayNinetyStopsNewMainlineObstaclesButKeepsOptionalSupplyMissionsAvailable() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        data.advanceDays(FinalePolicy.FINALE_HUB_START_DAY - 1);
        data.clearMission();

        assertFalse(data.createMission(MissionType.RAIL_BREAK));
        assertTrue(data.createMission(MissionType.SUPPLY_RECOVERY));
    }

    @Test
    void finaleWaitsForActiveMainlineButAProposedOfferCannotBlockCreation() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        data.advanceDays(CampaignSavedData.FINAL_DAY - 1);

        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
        assertNotNull(data.activeMission());
        assertNull(data.proposedMission());

        assertTrue(data.clearMission());
        assertTrue(data.proposeMission(MissionType.SUPPLY_RECOVERY));
        assertEquals(
                CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED,
                data.tick());
        assertNull(data.proposedMission());
        assertTrue(data.isFinaleMission(data.activeMission()));
        assertEquals(
                data.finaleHubRouteSegment(),
                data.activeMission().routeSegment());
    }

    @Test
    void legacySaveWithoutP4FieldsUsesSafeDefaults() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 6);
        old.putString("campaign_id", UUID.randomUUID().toString());
        old.putString("status", CampaignStatus.RUNNING.name());
        old.putInt("day", FinalePolicy.FINALE_HUB_START_DAY - 1);
        old.putInt("route_segment", 9);

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertEquals(0, loaded.finaleHubRouteSegment());
        assertNull(loaded.proposedMission());
        assertTrue(loaded.scheduledKeyMissions().isEmpty());
    }

    private static void finishActiveMission(CampaignSavedData data) {
        ActiveMission mission = data.activeMission();
        assertNotNull(mission);
        assertTrue(data.addMissionProgress(mission.target()));
        assertTrue(data.turnInMission());
    }
}
