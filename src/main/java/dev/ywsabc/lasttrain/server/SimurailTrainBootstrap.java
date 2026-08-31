package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * 按三节功能布局放置默认列车，并调用 Create: Simulated 装配为 Sable 子世界。
 *
 * <p>Simurail is an unreleased alpha dependency. The integration therefore
 * uses registry IDs and narrow reflected public surfaces instead of linking
 * the campaign core to alpha implementation classes. A future Simurail build
 * can fail with a precise compatibility message without making the campaign
 * save itself unloadable.</p>
 */
public final class SimurailTrainBootstrap {
    private static final int UPDATE_ALL = 3;
    private static final int NORMAL_RETRY_TICKS = 40;
    private static final int SLOW_RETRY_TICKS = 200;
    private static final int SLOW_RETRY_AFTER = 30;
    private static final int STARTER_VEHICLE_BLOCKS = StarterTrainLayout.expectedBlockCount();
    private static final String TRAIN_SUPPLY_OPERATION_KEY =
            "lasttrain_train_supply_operation";
    private static final String TRAIN_SUPPLY_VERSION = "starter_train_v1";
    private static final String ASSEMBLER_CLASS =
            "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity";
    private static final String SUPER_GLUE_CLASS =
            "com.simibubi.create.content.contraptions.glue.SuperGlueEntity";

    private SimurailTrainBootstrap() {
    }

    public static void ensureLayout(ServerLevel level, CampaignSavedData data) {
        if (data.starterTrainAssembled()) {
            if (data.starterTrainSublevelId() == null) {
                Optional<UUID> recovered = SableTrainTracker.findTaggedStarterTrain(
                        level,
                        data.campaignId());
                if (recovered.isPresent()) {
                    SableTrainTracker.ensureForceLoaded(level, recovered.orElseThrow());
                    data.markStarterTrainAssembled(recovered.orElseThrow());
                    LastTrain.LOGGER.info(
                            "Recovered persisted starter train identity {} from its Sable tag",
                            recovered.orElseThrow());
                } else {
                    LastTrain.LOGGER.error(
                            "The campaign says the starter train is assembled but has no saved Sable UUID. "
                                    + "Automatic recovery refuses to claim an unrelated aircraft or vehicle");
                }
            }
            if (data.starterTrainSublevelId() != null) {
                SableTrainTracker.ensureForceLoaded(level, data.starterTrainSublevelId());
            }
            return;
        }
        if (!hasVehicleStack()) {
            LastTrain.LOGGER.error(
                    "The starter vehicle requires the unreleased Create Simurail alpha "
                            + "together with Create, Simulated, Sable and Create Aeronautics");
            return;
        }

        BlockPos anchor = data.starterStationAnchor();
        if (!requiredBlocksPresent()) {
            LastTrain.LOGGER.error(
                    "Create Simurail is loaded but its alpha block set does not match commit e68481d");
            return;
        }

        Optional<UUID> taggedTrain = SableTrainTracker.findTaggedStarterTrain(
                level,
                data.campaignId());
        boolean completeSource = isExpectedVehicleLayout(level, anchor);
        BootstrapTransactionPolicy.AssemblyDirective directive =
                BootstrapTransactionPolicy.assemblyDirective(
                        data.starterTrainAssemblyPhase(),
                        completeSource,
                        taggedTrain.isPresent());
        try {
            switch (directive) {
                case RECOVER_TRAIN -> {
                    UUID recovered = taggedTrain.orElseThrow();
                    SableTrainTracker.ensureForceLoaded(level, recovered);
                    data.markStarterTrainAssembled(recovered);
                    LastTrain.LOGGER.info(
                            "Recovered starter Simurail train {} from its campaign tag",
                            recovered);
                }
                case RESUME_ASSEMBLY -> {
                    if (!ensureTrainSupplies(level, data, anchor)) {
                        return;
                    }
                    ensureAssemblyGlue(level, anchor);
                    data.markStarterTrainPlaced();
                }
                case PLACE_LAYOUT -> {
                    placeTrackBed(level, anchor);
                    placeStarterTrain(level, anchor);
                    if (!ensureTrainSupplies(level, data, anchor)) {
                        return;
                    }
                    ensureAssemblyGlue(level, anchor);
                    data.markStarterTrainPlaced();
                    LastTrain.LOGGER.info(
                            "Placed and glued the three-unit Create Simurail starter train at {}; "
                                    + "waiting for Create track and Sable physics initialization",
                            anchor);
                }
                case WAIT_FOR_RECOVERY -> LastTrain.LOGGER.error(
                        "Starter train transaction {} has neither a complete source layout nor "
                                + "a tagged Sable train; refusing to place a duplicate",
                        data.starterTrainAssemblyPhase());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LastTrain.LOGGER.error(
                    "Could not prepare the Create Simurail starter vehicle with the "
                            + "required Create super-glue compatibility API",
                    unwrap(exception));
        }
    }

    public static void tick(ServerLevel level, CampaignSavedData data, int serverTick) {
        if (data.starterTrainAssembled()) {
            if (data.starterTrainSublevelId() != null
                    && serverTick % SLOW_RETRY_TICKS == 0) {
                SableTrainTracker.ensureForceLoaded(level, data.starterTrainSublevelId());
            }
            return;
        }
        if (!data.starterTrainPlaced() || !hasVehicleStack()) {
            return;
        }

        int interval = data.starterTrainAssemblyAttempts() < SLOW_RETRY_AFTER
                ? NORMAL_RETRY_TICKS
                : SLOW_RETRY_TICKS;
        if (serverTick % interval != 0) {
            return;
        }

        BlockPos anchor = data.starterStationAnchor();
        int sourceBlocks = sourceVehicleBlockCount(level, anchor);
        data.markStarterTrainAssemblyRequested();
        int attempt = data.recordStarterTrainAssemblyAttempt();
        if (sourceBlocks == 0) {
            Optional<UUID> recovered = SableTrainTracker.findTaggedStarterTrain(
                    level,
                    data.campaignId());
            if (recovered.isPresent()) {
                SableTrainTracker.ensureForceLoaded(level, recovered.orElseThrow());
                data.markStarterTrainAssembled(recovered.orElseThrow());
                LastTrain.LOGGER.info(
                        "Recovered active starter Simurail train {} from its campaign tag",
                        recovered.orElseThrow());
            } else {
                logWaiting(
                        attempt,
                        "the source footprint is absent and no Sable sublevel carries this campaign tag");
            }
            return;
        }

        if (!isExpectedVehicleLayout(level, anchor)) {
            logWaiting(
                    attempt,
                    "starter source layout is incomplete or modified ("
                            + sourceBlocks
                            + "/"
                            + STARTER_VEHICLE_BLOCKS
                            + " expected blocks); refusing a partial assembly");
            return;
        }

        BlockPos assemblerPos = assemblerPos(anchor);
        try {
            ensureAssemblyGlue(level, anchor);
            BlockEntity assembler = level.getBlockEntity(assemblerPos);
            Class<?> assemblerType = Class.forName(ASSEMBLER_CLASS);
            if (assembler == null || !assemblerType.isInstance(assembler)) {
                logWaiting(
                        attempt,
                        "Simulated physics assembler block entity is unavailable at " + assemblerPos);
                return;
            }

            Method assemble = assemblerType.getMethod("assembleOrDisassemble");
            Method lastFailure = assemblerType.getMethod("getLastAssemblyException");
            Set<UUID> subLevelsBefore = SableTrainTracker.subLevelIds(level);
            assemble.invoke(assembler);
            Set<UUID> newSubLevels = SableTrainTracker.subLevelIds(level);
            newSubLevels.removeAll(subLevelsBefore);

            // 装配同步移动世界方块；成功必须同时出现一个新 Sable 身份，且完整
            // 三节源布局全部消失，不能把局部移动误记为已提交。
            int remainingBlocks = sourceVehicleBlockCount(level, anchor);
            if (remainingBlocks == 0 && newSubLevels.size() == 1) {
                UUID trainId = newSubLevels.iterator().next();
                Vec3 gatheringPoint = Vec3.atBottomCenterOf(
                        anchor.offset(StarterTrainLayout.gatheringPointOffset()));
                if (!SableTrainTracker.tagStarterTrain(
                        level,
                        trainId,
                        data.campaignId(),
                        gatheringPoint)) {
                    LastTrain.LOGGER.warn(
                            "Starter train {} assembled, but its secondary Sable recovery tag could not be written",
                            trainId);
                }
                if (!SableTrainTracker.ensureForceLoaded(level, trainId)) {
                    LastTrain.LOGGER.warn(
                            "Starter train {} assembled, but its persisted Sable force-load ticket could not be added",
                            trainId);
                }
                data.markStarterTrainAssembled(trainId);
                LastTrain.LOGGER.info(
                        "Starter train assembled into Sable sublevel {} "
                                + "with Create Simurail alpha e68481d on attempt {}",
                        trainId,
                        attempt);
            } else {
                Object failure = lastFailure.invoke(assembler);
                if (remainingBlocks > 0
                        && remainingBlocks < STARTER_VEHICLE_BLOCKS
                        && !newSubLevels.isEmpty()) {
                    LastTrain.LOGGER.error(
                            "Create Simurail produced partial starter assembly {}: "
                                    + "{}/{} source blocks remain. Automatic reassembly is disabled "
                                    + "because invoking the moved primary assembler would start disassembly",
                            newSubLevels,
                            remainingBlocks,
                            STARTER_VEHICLE_BLOCKS);
                } else {
                    logWaiting(
                            attempt,
                            failure != null
                                    ? "Simulated rejected the layout: " + failure
                                    : remainingBlocks == 0
                                            ? "the source moved but Sable exposed "
                                                    + newSubLevels.size()
                                                    + " new sublevel IDs instead of exactly one"
                                            : "the assembler did not move the complete source layout");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (shouldLog(attempt)) {
                LastTrain.LOGGER.error(
                        "Create Simurail starter assembly attempt {} failed in the alpha compatibility adapter",
                        attempt,
                        unwrap(exception));
            }
        }
    }

    private static void placeTrackBed(ServerLevel level, BlockPos anchor) {
        BlockState track = state("create:track", "shape", "xo", "turn", "false", "waterlogged", "false");
        for (int x = -32; x <= 24; x++) {
            if (Math.abs(x) > 10) {
                for (int y = 1; y <= 6; y++) {
                    for (int z = -3; z <= 3; z++) {
                        BlockPos clearance = anchor.offset(x, y, z);
                        if (!level.getBlockState(clearance).isAir()) {
                            level.setBlock(clearance, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                        }
                    }
                }
                level.setBlock(anchor.offset(x, 0, 0), Blocks.POLISHED_ANDESITE.defaultBlockState(), UPDATE_ALL);
            }
            level.setBlock(anchor.offset(x, 1, 0), track, UPDATE_ALL);
        }
    }

    private static void placeStarterTrain(ServerLevel level, BlockPos anchor) {
        for (StarterTrainLayout.BlockSpec spec : StarterTrainLayout.blocks()) {
            BlockPos offset = spec.offset();
            place(
                    level,
                    anchor,
                    offset.getX(),
                    offset.getY(),
                    offset.getZ(),
                    state(spec));
        }
    }

    /**
     * 三节车体使用一个覆盖全部功能单元的胶水体积；下表面从 y=2 开始，明确排除
     * y=1 的主线轨道，避免装配时把起点线路一起搬入 Sable 子世界。
     */
    private static void ensureAssemblyGlue(ServerLevel level, BlockPos anchor)
            throws ReflectiveOperationException {
        BlockPos min = anchor.offset(StarterTrainLayout.glueMinOffset());
        BlockPos max = anchor.offset(StarterTrainLayout.glueMaxOffset());
        AABB bounds = new AABB(
                min.getX(),
                min.getY(),
                min.getZ(),
                max.getX() + 1.0D,
                max.getY() + 1.0D,
                max.getZ() + 1.0D);

        Class<? extends Entity> glueType = Class.forName(SUPER_GLUE_CLASS).asSubclass(Entity.class);
        for (Entity existing : level.getEntitiesOfClass(glueType, bounds.inflate(0.1D))) {
            AABB existingBounds = existing.getBoundingBox();
            if (existingBounds.contains(
                            min.getX() + 0.5D,
                            min.getY() + 0.5D,
                            min.getZ() + 0.5D)
                    && existingBounds.contains(
                            max.getX() + 0.5D,
                            max.getY() + 0.5D,
                            max.getZ() + 0.5D)) {
                return;
            }
        }

        Entity glue = glueType.getConstructor(Level.class, AABB.class).newInstance(level, bounds);
        if (!level.addFreshEntity(glue)) {
            throw new IllegalStateException("Create rejected the starter vehicle super-glue entity");
        }
    }

    /** True while the full pinned vehicle stack (Create, Sable, Simulated, Simurail) is loaded. */
    public static boolean hasVehicleStack() {
        ModList mods = ModList.get();
        return mods.isLoaded("create")
                && mods.isLoaded("sable")
                && mods.isLoaded("simulated")
                && mods.isLoaded("simurail");
    }

    private static boolean requiredBlocksPresent() {
        return blockExists("create:track")
                && blockExists("simulated:physics_assembler")
                && blockExists("simulated:red_portable_engine")
                && blockExists("simurail:physics_bogey");
    }

    private static boolean blockExists(String id) {
        return BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(id));
    }

    private static boolean isBlock(ServerLevel level, BlockPos pos, String id) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return key != null && key.toString().equals(id);
    }

    private static boolean isExpectedVehicleLayout(ServerLevel level, BlockPos anchor) {
        for (StarterTrainLayout.BlockSpec spec : StarterTrainLayout.blocks()) {
            if (!isBlock(level, anchor.offset(spec.offset()), spec.blockId())) {
                return false;
            }
        }
        // 生活/医疗单元的 3x3 重连甲板必须保持两格净空。
        for (int x = 4; x <= 6; x++) {
            for (int z = 1; z <= 3; z++) {
                if (!level.getBlockState(anchor.offset(x, 3, z)).isAir()
                        || !level.getBlockState(anchor.offset(x, 4, z)).isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int sourceVehicleBlockCount(ServerLevel level, BlockPos anchor) {
        int blocks = 0;
        for (StarterTrainLayout.BlockSpec spec : StarterTrainLayout.blocks()) {
            blocks += occupied(level, anchor.offset(spec.offset()));
        }
        return blocks;
    }

    /** 每个车载容器以自己的操作标记去重，补给按功能车厢分布且重启不重灌。 */
    private static boolean ensureTrainSupplies(
            ServerLevel level,
            CampaignSavedData data,
            BlockPos anchor) {
        String operation = data.campaignId() + ":" + TRAIN_SUPPLY_VERSION;
        for (BlockPos offset : StarterTrainLayout.supplies().stream()
                .map(StarterTrainLayout.SupplySpec::containerOffset)
                .distinct()
                .toList()) {
            BlockEntity blockEntity = level.getBlockEntity(anchor.offset(offset));
            if (!(blockEntity instanceof Container container)) {
                LastTrain.LOGGER.warn(
                        "Starter train supply container is unavailable at {}",
                        anchor.offset(offset));
                return false;
            }
            if (operation.equals(blockEntity.getPersistentData()
                    .getString(TRAIN_SUPPLY_OPERATION_KEY))) {
                continue;
            }
            container.clearContent();
            for (StarterTrainLayout.SupplySpec supply : StarterTrainLayout.supplies()) {
                if (!supply.containerOffset().equals(offset)) {
                    continue;
                }
                ResourceLocation itemId = ResourceLocation.parse(supply.itemId());
                if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
                    return false;
                }
                Item item = BuiltInRegistries.ITEM.get(itemId);
                container.setItem(
                        supply.slot(),
                        new ItemStack(item, supply.count()));
            }
            blockEntity.getPersistentData().putString(
                    TRAIN_SUPPLY_OPERATION_KEY,
                    operation);
            blockEntity.setChanged();
        }
        return true;
    }

    private static int occupied(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() ? 0 : 1;
    }

    private static BlockPos assemblerPos(BlockPos anchor) {
        return anchor.offset(StarterTrainLayout.assemblerOffset());
    }

    private static void place(
            ServerLevel level,
            BlockPos anchor,
            int x,
            int y,
            int z,
            BlockState state) {
        level.setBlock(anchor.offset(x, y, z), state, UPDATE_ALL);
    }

    private static BlockState state(String id, String... propertyPairs) {
        if ((propertyPairs.length & 1) != 0) {
            throw new IllegalArgumentException("Block state properties must be key/value pairs");
        }
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        BlockState state = block.defaultBlockState();
        for (int index = 0; index < propertyPairs.length; index += 2) {
            String propertyName = propertyPairs[index];
            String propertyValue = propertyPairs[index + 1];
            Property<?> property = state.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(propertyName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            id + " has no block-state property named " + propertyName));
            state = applyProperty(state, property, propertyValue);
        }
        return state;
    }

    private static BlockState state(StarterTrainLayout.BlockSpec spec) {
        BlockState state = BuiltInRegistries.BLOCK
                .get(ResourceLocation.parse(spec.blockId()))
                .defaultBlockState();
        for (java.util.Map.Entry<String, String> entry : spec.properties().entrySet()) {
            Property<?> property = state.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            spec.blockId() + " has no block-state property named " + entry.getKey()));
            state = applyProperty(state, property, entry.getValue());
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(BlockState state, Property property, String serializedValue) {
        Optional<? extends Comparable> parsed = property.getValue(serializedValue);
        return parsed.map(value -> state.setValue(property, value))
                .orElseThrow(() -> new IllegalArgumentException(
                        property.getName() + " rejects block-state value " + serializedValue));
    }

    private static void logWaiting(int attempt, String reason) {
        if (shouldLog(attempt)) {
            LastTrain.LOGGER.warn(
                    "Create Simurail starter assembly attempt {} is waiting: {}",
                    attempt,
                    reason);
        }
    }

    private static boolean shouldLog(int attempt) {
        return attempt == 1 || attempt % 10 == 0;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }
}
