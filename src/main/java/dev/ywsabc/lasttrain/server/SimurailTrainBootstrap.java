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
import net.minecraft.world.entity.Entity;
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
 * Builds the starter vehicle from Create Simurail's own proven Ponder layout
 * and asks Create: Simulated's physics assembler to turn it into a Sable
 * sublevel.
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
    private static final int STARTER_VEHICLE_BLOCKS = 24;
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
        if (data.starterTrainPlaced()) {
            if (isPonderCoreLayout(level, anchor)) {
                ensureGatheringDeck(level, anchor);
            }
            if (sourceVehicleBlockCount(level, anchor) == 0) {
                Optional<UUID> recovered = SableTrainTracker.findTaggedStarterTrain(
                        level,
                        data.campaignId());
                if (recovered.isPresent()) {
                    SableTrainTracker.ensureForceLoaded(level, recovered.orElseThrow());
                    data.markStarterTrainAssembled(recovered.orElseThrow());
                    LastTrain.LOGGER.info(
                            "Recovered starter Simurail train {} from its campaign tag",
                            recovered.orElseThrow());
                } else {
                    LastTrain.LOGGER.error(
                            "Starter Simurail source footprint is missing and no Sable sublevel "
                                    + "has this campaign's starter-train tag; manual recovery is required");
                }
            }
            return;
        }

        if (!requiredBlocksPresent()) {
            LastTrain.LOGGER.error(
                    "Create Simurail is loaded but its alpha block set does not match commit e68481d");
            return;
        }

        try {
            placeTrackBed(level, anchor);
            placePonderTestVehicle(level, anchor);
            ensureAssemblyGlue(level, anchor);
            data.markStarterTrainPlaced();
            LastTrain.LOGGER.info(
                    "Placed and glued the Create Simurail starter vehicle at {}; "
                            + "waiting for Create track and Sable physics initialization",
                    anchor);
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
        if (isPonderCoreLayout(level, anchor)) {
            ensureGatheringDeck(level, anchor);
        }
        int sourceBlocks = sourceVehicleBlockCount(level, anchor);
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

            // Simulated assembly is synchronous: the world blocks are moved
            // into the newly-created sublevel before the method returns. Its
            // public method clears lastException even when the helper returns
            // null, so success requires both a new Sable ID and a fully-cleared
            // complete 24-block source footprint.
            int remainingBlocks = sourceVehicleBlockCount(level, anchor);
            if (remainingBlocks == 0 && newSubLevels.size() == 1) {
                UUID trainId = newSubLevels.iterator().next();
                Vec3 gatheringPoint = Vec3.atBottomCenterOf(anchor.offset(-1, 3, 2));
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

    /**
     * This is the compact demonstration supplied by Simurail's
     * {@code physics_bogey/intro.nbt}, translated around the campaign spawn.
     * Keeping the alpha vehicle deliberately small makes API breakage easy to
     * diagnose before the campaign art pass replaces it with the full train.
     */
    private static void placePonderTestVehicle(ServerLevel level, BlockPos anchor) {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState bogey = state(
                "simurail:physics_bogey",
                "facing", "east",
                "inverted", "false",
                "waterlogged", "false");

        place(level, anchor, -3, 2, 0, bogey);
        place(level, anchor, 0, 2, 0, bogey);

        for (int x = -3; x <= 0; x++) {
            place(level, anchor, x, 2, -1, planks);
            place(level, anchor, x, 2, 1, planks);
        }
        // Extend the unobstructed east-side walkway into a genuine 3x3
        // gathering deck. Its center has two clear blocks above it.
        for (int x = -2; x <= 0; x++) {
            place(level, anchor, x, 2, 2, planks);
            place(level, anchor, x, 2, 3, planks);
        }
        place(level, anchor, -1, 2, 0, planks);

        place(level, anchor, -2, 2, 0, state(
                "create:encased_chain_drive",
                "part", "start",
                "axis", "x",
                "axis_along_first", "true"));
        place(level, anchor, -2, 3, 0, state(
                "create:encased_chain_drive",
                "part", "end",
                "axis", "x",
                "axis_along_first", "true"));
        place(level, anchor, -1, 3, 0, state(
                "simulated:red_portable_engine",
                "facing", "west",
                "lit", "false"));

        BlockState lever = state(
                "minecraft:lever",
                "face", "floor",
                "facing", "east",
                "powered", "false");
        place(level, anchor, -3, 3, -1, lever);
        place(level, anchor, -3, 3, 0, lever);
        place(level, anchor, -3, 3, 1, lever);

        place(level, anchor, 0, 3, 0, state(
                "simulated:physics_assembler",
                "face", "floor",
                "facing", "east"));
    }

    /**
     * The Ponder scene moves an independent visual section and contains no glue
     * entity. Simulated's real assembly traversal does not make ordinary
     * planks sticky, so a real world needs one glue volume around the vehicle.
     * Its lower face starts at y=2, deliberately excluding the track at y=1.
     */
    private static void ensureAssemblyGlue(ServerLevel level, BlockPos anchor)
            throws ReflectiveOperationException {
        BlockPos min = anchor.offset(-3, 2, -1);
        BlockPos max = anchor.offset(0, 3, 3);
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
                && blockExists("create:encased_chain_drive")
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

    private static boolean isPonderCoreLayout(ServerLevel level, BlockPos anchor) {
        if (!isBlock(level, anchor.offset(-3, 2, 0), "simurail:physics_bogey")
                || !isBlock(level, anchor.offset(0, 2, 0), "simurail:physics_bogey")
                || !isBlock(level, anchor.offset(-2, 2, 0), "create:encased_chain_drive")
                || !isBlock(level, anchor.offset(-2, 3, 0), "create:encased_chain_drive")
                || !isBlock(level, anchor.offset(-1, 2, 0), "minecraft:oak_planks")
                || !isBlock(level, anchor.offset(-1, 3, 0), "simulated:red_portable_engine")
                || !isBlock(level, assemblerPos(anchor), "simulated:physics_assembler")) {
            return false;
        }
        for (int x = -3; x <= 0; x++) {
            if (!isBlock(level, anchor.offset(x, 2, -1), "minecraft:oak_planks")
                    || !isBlock(level, anchor.offset(x, 2, 1), "minecraft:oak_planks")) {
                return false;
            }
        }
        return isBlock(level, anchor.offset(-3, 3, -1), "minecraft:lever")
                && isBlock(level, anchor.offset(-3, 3, 0), "minecraft:lever")
                && isBlock(level, anchor.offset(-3, 3, 1), "minecraft:lever");
    }

    private static boolean isExpectedVehicleLayout(ServerLevel level, BlockPos anchor) {
        if (!isPonderCoreLayout(level, anchor)) {
            return false;
        }
        // The 3x3 deck spans x=-2..0 and z=1..3. Every floor block is
        // solid oak and both blocks above every cell must remain clear.
        for (int x = -2; x <= 0; x++) {
            for (int z = 1; z <= 3; z++) {
                if (!isBlock(level, anchor.offset(x, 2, z), "minecraft:oak_planks")
                        || !level.getBlockState(anchor.offset(x, 3, z)).isAir()
                        || !level.getBlockState(anchor.offset(x, 4, z)).isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Idempotently upgrades the original 18-block proof vehicle's walkway. */
    private static boolean ensureGatheringDeck(ServerLevel level, BlockPos anchor) {
        for (int x = -2; x <= 0; x++) {
            for (int z = 2; z <= 3; z++) {
                BlockPos pos = anchor.offset(x, 2, z);
                BlockState existing = level.getBlockState(pos);
                if (!existing.isAir() && !isBlock(level, pos, "minecraft:oak_planks")) {
                    return false;
                }
            }
        }
        for (int x = -2; x <= 0; x++) {
            for (int z = 2; z <= 3; z++) {
                BlockPos pos = anchor.offset(x, 2, z);
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), UPDATE_ALL);
                }
            }
        }
        return true;
    }

    private static int sourceVehicleBlockCount(ServerLevel level, BlockPos anchor) {
        int blocks = 0;
        blocks += occupied(level, anchor.offset(-3, 2, 0));
        blocks += occupied(level, anchor.offset(0, 2, 0));
        blocks += occupied(level, anchor.offset(-2, 2, 0));
        blocks += occupied(level, anchor.offset(-2, 3, 0));
        blocks += occupied(level, anchor.offset(-1, 2, 0));
        blocks += occupied(level, anchor.offset(-1, 3, 0));
        blocks += occupied(level, assemblerPos(anchor));
        for (int x = -3; x <= 0; x++) {
            blocks += occupied(level, anchor.offset(x, 2, -1));
            blocks += occupied(level, anchor.offset(x, 2, 1));
        }
        for (int x = -2; x <= 0; x++) {
            blocks += occupied(level, anchor.offset(x, 2, 2));
            blocks += occupied(level, anchor.offset(x, 2, 3));
        }
        blocks += occupied(level, anchor.offset(-3, 3, -1));
        blocks += occupied(level, anchor.offset(-3, 3, 0));
        blocks += occupied(level, anchor.offset(-3, 3, 1));
        return blocks;
    }

    private static int occupied(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() ? 0 : 1;
    }

    private static BlockPos assemblerPos(BlockPos anchor) {
        return anchor.offset(0, 3, 0);
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
