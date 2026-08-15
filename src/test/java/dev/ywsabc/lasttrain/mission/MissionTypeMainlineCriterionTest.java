package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignPacingPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The single authoritative mainline criterion: {@link MissionType#blocksRoute()}.
 * Pacing policy, pool constraints and the mission slot all follow it, and
 * supply recovery is a non-blocking support mission that still occupies the
 * slot (and keeps counting for the no-repeat constraint).
 */
class MissionTypeMainlineCriterionTest {
    @Test
    void blocksRouteIsTheSingleAuthoritativeMainlineCriterion() {
        assertTrue(MissionType.RAIL_BREAK.blocksRoute());
        assertTrue(MissionType.STATION_POWER.blocksRoute());
        assertTrue(MissionType.STATION_GATE.blocksRoute());
        assertTrue(MissionType.ZOMBIE_BLOCKADE.blocksRoute());
        assertFalse(MissionType.SUPPLY_RECOVERY.blocksRoute());
        assertFalse(MissionType.RESCUE_SURVIVOR.blocksRoute());
        assertFalse(MissionType.SALVAGE_CAR.blocksRoute());
        for (MissionType type : MissionType.values()) {
            assertEquals(type.category() == MissionType.Category.MAIN, type.blocksRoute());
            assertEquals(type.blocksRoute(), CampaignPacingPolicy.isMainlineMission(type));
        }
    }

    @Test
    void supplyRecoveryIsANonBlockingSupportMissionThatOccupiesTheSlot() {
        assertEquals(MissionType.Category.SUPPORT, MissionType.SUPPLY_RECOVERY.category());
        assertTrue(MissionType.SUPPLY_RECOVERY.occupiesMainlineSlot());
        assertFalse(MissionType.SUPPLY_RECOVERY.blocksRoute());
        assertEquals(MissionType.Category.OPTIONAL, MissionType.RESCUE_SURVIVOR.category());
        assertFalse(MissionType.RESCUE_SURVIVOR.occupiesMainlineSlot());
        assertEquals(MissionType.Category.OPTIONAL, MissionType.SALVAGE_CAR.category());
        assertFalse(MissionType.SALVAGE_CAR.occupiesMainlineSlot());
    }

    @Test
    void supplyRecoveryStillCountsForTheSlotNoRepeatConstraint() {
        List<MissionPoolPolicy.Entry> history = List.of(
                MissionPoolPolicy.entry(MissionType.SUPPLY_RECOVERY, MissionPoolPolicy.Outcome.COMPLETED));
        assertFalse(MissionPoolPolicy.mayCreateMainline(history, MissionType.SUPPLY_RECOVERY));
        assertTrue(MissionPoolPolicy.mayCreateMainline(history, MissionType.RAIL_BREAK));
        assertEquals(
                MissionType.SUPPLY_RECOVERY,
                MissionPoolPolicy.lastMainlineType(history).orElseThrow());
    }

    @Test
    void thePoolStillOffersSupplyRecoveryAsASelectableType() {
        assertTrue(MissionPoolPolicy.MAINLINE_TYPES.contains(MissionType.SUPPLY_RECOVERY));
        assertTrue(MissionPoolPolicy.mayCreateMainline(List.of(), MissionType.SUPPLY_RECOVERY));
        assertTrue(MissionPoolPolicy.isShortOrSupplied(MissionType.SUPPLY_RECOVERY));
    }
}
