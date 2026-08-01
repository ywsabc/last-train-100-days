package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
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
    private static final int PONDER_VEHICLE_BLOCKS = 18;
    private static final String ASSEMBLER_CLASS =
            "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity";
    private static final String SUBLEVEL_CONTAINER_CLASS =
            "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String SUPER_GLUE_CLASS =
            "com.simibubi.create.content.contraptions.glue.SuperGlueEntity";

    private SimurailTrainBootstrap() {
    }

    public static void ensureLayout(ServerLevel level, CampaignSavedData data) {
        if (data.starterTrainAssembled()) {
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
            if (sourceVehicleBlockCount(level, anchor) == 0) {
                try {
                    Set<UUID> subLevels = subLevelIds(level);
                    if (!subLevels.isEmpty()) {
                        data.markStarterTrainAssembled();
                        LastTrain.LOGGER.info(
                                "Recovered starter Simurail train state: its source footprint "
                                        + "is clear and Sable exposes loaded sublevel(s) {}",
                                subLevels);
                    } else {
                        LastTrain.LOGGER.error(
                                "Starter Simurail source footprint is missing, but Sable exposes "
                                        + "no loaded sublevel; refusing to mark assembly successful");
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                    LastTrain.LOGGER.error(
                            "Could not verify the persisted starter Simurail sublevel",
                            unwrap(exception));
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
        if (data.starterTrainAssembled() || !data.starterTrainPlaced() || !hasVehicleStack()) {
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
        int attempt = data.recordStarterTrainAssemblyAttempt();
        if (sourceBlocks == 0) {
            try {
                Set<UUID> subLevels = subLevelIds(level);
                if (!subLevels.isEmpty()) {
                    data.markStarterTrainAssembled();
                    LastTrain.LOGGER.info(
                            "Starter Create Simurail vehicle is active; "
                                    + "Sable exposes loaded sublevel(s) {}",
                            subLevels);
                } else {
                    logWaiting(
                            attempt,
                            "the complete source footprint is absent but Sable exposes no sublevel");
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                if (shouldLog(attempt)) {
                    LastTrain.LOGGER.error(
                            "Could not verify the moved starter Simurail vehicle on attempt {}",
                            attempt,
                            unwrap(exception));
                }
            }
            return;
        }

        if (!isExpectedVehicleLayout(level, anchor)) {
            logWaiting(
                    attempt,
                    "starter source layout is incomplete or modified ("
                            + sourceBlocks
                            + "/"
                            + PONDER_VEHICLE_BLOCKS
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
            Set<UUID> subLevelsBefore = subLevelIds(level);
            assemble.invoke(assembler);
            Set<UUID> newSubLevels = subLevelIds(level);
            newSubLevels.removeAll(subLevelsBefore);

            // Simulated assembly is synchronous: the world blocks are moved
            // into the newly-created sublevel before the method returns. Its
            // public method clears lastException even when the helper returns
            // null, so success requires both a new Sable ID and a fully-cleared
            // 18-block source footprint.
            int remainingBlocks = sourceVehicleBlockCount(level, anchor);
            if (remainingBlocks == 0 && !newSubLevels.isEmpty()) {
                data.markStarterTrainAssembled();
                LastTrain.LOGGER.info(
                        "Starter train assembled into Sable sublevel(s) {} "
                                + "with Create Simurail alpha e68481d on attempt {}",
                        newSubLevels,
                        attempt);
            } else {
                Object failure = lastFailure.invoke(assembler);
                if (remainingBlocks > 0
                        && remainingBlocks < PONDER_VEHICLE_BLOCKS
                        && !newSubLevels.isEmpty()) {
                    LastTrain.LOGGER.error(
                            "Create Simurail produced partial starter assembly {}: "
                                    + "{}/{} source blocks remain. Automatic reassembly is disabled "
                                    + "because invoking the moved primary assembler would start disassembly",
                            newSubLevels,
                            remainingBlocks,
                            PONDER_VEHICLE_BLOCKS);
                } else {
                    logWaiting(
                            attempt,
                            failure != null
                                    ? "Simulated rejected the layout: " + failure
                                    : remainingBlocks == 0
                                            ? "the source moved but Sable exposed no new sublevel ID"
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
        BlockPos max = anchor.offset(0, 3, 1);
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

    private static boolean hasVehicleStack() {
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

    private static boolean isExpectedVehicleLayout(ServerLevel level, BlockPos anchor) {
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
        blocks += occupied(level, anchor.offset(-3, 3, -1));
        blocks += occupied(level, anchor.offset(-3, 3, 0));
        blocks += occupied(level, anchor.offset(-3, 3, 1));
        return blocks;
    }

    private static int occupied(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() ? 0 : 1;
    }

    private static Set<UUID> subLevelIds(ServerLevel level) throws ReflectiveOperationException {
        Class<?> containerType = Class.forName(SUBLEVEL_CONTAINER_CLASS);
        Object container = containerType.getMethod("getContainer", ServerLevel.class)
                .invoke(null, level);
        if (container == null) {
            throw new IllegalStateException("Sable has not initialized its server sublevel container");
        }

        Object value = containerType.getMethod("getAllSubLevels").invoke(container);
        if (!(value instanceof Iterable<?> subLevels)) {
            throw new IllegalStateException("Sable getAllSubLevels() no longer returns an iterable value");
        }

        Set<UUID> ids = new HashSet<>();
        for (Object subLevel : subLevels) {
            Object id = subLevel.getClass().getMethod("getUniqueId").invoke(subLevel);
            if (!(id instanceof UUID uuid)) {
                throw new IllegalStateException("Sable sublevel has no UUID identity");
            }
            ids.add(uuid);
        }
        return ids;
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
