package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class MissionBlockProtectionTest {
    private static final BlockPos SITE = new BlockPos(100, 64, -20);

    @Test
    void protectsRepairedControlsBarriersAndGateSupports() {
        ActiveMission power = prepared(MissionType.STATION_POWER);
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(power, SITE.offset(-3, -1, 4)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(power, SITE.offset(1, 0, 4)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(power, SITE.offset(3, -1, 5)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(power, SITE.offset(0, 2, -2)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(power, SITE.below()));
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(power, SITE.offset(4, -1, 4)));

        ActiveMission gate = prepared(MissionType.STATION_GATE);
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(gate, SITE.offset(-1, 0, 4)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(gate, SITE.offset(1, 1, 4)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(gate, SITE.offset(-1, 0, 3)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(gate, SITE.offset(1, -1, 4)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(gate, SITE.offset(1, -1, 3)));
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(gate, SITE.offset(1, 1, 3)));

        ActiveMission supplies = prepared(MissionType.SUPPLY_RECOVERY);
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(supplies, SITE.offset(-2, 0, 4)));
        assertTrue(MissionWorldDirector.isProtectedMissionBlock(supplies, SITE.offset(2, 0, 4)));
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(supplies, SITE.offset(3, 0, 4)));
    }

    @Test
    void protectionExistsOnlyWhileARelevantPreparedMissionIsActive() {
        ActiveMission unprepared = ActiveMission.create(MissionType.STATION_POWER, 1, 1);
        unprepared.assignSite(SITE);
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(unprepared, SITE));

        ActiveMission ready = prepared(MissionType.STATION_POWER);
        while (ready.stage() == MissionStage.ACTIVE) {
            ready.setObservedProgress(ready.target());
        }
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(ready, SITE));

        ActiveMission rail = prepared(MissionType.RAIL_BREAK);
        assertFalse(MissionWorldDirector.isProtectedMissionBlock(rail, SITE));
    }

    @Test
    void detectsProtectedBlocksInPistonMoveAndDestroyLists() {
        ActiveMission supplies = prepared(MissionType.SUPPLY_RECOVERY);
        BlockPos ordinary = SITE.offset(8, 0, 0);
        BlockPos protectedBarrel = SITE.offset(1, 0, 4);

        assertFalse(MissionWorldDirector.containsProtectedMissionBlock(
                supplies,
                List.of(ordinary)));
        assertTrue(MissionWorldDirector.containsProtectedMissionBlock(
                supplies,
                List.of(ordinary, protectedBarrel)));
        assertTrue(MissionWorldDirector.movesIntoProtectedMissionBlock(
                supplies,
                List.of(protectedBarrel.relative(Direction.WEST)),
                Direction.EAST));
        assertFalse(MissionWorldDirector.movesIntoProtectedMissionBlock(
                supplies,
                List.of(ordinary),
                Direction.EAST));
    }

    @Test
    void identifiesOnlyRegeneratedMissionCoordinatesAndBlockKinds() {
        ActiveMission power = prepared(MissionType.STATION_POWER);
        assertEquals(
                MissionWorldDirector.RegeneratedMissionDrop.LEVER,
                MissionWorldDirector.regeneratedMissionDropAt(
                        power,
                        SITE.offset(-3, 0, 4)));
        assertEquals(
                MissionWorldDirector.RegeneratedMissionDrop.IRON_BARS,
                MissionWorldDirector.regeneratedMissionDropAt(
                        power,
                        SITE.offset(0, 2, 1)));
        assertEquals(
                MissionWorldDirector.RegeneratedMissionDrop.NONE,
                MissionWorldDirector.regeneratedMissionDropAt(
                        power,
                        SITE.offset(-3, 1, 4)));

        ActiveMission gate = prepared(MissionType.STATION_GATE);
        assertEquals(
                MissionWorldDirector.RegeneratedMissionDrop.IRON_DOOR,
                MissionWorldDirector.regeneratedMissionDropAt(
                        gate,
                        SITE.offset(1, 1, 4)));
        assertEquals(
                MissionWorldDirector.RegeneratedMissionDrop.NONE,
                MissionWorldDirector.regeneratedMissionDropAt(
                        gate,
                        SITE.offset(1, -1, 3)));

        ActiveMission supplies = prepared(MissionType.SUPPLY_RECOVERY);
        assertEquals(
                MissionWorldDirector.RegeneratedMissionDrop.BARREL,
                MissionWorldDirector.regeneratedMissionDropAt(
                        supplies,
                        SITE.offset(2, 0, 4)));
        assertEquals(
                MissionWorldDirector.RegeneratedMissionDrop.NONE,
                MissionWorldDirector.regeneratedMissionDropAt(
                        supplies,
                        SITE.offset(0, 2, 1)));
    }

    private static ActiveMission prepared(MissionType type) {
        ActiveMission mission = ActiveMission.create(type, 1, 1);
        mission.assignSite(SITE);
        mission.markWorldPrepared();
        return mission;
    }
}
