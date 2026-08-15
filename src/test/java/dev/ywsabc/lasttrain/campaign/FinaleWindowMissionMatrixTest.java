package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Final-window mission matrix (days 90-99) and the day-100 finale clearing.
 *
 * <p>Days 90-99 only accept non-blocking support missions in the single slot
 * (and optional proposals beside it); no route-blocking mission may start.
 * The day-100 clearing of a leftover non-blocking mission registers a
 * deferred site cleanup before the mission record is dropped.</p>
 */
class FinaleWindowMissionMatrixTest {
    private static CampaignSavedData atDay(int day) {
        CampaignSavedData data = new CampaignSavedData();
        data.start();
        data.advanceDays(day - 1);
        data.clearMission();
        if (data.proposedMission() != null) {
            data.rejectProposal(null, null);
        }
        data.settleOptionalTimeouts();
        return data;
    }

    @Test
    void finalWindowMatrixAllowsOnlyNonBlockingSlotMissions() {
        for (int day : new int[]{90, 95, 99}) {
            for (MissionType type : MissionType.values()) {
                CampaignSavedData data = atDay(day);
                if (type.occupiesMainlineSlot()) {
                    assertEquals(
                            !type.blocksRoute(),
                            data.createMission(type),
                            type + " at day " + day);
                    data.clearMission();
                } else {
                    assertFalse(data.createMission(type), type + " at day " + day);
                    assertTrue(
                            data.proposeOptionalMission(type),
                            type + " proposal at day " + day);
                    data.rejectProposal(null, null);
                }
            }
        }
    }

    @Test
    void beforeTheFinalWindowEverySlotMissionIsCreatable() {
        CampaignSavedData data = atDay(60);
        for (MissionType type : MissionType.values()) {
            if (type.occupiesMainlineSlot()) {
                assertTrue(data.createMission(type), type.name());
                data.clearMission();
            }
        }
    }

    @Test
    void zombieBlockadesAreBlockingAndRefusedInTheFinalWindow() {
        CampaignSavedData data = atDay(95);
        assertTrue(MissionType.ZOMBIE_BLOCKADE.blocksRoute());
        assertFalse(data.createMission(MissionType.ZOMBIE_BLOCKADE));
        // The siege pressure path falls into the same rejection: no active
        // mission appears and no outcome is recorded.
        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
        assertTrue(data.activeMission() == null);
    }

    @Test
    void day100ClearingRegistersDeferredCleanupBeforeDroppingTheMission() {
        CampaignSavedData data = atDay(99);
        assertTrue(data.createMission(MissionType.SUPPLY_RECOVERY));
        BlockPos site = new BlockPos(123, 64, 456);
        assertTrue(data.assignMissionSite(site));
        UUID missionId = data.activeMission().id();

        data.advanceDays(1);
        assertEquals(CampaignSavedData.FINAL_DAY, data.day());
        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());

        assertTrue(data.isFinaleMission(data.activeMission()));
        assertEquals(
                1,
                data.pendingSiteCleanups().stream()
                        .filter(cleanup -> cleanup.missionId().equals(missionId))
                        .count());
        assertEquals(
                site,
                data.pendingSiteCleanups().stream()
                        .filter(cleanup -> cleanup.missionId().equals(missionId))
                        .findFirst()
                        .orElseThrow()
                        .site());
    }

    @Test
    void day100ClearingWithoutASiteQueuesNoCleanup() {
        CampaignSavedData data = atDay(99);
        assertTrue(data.createMission(MissionType.SUPPLY_RECOVERY));
        data.advanceDays(1);
        assertEquals(CampaignSavedData.TickOutcome.FINALE_MISSION_STARTED, data.tick());
        assertTrue(data.isFinaleMission(data.activeMission()));
        assertTrue(data.pendingSiteCleanups().isEmpty());
    }

    @Test
    void day100StillWaitsForARouteBlockingMissionInsteadOfClearingIt() {
        CampaignSavedData data = atDay(60);
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        BlockPos site = new BlockPos(200, 64, 300);
        assertTrue(data.assignMissionSite(site));
        UUID ordinaryId = data.activeMission().id();

        data.advanceDays(CampaignSavedData.FINAL_DAY - 60);
        assertEquals(CampaignSavedData.TickOutcome.NONE, data.tick());
        assertEquals(ordinaryId, data.activeMission().id());
        assertTrue(data.pendingSiteCleanups().isEmpty());
    }

    @Test
    void supplyRecoveryNoLongerBlocksTheRouteAnywhere() {
        ActiveMission supplies = ActiveMission.create(MissionType.SUPPLY_RECOVERY, 30, 12);
        assertFalse(supplies.type().blocksRoute());
        assertEquals(MissionType.Category.SUPPORT, supplies.type().category());
    }
}
