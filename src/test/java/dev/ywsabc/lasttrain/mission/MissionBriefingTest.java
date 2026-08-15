package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MissionBriefingTest {
    @Test
    void rescueSurvivorAdvertisesMediumRiskSuppliesAndThreeDays() {
        MissionBriefing briefing =
                MissionBriefing.of(MissionType.RESCUE_SURVIVOR).orElseThrow();

        assertEquals(MissionType.RESCUE_SURVIVOR, briefing.type());
        assertEquals(MissionBriefing.Risk.MEDIUM, briefing.risk());
        assertEquals(MissionBriefing.RewardCategory.SUPPLIES, briefing.reward());
        assertEquals(3, briefing.timedDays());
    }

    @Test
    void salvageCarAdvertisesHighRiskUpgradeAndSevenDays() {
        MissionBriefing briefing =
                MissionBriefing.of(MissionType.SALVAGE_CAR).orElseThrow();

        assertEquals(MissionType.SALVAGE_CAR, briefing.type());
        assertEquals(MissionBriefing.Risk.HIGH, briefing.risk());
        assertEquals(MissionBriefing.RewardCategory.UPGRADE, briefing.reward());
        assertEquals(7, briefing.timedDays());
    }

    @Test
    void mainlineMissionsHaveNoOptionalBriefing() {
        for (MissionType type : MissionType.values()) {
            if (type.category() == MissionType.Category.MAIN) {
                assertTrue(MissionBriefing.of(type).isEmpty());
            }
        }
    }
}
