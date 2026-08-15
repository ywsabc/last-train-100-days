package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OptionalMissionLayoutTest {
    private static final BlockPos SITE = new BlockPos(100, 64, -20);

    @Test
    void salvageDamageOffsetsAreUniqueAndStable() {
        Set<BlockPos> seen = new HashSet<>();
        for (int index = 0; index < OptionalMissionDirector.SALVAGE_DAMAGE_POINTS; index++) {
            BlockPos offset = OptionalMissionDirector.salvageDamageOffset(index);
            assertTrue(seen.add(offset), "duplicate damage offset " + offset);
            assertEquals(1, offset.getY());
            assertEquals(1, Math.abs(offset.getZ()));
        }
        assertEquals(OptionalMissionDirector.SALVAGE_DAMAGE_POINTS, seen.size());
    }

    @Test
    void damageIndexReverseLookupMatchesEveryOffset() {
        for (int index = 0; index < OptionalMissionDirector.SALVAGE_DAMAGE_POINTS; index++) {
            assertEquals(
                    index,
                    OptionalMissionDirector.salvageDamageIndexAt(
                            SITE,
                            SITE.offset(OptionalMissionDirector.salvageDamageOffset(index))));
        }
        assertEquals(-1, OptionalMissionDirector.salvageDamageIndexAt(SITE, SITE.offset(0, 0, 0)));
        assertEquals(-1, OptionalMissionDirector.salvageDamageIndexAt(SITE, SITE.offset(3, 1, -1)));
        assertEquals(-1, OptionalMissionDirector.salvageDamageIndexAt(SITE, SITE.offset(0, 2, -1)));
    }

    @Test
    void salvageCarBodyIsProtectedWhileActiveAndPrepared() {
        ActiveMission mission = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                5,
                2,
                3_000L);
        mission.transitionTo(MissionStage.ACTIVE);
        mission.assignSite(SITE);
        mission.markWorldPrepared();

        assertTrue(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(0, 0, 0)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(-3, 0, 1)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(-1, 1, -1)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(3, 1, 0)));
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(0, 1, 0)));
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(4, 0, 0)));
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(0, 2, -1)));
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(mission, SITE.offset(0, -1, 0)));
    }

    @Test
    void salvageProtectionRequiresActivePreparedMission() {
        ActiveMission unprepared = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                5,
                2,
                3_000L);
        unprepared.transitionTo(MissionStage.ACTIVE);
        unprepared.assignSite(SITE);
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(unprepared, SITE));

        ActiveMission ready = ActiveMission.createProposal(
                MissionType.SALVAGE_CAR,
                5,
                2,
                3_000L);
        ready.transitionTo(MissionStage.ACTIVE);
        ready.assignSite(SITE);
        ready.markWorldPrepared();
        for (int index = 0; index < ready.target(); index++) {
            ready.recordRepairIndex(index);
        }
        assertEquals(MissionStage.READY_TO_TURN_IN, ready.stage());
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(ready, SITE.offset(0, 0, 0)));
    }

    @Test
    void rescueSurvivorHasNoProtectedBlocks() {
        ActiveMission mission = ActiveMission.createProposal(
                MissionType.RESCUE_SURVIVOR,
                5,
                2,
                3_000L);
        mission.transitionTo(MissionStage.ACTIVE);
        mission.assignSite(SITE);
        mission.markWorldPrepared();
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(mission, SITE));
    }

    @Test
    void survivorTagsAreUniquePerMissionAndNeverCollideWithZombieTags() {
        ActiveMission first = ActiveMission.createProposal(
                MissionType.RESCUE_SURVIVOR,
                5,
                2,
                3_000L);
        ActiveMission second = ActiveMission.createProposal(
                MissionType.RESCUE_SURVIVOR,
                5,
                2,
                3_000L);

        String firstTag = MissionWorldDirector.survivorEntityTag(first);
        String secondTag = MissionWorldDirector.survivorEntityTag(second);
        assertTrue(MissionWorldDirector.isSurvivorEntityTag(firstTag));
        assertFalse(firstTag.equals(secondTag));
        assertTrue(firstTag.contains(first.id().toString()));
        // The zombie tag (plain mission prefix) is not a survivor tag, so
        // zombie reconciliation never discards survivors and vice versa.
        assertFalse(MissionWorldDirector.isSurvivorEntityTag(
                MissionWorldDirector.missionEntityTag(first)));
    }
}
