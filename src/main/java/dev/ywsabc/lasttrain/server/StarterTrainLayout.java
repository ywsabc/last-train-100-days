package dev.ywsabc.lasttrain.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * 三节默认列车的单一布局清单。
 *
 * <p>物理装配器和单元测试读取同一份方块/补给定义，避免“测试说三节、世界仍
 * 放单节”的漂移。坐标均相对起点站锚点。</p>
 */
public final class StarterTrainLayout {
    private static final List<UnitDefinition> UNITS = List.of(
            new UnitDefinition(Unit.POWER_DRIVING, -10, -6),
            new UnitDefinition(Unit.WORKSHOP_CARGO, -4, 0),
            new UnitDefinition(Unit.LIVING_MEDICAL, 2, 6));
    private static final List<BlockSpec> BLOCKS = buildBlocks();
    private static final List<SupplySpec> SUPPLIES = List.of(
            supply(Unit.POWER_DRIVING, -9, 3, 1, 0, "minecraft:coal", 32),
            supply(Unit.POWER_DRIVING, -9, 3, 1, 1, "minecraft:redstone", 16),
            supply(Unit.WORKSHOP_CARGO, -3, 3, 1, 0, "minecraft:iron_ingot", 24),
            supply(Unit.WORKSHOP_CARGO, -3, 3, 1, 1, "minecraft:copper_ingot", 16),
            supply(Unit.WORKSHOP_CARGO, -3, 3, 1, 2, "minecraft:rail", 24),
            supply(Unit.WORKSHOP_CARGO, -3, 3, 1, 3, "minecraft:iron_pickaxe", 1),
            supply(Unit.LIVING_MEDICAL, 2, 3, 1, 0, "minecraft:bread", 24),
            supply(Unit.LIVING_MEDICAL, 2, 3, 1, 1, "minecraft:baked_potato", 24),
            supply(Unit.LIVING_MEDICAL, 6, 3, -1, 0, "minecraft:golden_apple", 3),
            supply(Unit.LIVING_MEDICAL, 6, 3, -1, 1, "minecraft:honey_bottle", 6));

    private StarterTrainLayout() {
    }

    public static List<UnitDefinition> units() {
        return UNITS;
    }

    public static List<BlockSpec> blocks() {
        return BLOCKS;
    }

    public static List<SupplySpec> supplies() {
        return SUPPLIES;
    }

    public static int expectedBlockCount() {
        return BLOCKS.size();
    }

    public static BlockPos assemblerOffset() {
        return new BlockPos(-6, 3, 0);
    }

    public static BlockPos gatheringPointOffset() {
        return new BlockPos(5, 3, 2);
    }

    public static BlockPos glueMinOffset() {
        return new BlockPos(-10, 2, -1);
    }

    public static BlockPos glueMaxOffset() {
        return new BlockPos(6, 3, 3);
    }

    private static List<BlockSpec> buildBlocks() {
        List<BlockSpec> blocks = new ArrayList<>();
        for (UnitDefinition unit : UNITS) {
            for (int x = unit.minX(); x <= unit.maxX(); x++) {
                blocks.add(block(unit.unit(), x, 2, -1, "minecraft:oak_planks"));
                blocks.add(block(unit.unit(), x, 2, 1, "minecraft:oak_planks"));
                if (x == unit.minX() || x == unit.maxX()) {
                    blocks.add(block(
                            unit.unit(),
                            x,
                            2,
                            0,
                            "simurail:physics_bogey",
                            "facing", "east",
                            "inverted", "false",
                            "waterlogged", "false"));
                } else {
                    blocks.add(block(unit.unit(), x, 2, 0, "minecraft:oak_planks"));
                }
            }
        }

        // 两个窄连接台只负责把三个功能单元稳定纳入同一物理装配体。
        blocks.add(block(Unit.POWER_DRIVING, -5, 2, 0, "minecraft:oak_planks"));
        blocks.add(block(Unit.WORKSHOP_CARGO, 1, 2, 0, "minecraft:oak_planks"));

        blocks.add(block(
                Unit.POWER_DRIVING,
                -8, 3, 0,
                "simulated:red_portable_engine",
                "facing", "west",
                "lit", "false"));
        blocks.add(block(
                Unit.POWER_DRIVING,
                -6, 3, 0,
                "simulated:physics_assembler",
                "face", "floor",
                "facing", "east"));
        for (int z = -1; z <= 1; z++) {
            blocks.add(block(
                    Unit.POWER_DRIVING,
                    -10, 3, z,
                    "minecraft:lever",
                    "face", "floor",
                    "facing", "east",
                    "powered", "false"));
        }
        blocks.add(block(Unit.POWER_DRIVING, -9, 3, 1, "minecraft:barrel"));

        blocks.add(block(Unit.WORKSHOP_CARGO, -4, 3, -1, "minecraft:crafting_table"));
        blocks.add(block(Unit.WORKSHOP_CARGO, -2, 3, -1, "minecraft:furnace"));
        blocks.add(block(Unit.WORKSHOP_CARGO, -3, 3, 1, "minecraft:barrel"));
        blocks.add(block(Unit.WORKSHOP_CARGO, -1, 3, 1, "minecraft:anvil"));

        blocks.add(block(
                Unit.LIVING_MEDICAL,
                3, 3, -1,
                "minecraft:red_bed",
                "facing", "east",
                "part", "foot",
                "occupied", "false"));
        blocks.add(block(
                Unit.LIVING_MEDICAL,
                4, 3, -1,
                "minecraft:red_bed",
                "facing", "east",
                "part", "head",
                "occupied", "false"));
        blocks.add(block(Unit.LIVING_MEDICAL, 2, 3, 1, "minecraft:barrel"));
        blocks.add(block(Unit.LIVING_MEDICAL, 6, 3, -1, "minecraft:barrel"));

        // 生活车尾保留 3x3、上方两格净空的安全重连甲板。
        for (int x = 4; x <= 6; x++) {
            for (int z = 1; z <= 3; z++) {
                BlockSpec deck = block(Unit.LIVING_MEDICAL, x, 2, z, "minecraft:oak_planks");
                if (blocks.stream().noneMatch(existing -> existing.offset().equals(deck.offset()))) {
                    blocks.add(deck);
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static BlockSpec block(
            Unit unit,
            int x,
            int y,
            int z,
            String blockId,
            String... propertyPairs) {
        if ((propertyPairs.length & 1) != 0) {
            throw new IllegalArgumentException("方块属性必须成对出现");
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (int index = 0; index < propertyPairs.length; index += 2) {
            properties.put(propertyPairs[index], propertyPairs[index + 1]);
        }
        return new BlockSpec(unit, new BlockPos(x, y, z), blockId, properties);
    }

    private static SupplySpec supply(
            Unit unit,
            int x,
            int y,
            int z,
            int slot,
            String itemId,
            int count) {
        return new SupplySpec(unit, new BlockPos(x, y, z), slot, itemId, count);
    }

    public enum Unit {
        POWER_DRIVING,
        WORKSHOP_CARGO,
        LIVING_MEDICAL
    }

    public record UnitDefinition(Unit unit, int minX, int maxX) {
        public UnitDefinition {
            Objects.requireNonNull(unit, "unit");
            if (maxX < minX) {
                throw new IllegalArgumentException("车厢坐标范围不能为空");
            }
        }
    }

    public record BlockSpec(
            Unit unit,
            BlockPos offset,
            String blockId,
            Map<String, String> properties) {
        public BlockSpec {
            Objects.requireNonNull(unit, "unit");
            offset = Objects.requireNonNull(offset, "offset").immutable();
            blockId = Objects.requireNonNull(blockId, "blockId");
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        }
    }

    public record SupplySpec(
            Unit unit,
            BlockPos containerOffset,
            int slot,
            String itemId,
            int count) {
        public SupplySpec {
            Objects.requireNonNull(unit, "unit");
            containerOffset = Objects.requireNonNull(containerOffset, "containerOffset").immutable();
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (slot < 0 || count < 1) {
                throw new IllegalArgumentException("补给槽位和数量必须有效");
            }
        }
    }
}
