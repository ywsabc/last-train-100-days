package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MissionWorldDirectorScalingLayoutTest {
    @Test
    void defaultTargetsPreserveTheLegacyObjectiveLayouts() {
        assertArrayEquals(
                new int[]{-1, 0, 1},
                MissionWorldDirector.objectiveXOffsets(MissionType.RAIL_BREAK, 3));
        assertArrayEquals(
                new int[]{-3, -1, 1, 3},
                MissionWorldDirector.objectiveXOffsets(MissionType.STATION_POWER, 4));
        assertArrayEquals(
                new int[]{-1, 1},
                MissionWorldDirector.objectiveXOffsets(MissionType.STATION_GATE, 2));
        assertArrayEquals(
                new int[]{-2, -1, 0, 1, 2},
                MissionWorldDirector.objectiveXOffsets(MissionType.SUPPLY_RECOVERY, 5));
        assertArrayEquals(
                new int[0],
                MissionWorldDirector.objectiveXOffsets(MissionType.ZOMBIE_BLOCKADE, 12));
    }

    @Test
    void scaledTargetsCreateUniqueDeterministicOffsets() {
        for (MissionType type : MissionType.values()) {
            if (type == MissionType.ZOMBIE_BLOCKADE) {
                continue;
            }
            for (int target = 1; target <= 20; target++) {
                int[] offsets = MissionWorldDirector.objectiveXOffsets(type, target);
                assertEquals(target, offsets.length);
                Set<Integer> unique = new HashSet<>();
                for (int offset : offsets) {
                    assertTrue(unique.add(offset), "duplicate offset " + offset);
                }
            }
        }
    }

    @Test
    void railAndSupplyObjectivesStayContiguousAroundTheSite() {
        for (int target = 1; target <= 20; target++) {
            int[] rail = MissionWorldDirector.objectiveXOffsets(MissionType.RAIL_BREAK, target);
            int[] supply = MissionWorldDirector.objectiveXOffsets(
                    MissionType.SUPPLY_RECOVERY,
                    target);
            assertContiguous(rail);
            assertContiguous(supply);
        }
    }

    private static void assertContiguous(int[] offsets) {
        for (int index = 1; index < offsets.length; index++) {
            assertEquals(1, offsets[index] - offsets[index - 1]);
        }
    }
}
