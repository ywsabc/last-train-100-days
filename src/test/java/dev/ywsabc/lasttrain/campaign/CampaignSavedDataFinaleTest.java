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
    void fullActiveTimerAndAllThreeFinaleStagesAreRequired() {
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
        assertEquals(FinalePhase.ARRIVAL, data.finalePhase());
        assertNull(data.activeMission());

        materializeAndReachHub(data);
        ActiveMission restart = data.activeMission();
        assertNotNull(restart);
        assertEquals(FinalePhase.RESTART, data.finalePhase());
        assertEquals(MissionType.STATION_POWER, restart.type());
        assertEquals(3, restart.target());
        assertTrue(data.threat() >= 75);
        assertTrue(data.addMissionProgress(restart.target()));
        assertTrue(data.turnInMission());

        ActiveMission dawn = data.activeMission();
        assertNotNull(dawn);
        assertEquals(FinalePhase.HOLD_DAWN, data.finalePhase());
        assertEquals(MissionType.ZOMBIE_BLOCKADE, dawn.type());
        assertTrue(dawn.target() >= 24);
        assertTrue(data.threat() >= 90);
        assertTrue(data.addMissionProgress(dawn.target()));
        assertTrue(data.turnInMission());

        assertTrue(data.finaleMissionCompleted());
        assertEquals(FinalePhase.COMPLETED, data.finalePhase());
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
        assertEquals(CampaignSavedData.TickOutcome.FINALE_PHASE_ADVANCED, data.tick());
        assertEquals(FinalePhase.ARRIVAL, data.finalePhase());
        materializeAndReachHub(data);
        UUID finaleId = data.activeMission().id();
        assertNotEquals(ordinaryId, finaleId);
        assertTrue(data.isFinaleMission(data.activeMission()));
        assertEquals(MissionType.STATION_POWER, data.activeMission().type());

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
        assertEquals(FinalePhase.ARRIVAL, data.finalePhase());
        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
        materializeAndReachHub(data);
        assertEquals(MissionType.STATION_POWER, data.activeMission().type());

        CompoundTag migrated = data.save(new CompoundTag(), null);
        assertEquals(CampaignSavedData.CURRENT_SCHEMA, migrated.getInt("schema_version"));
        assertEquals(data.finalePhase().serializedName(), migrated.getString("finale_phase"));
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
                        data.generatedRouteSegment(),
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
        data.advanceDays(CampaignSavedData.FINAL_DAY - 2);

        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
        assertNotNull(data.activeMission());
        assertNull(data.proposedMission());

        assertTrue(data.clearMission());
        assertTrue(data.proposeOptionalMission(MissionType.SALVAGE_CAR));
        data.advanceDays(1);
        assertEquals(
                CampaignSavedData.TickOutcome.FINALE_PHASE_ADVANCED,
                data.tick());
        assertNull(data.proposedMission());
        assertNull(data.activeMission());
        materializeAndReachHub(data);
        assertTrue(data.isFinaleMission(data.activeMission()));
        assertEquals(MissionType.STATION_POWER, data.activeMission().type());
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

    @Test
    void schemaElevenActiveFinalHordeMigratesDirectlyToHoldDawn() {
        CampaignSavedData legacy = new CampaignSavedData();
        assertTrue(legacy.start());
        legacy.advanceDays(CampaignSavedData.FINAL_DAY - 1);
        legacy.tick();
        materializeAndReachHub(legacy);
        finishActiveMission(legacy);
        ActiveMission dawn = legacy.activeMission();
        assertEquals(MissionType.ZOMBIE_BLOCKADE, dawn.type());
        assertTrue(legacy.addMissionProgress(5));

        CompoundTag old = legacy.save(new CompoundTag(), null);
        old.putInt("schema_version", 11);
        old.remove("finale_phase");
        old.remove("finale_hub_materialized");

        CampaignSavedData migrated = CampaignSavedData.load(old, null);

        assertEquals(FinalePhase.HOLD_DAWN, migrated.finalePhase());
        assertTrue(migrated.finaleHubMaterialized());
        assertEquals(5, migrated.activeMission().progress());
        assertEquals(MissionType.ZOMBIE_BLOCKADE, migrated.activeMission().type());
    }

    @Test
    void restartAndHoldDawnResumeTheirExactMissionAfterReload() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        data.advanceDays(CampaignSavedData.FINAL_DAY - 1);
        data.tick();
        materializeAndReachHub(data);
        UUID restartId = data.activeMission().id();

        CampaignSavedData restartLoaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);
        assertEquals(FinalePhase.RESTART, restartLoaded.finalePhase());
        assertEquals(restartId, restartLoaded.activeMission().id());
        assertEquals(MissionType.STATION_POWER, restartLoaded.activeMission().type());

        finishActiveMission(restartLoaded);
        UUID dawnId = restartLoaded.activeMission().id();
        CampaignSavedData dawnLoaded = CampaignSavedData.load(
                restartLoaded.save(new CompoundTag(), null),
                null);
        assertEquals(FinalePhase.HOLD_DAWN, dawnLoaded.finalePhase());
        assertEquals(dawnId, dawnLoaded.activeMission().id());
        assertNotEquals(restartId, dawnId);
        assertEquals(MissionType.ZOMBIE_BLOCKADE, dawnLoaded.activeMission().type());
    }

    private static void finishActiveMission(CampaignSavedData data) {
        ActiveMission mission = data.activeMission();
        assertNotNull(mission);
        assertTrue(data.addMissionProgress(mission.target()));
        assertTrue(data.turnInMission());
    }

    private static void materializeAndReachHub(CampaignSavedData data) {
        int hub = data.finaleHubRouteSegment();
        assertTrue(hub > 0);
        while (data.generatedRouteSegment() < hub) {
            assertTrue(data.markRouteSegmentGenerated(data.generatedRouteSegment() + 1));
        }
        if (!data.finaleHubMaterialized()) {
            assertTrue(data.markFinaleHubMaterialized(hub));
        }
        if (data.routeSegment() < hub) {
            assertTrue(data.advanceRouteTo(hub));
        } else {
            data.tick();
        }
    }
}
