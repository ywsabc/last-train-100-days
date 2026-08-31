package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StarterTrainLayoutTest {
    @Test
    void defaultTrainContainsExactlyThreeFunctionalUnits() {
        assertEquals(3, StarterTrainLayout.units().size());
        assertEquals(
                Set.of(
                        StarterTrainLayout.Unit.POWER_DRIVING,
                        StarterTrainLayout.Unit.WORKSHOP_CARGO,
                        StarterTrainLayout.Unit.LIVING_MEDICAL),
                StarterTrainLayout.units().stream()
                        .map(StarterTrainLayout.UnitDefinition::unit)
                        .collect(java.util.stream.Collectors.toSet()));

        for (StarterTrainLayout.Unit unit : StarterTrainLayout.Unit.values()) {
            long bogeys = StarterTrainLayout.blocks().stream()
                    .filter(block -> block.unit() == unit)
                    .filter(block -> block.blockId().equals("simurail:physics_bogey"))
                    .count();
            assertEquals(2, bogeys, unit.name());
        }
    }

    @Test
    void eachUnitCarriesItsRequiredFunctionAndDistributedSupplies() {
        assertUnitHasBlock(
                StarterTrainLayout.Unit.POWER_DRIVING,
                "simulated:red_portable_engine");
        assertUnitHasBlock(
                StarterTrainLayout.Unit.POWER_DRIVING,
                "simulated:physics_assembler");
        assertUnitHasBlock(
                StarterTrainLayout.Unit.WORKSHOP_CARGO,
                "minecraft:crafting_table");
        assertUnitHasBlock(
                StarterTrainLayout.Unit.WORKSHOP_CARGO,
                "minecraft:furnace");
        assertUnitHasBlock(
                StarterTrainLayout.Unit.LIVING_MEDICAL,
                "minecraft:red_bed");

        assertUnitHasItem(StarterTrainLayout.Unit.POWER_DRIVING, "minecraft:coal");
        assertUnitHasItem(StarterTrainLayout.Unit.WORKSHOP_CARGO, "minecraft:iron_ingot");
        assertUnitHasItem(StarterTrainLayout.Unit.WORKSHOP_CARGO, "minecraft:rail");
        assertUnitHasItem(StarterTrainLayout.Unit.LIVING_MEDICAL, "minecraft:bread");
        assertUnitHasItem(StarterTrainLayout.Unit.LIVING_MEDICAL, "minecraft:golden_apple");
    }

    @Test
    void physicalManifestHasUniqueCellsAndAClearThreeByThreeReconnectDeck() {
        Set<net.minecraft.core.BlockPos> positions = new HashSet<>();
        StarterTrainLayout.blocks().forEach(block -> assertTrue(
                positions.add(block.offset()),
                block.offset().toShortString()));
        assertEquals(StarterTrainLayout.expectedBlockCount(), positions.size());

        for (int x = 4; x <= 6; x++) {
            for (int z = 1; z <= 3; z++) {
                net.minecraft.core.BlockPos floor = new net.minecraft.core.BlockPos(x, 2, z);
                assertTrue(positions.contains(floor), floor.toShortString());
                assertTrue(!positions.contains(floor.above()), floor.above().toShortString());
                assertTrue(!positions.contains(floor.above(2)), floor.above(2).toShortString());
            }
        }
    }

    private static void assertUnitHasBlock(
            StarterTrainLayout.Unit unit,
            String blockId) {
        assertTrue(StarterTrainLayout.blocks().stream()
                .anyMatch(block -> block.unit() == unit && block.blockId().equals(blockId)));
    }

    private static void assertUnitHasItem(
            StarterTrainLayout.Unit unit,
            String itemId) {
        assertTrue(StarterTrainLayout.supplies().stream()
                .anyMatch(supply -> supply.unit() == unit && supply.itemId().equals(itemId)));
    }
}
