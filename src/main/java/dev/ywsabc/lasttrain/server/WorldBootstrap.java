package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Small vanilla-safe station used as an idempotent hand-off point for the
 * TongDa/Create vehicle adapters. No third-party block IDs are written here,
 * so a beta dependency cannot corrupt the campaign bootstrap.
 */
public final class WorldBootstrap {
    private static final int UPDATE_ALL = 3;
    private static final String PUBLIC_SUPPLY_OPERATION_KEY =
            "lasttrain_public_supply_operation";
    private static final String PUBLIC_SUPPLY_VERSION = "starter_station_v1";

    private WorldBootstrap() {
    }

    public static void ensureStarterStation(ServerLevel level, CampaignSavedData data) {
        if (data.starterStationBuilt()) {
            migrateLegacyCanopyClearance(level, data.starterStationAnchor());
        } else {
            BlockPos worldSpawn = level.getSharedSpawnPos();
            int centerX = worldSpawn.getX();
            int centerZ = worldSpawn.getZ();
            int deckY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    centerX,
                    centerZ);
            BlockPos anchor = new BlockPos(centerX, deckY, centerZ);

            clearHeadroom(level, anchor);
            buildDeck(level, anchor);
            buildCanopy(level, anchor);
            placeUtilities(level, anchor);

            BlockPos safeSpawn = anchor.offset(-7, 1, 3);
            level.setDefaultSpawnPos(safeSpawn, 0.0F);
            data.markStarterStationBuilt(anchor);
            LastTrain.LOGGER.info("Starter station bootstrapped at {}", anchor);
        }

        ensurePublicSupply(level, data);
    }

    private static void clearHeadroom(ServerLevel level, BlockPos anchor) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = 1; y <= 5; y++) {
                    level.setBlock(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                }
            }
        }
    }

    private static void buildDeck(ServerLevel level, BlockPos anchor) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -4; z <= 4; z++) {
                level.setBlock(
                        anchor.offset(x, 0, z),
                        (Math.abs(z) == 1 ? Blocks.YELLOW_CONCRETE : Blocks.POLISHED_ANDESITE)
                                .defaultBlockState(),
                        UPDATE_ALL);
            }
        }

        for (int x = -10; x <= 10; x++) {
            level.setBlock(
                    anchor.offset(x, 1, 0),
                    Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST),
                    UPDATE_ALL);
        }
    }

    private static void buildCanopy(ServerLevel level, BlockPos anchor) {
        for (int x : new int[]{-9, -3, 3, 9}) {
            for (int z : new int[]{-4, 4}) {
                for (int y = 1; y <= 4; y++) {
                    level.setBlock(
                            anchor.offset(x, y, z),
                            Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(),
                            UPDATE_ALL);
                }
            }
        }

        for (int x = -10; x <= 10; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(z) >= 2) {
                    level.setBlock(
                            anchor.offset(x, 5, z),
                            Blocks.DARK_OAK_SLAB.defaultBlockState(),
                            UPDATE_ALL);
                }
            }
        }

        for (int x : new int[]{-6, 0, 6}) {
            for (int z : new int[]{-4, 4}) {
                level.setBlock(
                        anchor.offset(x, 4, z),
                        Blocks.LANTERN.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
    }

    /**
     * Moves canopy details created by the original prototype one block away
     * from the track. The Simurail gathering deck reaches z=3, so leaving the
     * old posts there would both block initial layout validation and collide
     * with the assembled body as it departed. Only exact bootstrap blocks are
     * touched; player-built replacements remain an explicit obstruction.
     */
    private static void migrateLegacyCanopyClearance(ServerLevel level, BlockPos anchor) {
        for (int x : new int[]{-9, -3, 3, 9}) {
            for (int oldZ : new int[]{-3, 3}) {
                int newZ = oldZ < 0 ? -4 : 4;
                for (int y = 1; y <= 4; y++) {
                    BlockPos oldPos = anchor.offset(x, y, oldZ);
                    if (!level.getBlockState(oldPos).is(Blocks.STRIPPED_SPRUCE_LOG)) {
                        continue;
                    }
                    BlockPos newPos = anchor.offset(x, y, newZ);
                    if (level.getBlockState(newPos).isAir()) {
                        level.setBlock(newPos, level.getBlockState(oldPos), UPDATE_ALL);
                    }
                    level.setBlock(oldPos, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                }
            }
        }

        for (int x : new int[]{-6, 0, 6}) {
            for (int oldZ : new int[]{-3, 3}) {
                BlockPos oldPos = anchor.offset(x, 4, oldZ);
                if (!level.getBlockState(oldPos).is(Blocks.LANTERN)) {
                    continue;
                }
                int newZ = oldZ < 0 ? -4 : 4;
                BlockPos newPos = anchor.offset(x, 4, newZ);
                if (level.getBlockState(newPos).isAir()) {
                    level.setBlock(newPos, level.getBlockState(oldPos), UPDATE_ALL);
                }
                level.setBlock(oldPos, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
            }
        }
    }

    private static void placeUtilities(ServerLevel level, BlockPos anchor) {
        level.setBlock(anchor.offset(-7, 1, 2), Blocks.CHEST.defaultBlockState(), UPDATE_ALL);
        level.setBlock(anchor.offset(-5, 1, 2), Blocks.CRAFTING_TABLE.defaultBlockState(), UPDATE_ALL);
        level.setBlock(anchor.offset(-4, 1, 2), Blocks.FURNACE.defaultBlockState(), UPDATE_ALL);
        // Reserve a clearly marked two-block sleeping area. A real bed is not
        // placed here because a half-bed is invalid and fixed-facing beds can
        // intersect the terrain in generated city starts.
        level.setBlock(anchor.offset(-2, 1, 2), Blocks.RED_WOOL.defaultBlockState(), UPDATE_ALL);
        level.setBlock(anchor.offset(-1, 1, 2), Blocks.RED_WOOL.defaultBlockState(), UPDATE_ALL);
    }

    /**
     * 公共补给事务：先持久化意图，再写箱体内容与操作标记，最后提交 SavedData。
     * 若崩溃发生在两份存储之间，重启只补缺少的半边，不会再次灌入已领取物资。
     */
    private static void ensurePublicSupply(ServerLevel level, CampaignSavedData data) {
        if (data.starterPublicSupplyCommitted()) {
            return;
        }
        data.beginStarterPublicSupply();
        BlockPos chestPos = data.starterStationAnchor().offset(-7, 1, 2);
        if (!level.getBlockState(chestPos).is(Blocks.CHEST)) {
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), UPDATE_ALL);
        }
        BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof Container chest)) {
            LastTrain.LOGGER.warn("Starter supply chest was not available at {}", chestPos);
            return;
        }
        String operation = publicSupplyOperation(data);
        boolean markerMatches = operation.equals(
                blockEntity.getPersistentData().getString(PUBLIC_SUPPLY_OPERATION_KEY));
        BootstrapTransactionPolicy.SupplyDirective directive =
                BootstrapTransactionPolicy.supplyDirective(
                        data.starterPublicSupplyPhase(),
                        markerMatches);
        if (directive == BootstrapTransactionPolicy.SupplyDirective.WRITE_AND_COMMIT) {
            fillSupplyChest(chest, blockEntity, operation);
        }
        if (directive != BootstrapTransactionPolicy.SupplyDirective.NONE) {
            data.commitStarterPublicSupply();
        }
    }

    static String publicSupplyOperation(CampaignSavedData data) {
        return data.campaignId() + ":" + PUBLIC_SUPPLY_VERSION;
    }

    private static void fillSupplyChest(
            Container chest,
            BlockEntity blockEntity,
            String operation) {
        chest.clearContent();
        put(chest, 0, Items.BREAD, 32);
        put(chest, 1, Items.BAKED_POTATO, 32);
        put(chest, 2, Items.COAL, 32);
        put(chest, 3, Items.IRON_INGOT, 24);
        put(chest, 4, Items.COPPER_INGOT, 24);
        put(chest, 5, Items.REDSTONE, 24);
        put(chest, 6, Items.TORCH, 64);
        put(chest, 7, Items.RAIL, 32);
        put(chest, 8, Items.POWERED_RAIL, 12);
        put(chest, 9, Items.IRON_PICKAXE, 1);
        put(chest, 10, Items.IRON_AXE, 1);
        put(chest, 11, Items.IRON_SHOVEL, 1);
        put(chest, 12, Items.SHIELD, 2);
        put(chest, 13, Items.WATER_BUCKET, 2);
        put(chest, 14, Items.GOLDEN_APPLE, 2);
        putOptional(chest, 15, "create:track", 48);
        blockEntity.getPersistentData().putString(PUBLIC_SUPPLY_OPERATION_KEY, operation);
        blockEntity.setChanged();
    }

    private static void put(Container container, int slot, net.minecraft.world.item.Item item, int count) {
        container.setItem(slot, new ItemStack(item, count));
    }

    private static void putOptional(Container container, int slot, String id, int count) {
        ResourceLocation key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            return;
        }
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(key);
        if (item != Items.AIR) {
            put(container, slot, item, count);
        }
    }
}
