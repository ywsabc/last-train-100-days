package dev.ywsabc.lasttrain.integration;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Narrow optional bridge to TongDa Railway 1.1.3's track-spawner API.
 *
 * <p>The bridge queues straight track runs of any horizontal direction and
 * length by placing {@code tongdarailway:track_spawner}, creating each
 * instruction through {@code TrackPutInfo.getByDir}, and passing the list to
 * {@code TrackSpawnerBlockEntity.addTrackPutInfo}. The route director submits
 * the eastbound 64-block main line through {@link #submitEastboundSegment}
 * and the plan-derived branch runs (station sidings, city spurs) through
 * {@link #submitStraightRun}; TongDa remains responsible for physical
 * placement during its normal server tick near a player.</p>
 *
 * <p>No TongDa type is linked at compile time. A queued submission is reported
 * as {@link SubmissionStatus#QUEUED}, never as completed. Call
 * {@link #isEastboundSegmentComplete(ServerLevel, BlockPos)} before recording
 * persistent route progress.</p>
 */
public final class TongDaTrackBridge {
    public static final String TONGDA_MOD_ID = "tongdarailway";

    private static final ResourceLocation TRACK_SPAWNER_ID =
            ResourceLocation.fromNamespaceAndPath(TONGDA_MOD_ID, "track_spawner");
    private static final ResourceLocation CREATE_TRACK_ID =
            ResourceLocation.fromNamespaceAndPath("create", "track");
    private static final ResourceLocation FIRED_SPAWNER_LAMP_ID =
            ResourceLocation.fromNamespaceAndPath("create", "rose_quartz_lamp");
    private static final int UPDATE_ALL = 3;

    private static final String TRACK_PUT_INFO_CLASS =
            "com.hxzhitang.tongdarailway.structure.TrackPutInfo";
    private static final String BEZIER_INFO_CLASS =
            "com.hxzhitang.tongdarailway.structure.TrackPutInfo$BezierInfo";
    private static final String TRACK_SPAWNER_BLOCK_ENTITY_CLASS =
            "com.hxzhitang.tongdarailway.blocks.TrackSpawnerBlockEntity";
    private static final String CONFIG_CLASS = "com.hxzhitang.tongdarailway.Config";

    private static volatile ApiResolution cachedApi;

    private TongDaTrackBridge() {
    }

    /**
     * Queues a TongDa spawner for a 64-block eastbound line beginning at
     * {@code trackStart}.
     *
     * <p>The deterministic spawner is four blocks to the positive-Z side of
     * the first track. Its eventual rose-quartz-lamp replacement therefore
     * cannot leave a gap in the railway or obstruct the train envelope.</p>
     */
    public static SubmissionResult submitEastboundSegment(
            ServerLevel level,
            BlockPos trackStart) {
        return submitStraightRun(
                level,
                trackStart,
                Direction.EAST,
                TongDaTrackPolicy.SEGMENT_LENGTH);
    }

    /**
     * Queues a TongDa spawner for a straight track run of {@code length}
     * blocks beginning at {@code trackStart} and continuing in
     * {@code direction} (horizontal only). The spawner sits four blocks to
     * the clockwise side of the first track.
     *
     * <p>Straight Create track shapes follow the run axis: XO along the X
     * axis, ZO along the Z axis. Real block placement remains TongDa's job;
     * a queued submission is pending work, never completion proof.</p>
     *
     * @throws IllegalArgumentException for vertical directions or
     *     non-positive lengths
     */
    public static SubmissionResult submitStraightRun(
            ServerLevel level,
            BlockPos trackStart,
            Direction direction,
            int length) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(trackStart, "trackStart");
        Objects.requireNonNull(direction, "direction");
        if (direction.getAxis() == Direction.Axis.Y) {
            throw new IllegalArgumentException(
                    "Track runs must be horizontal, got " + direction);
        }
        if (length < 1) {
            throw new IllegalArgumentException(
                    "Track runs must be at least one block long, got " + length);
        }

        BlockPos spawnerPos;
        List<BlockPos> trackPositions;
        try {
            spawnerPos = spawnerPosition(trackStart, direction);
            trackPositions = runPositions(trackStart, direction, length);
        } catch (ArithmeticException exception) {
            return new SubmissionResult(
                    SubmissionStatus.INVALID_POSITION,
                    trackStart,
                    diagnostic(exception));
        }

        RunInspection initialInspection = inspectRun(level, trackStart, direction, length);
        if (initialInspection.complete()) {
            return new SubmissionResult(
                    SubmissionStatus.ALREADY_COMPLETE,
                    spawnerPos,
                    "All " + length + " Create tracks are already present");
        }
        if (!ModList.get().isLoaded(TONGDA_MOD_ID)) {
            return new SubmissionResult(
                    SubmissionStatus.TONGDA_NOT_LOADED,
                    spawnerPos,
                    "TongDa Railway is not loaded");
        }

        ApiResolution resolution = resolveApi();
        if (resolution.api() == null) {
            return new SubmissionResult(
                    SubmissionStatus.API_INCOMPATIBLE,
                    spawnerPos,
                    resolution.failure());
        }
        TongDaApi api = resolution.api();

        try {
            if (!api.isTrackSpawnerEnabled()) {
                return new SubmissionResult(
                        SubmissionStatus.TRACK_SPAWNER_DISABLED,
                        spawnerPos,
                        "TongDa enableTrackSpawner is false");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return new SubmissionResult(
                    SubmissionStatus.API_INCOMPATIBLE,
                    spawnerPos,
                    diagnostic(unwrap(exception)));
        }

        Block spawnerBlock = registeredBlock(TRACK_SPAWNER_ID);
        Block firedSpawnerLamp = registeredBlock(FIRED_SPAWNER_LAMP_ID);
        if (spawnerBlock == Blocks.AIR) {
            return new SubmissionResult(
                    SubmissionStatus.REGISTRY_ENTRY_MISSING,
                    spawnerPos,
                    "Missing block " + TRACK_SPAWNER_ID);
        }
        if (registeredBlock(CREATE_TRACK_ID) == Blocks.AIR) {
            return new SubmissionResult(
                    SubmissionStatus.REGISTRY_ENTRY_MISSING,
                    spawnerPos,
                    "Missing block " + CREATE_TRACK_ID);
        }
        if (initialInspection.conflicting() > 0) {
            return new SubmissionResult(
                    SubmissionStatus.TRACK_SHAPE_CONFLICT,
                    spawnerPos,
                    initialInspection.conflicting()
                            + " existing Create track(s) do not match the straight shape; "
                            + "TongDa skips existing track blocks");
        }

        if (!withinWritableBounds(level, spawnerPos)
                || trackPositions.stream().anyMatch(pos -> !withinWritableBounds(level, pos))) {
            return new SubmissionResult(
                    SubmissionStatus.OUTSIDE_WORLD_BOUNDS,
                    spawnerPos,
                    "The run or its spawner is outside build/world-border bounds");
        }

        BlockState previousState = level.getBlockState(spawnerPos);
        boolean retryingFiredSpawner = firedSpawnerLamp != Blocks.AIR
                && previousState.is(firedSpawnerLamp)
                && hasSerializedProperty(previousState, "powering", "true");
        if (!previousState.isAir()) {
            BlockEntity existing = previousState.is(spawnerBlock)
                    ? level.getBlockEntity(spawnerPos)
                    : null;
            if (existing != null && api.isSpawnerBlockEntity(existing)) {
                return new SubmissionResult(
                        SubmissionStatus.MATERIALIZATION_PENDING,
                        spawnerPos,
                        "The deterministic TongDa spawner already exists; completion is not yet proven");
            }
            if (retryingFiredSpawner) {
                if (!(initialInspection.loaded() == length
                        && initialInspection.matching() < length
                        && initialInspection.conflicting() == 0)) {
                    return new SubmissionResult(
                            SubmissionStatus.RETRY_DEFERRED,
                            spawnerPos,
                            "TongDa's powered completion lamp is present, but the run is not "
                                    + "fully loaded or provably incomplete yet; retry is deferred");
                }
            } else {
                return new SubmissionResult(
                        SubmissionStatus.CONTROL_POSITION_BLOCKED,
                        spawnerPos,
                        "Spawner position is occupied by "
                                + BuiltInRegistries.BLOCK.getKey(previousState.getBlock()));
            }
        }

        List<Object> putInfos;
        try {
            putInfos = api.createPutInfos(trackPositions, direction);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return new SubmissionResult(
                    SubmissionStatus.API_INCOMPATIBLE,
                    spawnerPos,
                    diagnostic(unwrap(exception)));
        }

        if (!level.setBlock(spawnerPos, spawnerBlock.defaultBlockState(), UPDATE_ALL)) {
            return new SubmissionResult(
                    SubmissionStatus.SPAWNER_PLACEMENT_FAILED,
                    spawnerPos,
                    "ServerLevel rejected the TongDa spawner block placement");
        }

        BlockEntity blockEntity = level.getBlockEntity(spawnerPos);
        if (blockEntity == null || !api.isSpawnerBlockEntity(blockEntity)) {
            restoreControlPosition(level, spawnerPos, spawnerBlock, previousState);
            return new SubmissionResult(
                    SubmissionStatus.BLOCK_ENTITY_MISMATCH,
                    spawnerPos,
                    blockEntity == null
                            ? "TongDa spawner did not create a block entity"
                            : "Unexpected block entity " + blockEntity.getClass().getName());
        }

        try {
            api.addTrackPutInfos(blockEntity, putInfos);
            blockEntity.setChanged();
            return new SubmissionResult(
                    SubmissionStatus.QUEUED,
                    spawnerPos,
                    retryingFiredSpawner
                            ? "Requeued " + length + " entries after TongDa fired but left an "
                                    + "incomplete straight run"
                            : "Queued " + length + " TrackPutInfo entries; physical placement remains pending");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            restoreControlPosition(level, spawnerPos, spawnerBlock, previousState);
            return new SubmissionResult(
                    SubmissionStatus.SUBMISSION_FAILED,
                    spawnerPos,
                    diagnostic(unwrap(exception)));
        }
    }

    /**
     * Reads only loaded chunks and reports whether every physical track exists.
     * This method never treats a present or previously-fired spawner as proof.
     */
    public static SegmentInspection inspectEastboundSegment(
            ServerLevel level,
            BlockPos trackStart) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(trackStart, "trackStart");

        Block trackBlock = registeredBlock(CREATE_TRACK_ID);
        Block spawnerBlock = registeredBlock(TRACK_SPAWNER_ID);
        int loaded = 0;
        int matching = 0;
        int conflicting = 0;
        for (BlockPos trackPos : trackPositions(trackStart)) {
            if (!level.hasChunkAt(trackPos)) {
                continue;
            }
            loaded++;
            if (trackBlock != Blocks.AIR) {
                BlockState state = level.getBlockState(trackPos);
                if (isCreateXoTrack(state, trackBlock)) {
                    matching++;
                } else if (state.is(trackBlock)) {
                    conflicting++;
                }
            }
        }

        BlockPos spawnerPos = offset(trackStart, TongDaTrackPolicy.eastboundSpawnerOffset());
        boolean spawnerPresent = spawnerBlock != Blocks.AIR
                && level.hasChunkAt(spawnerPos)
                && level.getBlockState(spawnerPos).is(spawnerBlock);
        TongDaTrackPolicy.CompletionState state = TongDaTrackPolicy.completionState(
                loaded,
                matching,
                spawnerPresent);
        return new SegmentInspection(state, loaded, matching, conflicting, spawnerPos);
    }

    /** Returns true only after all 64 loaded positions are actual Create XO tracks. */
    public static boolean isEastboundSegmentComplete(
            ServerLevel level,
            BlockPos trackStart) {
        return inspectEastboundSegment(level, trackStart).actuallyComplete();
    }

    private static List<BlockPos> trackPositions(BlockPos start) {
        return runPositions(start, Direction.EAST, TongDaTrackPolicy.SEGMENT_LENGTH);
    }

    /** Consecutive positions of a straight horizontal run, start first. */
    private static List<BlockPos> runPositions(BlockPos start, Direction direction, int length) {
        List<BlockPos> positions = new ArrayList<>(length);
        BlockPos cursor = start;
        for (int index = 0; index < length; index++) {
            positions.add(cursor);
            cursor = new BlockPos(
                    Math.addExact(cursor.getX(), direction.getStepX()),
                    Math.addExact(cursor.getY(), direction.getStepY()),
                    Math.addExact(cursor.getZ(), direction.getStepZ()));
        }
        return List.copyOf(positions);
    }

    /** The deterministic spawner cell: four blocks clockwise of the first track. */
    private static BlockPos spawnerPosition(BlockPos start, Direction direction) {
        Direction lateral = direction.getClockWise();
        return new BlockPos(
                Math.addExact(start.getX(), lateral.getStepX() * TongDaTrackPolicy.SPAWNER_LATERAL_OFFSET),
                Math.addExact(start.getY(), lateral.getStepY() * TongDaTrackPolicy.SPAWNER_LATERAL_OFFSET),
                Math.addExact(start.getZ(), lateral.getStepZ() * TongDaTrackPolicy.SPAWNER_LATERAL_OFFSET));
    }

    /** Loaded-chunk observation of one straight run. */
    private static RunInspection inspectRun(
            ServerLevel level,
            BlockPos start,
            Direction direction,
            int length) {
        Block trackBlock = registeredBlock(CREATE_TRACK_ID);
        String expectedShape = direction.getAxis() == Direction.Axis.X ? "xo" : "zo";
        int loaded = 0;
        int matching = 0;
        int conflicting = 0;
        for (BlockPos trackPos : runPositions(start, direction, length)) {
            if (!level.hasChunkAt(trackPos)) {
                continue;
            }
            loaded++;
            if (trackBlock != Blocks.AIR) {
                BlockState state = level.getBlockState(trackPos);
                if (state.is(trackBlock) && hasSerializedProperty(state, "shape", expectedShape)) {
                    matching++;
                } else if (state.is(trackBlock)) {
                    conflicting++;
                }
            }
        }
        return new RunInspection(
                loaded == length && matching == length,
                loaded,
                matching,
                conflicting);
    }

    private record RunInspection(boolean complete, int loaded, int matching, int conflicting) {
    }

    private static BlockPos offset(BlockPos start, TongDaTrackPolicy.Offset offset) {
        return new BlockPos(
                Math.addExact(start.getX(), offset.east()),
                Math.addExact(start.getY(), offset.up()),
                Math.addExact(start.getZ(), offset.south()));
    }

    private static boolean withinWritableBounds(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos);
    }

    private static Block registeredBlock(ResourceLocation id) {
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.AIR);
    }

    private static boolean isCreateXoTrack(BlockState state, Block trackBlock) {
        if (!state.is(trackBlock)) {
            return false;
        }
        return hasSerializedProperty(state, "shape", "xo");
    }

    private static boolean hasSerializedProperty(
            BlockState state,
            String propertyName,
            String expectedValue) {
        return state.getProperties().stream()
                .filter(property -> property.getName().equals(propertyName))
                .findFirst()
                .map(property -> serializedValue(state, property).equals(expectedValue))
                .orElse(false);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String serializedValue(BlockState state, Property property) {
        Comparable value = state.getValue(property);
        return property.getName(value);
    }

    private static void restoreControlPosition(
            ServerLevel level,
            BlockPos spawnerPos,
            Block spawnerBlock,
            BlockState previousState) {
        if (level.getBlockState(spawnerPos).is(spawnerBlock)) {
            level.setBlock(spawnerPos, previousState, UPDATE_ALL);
        }
    }

    private static ApiResolution resolveApi() {
        ApiResolution resolution = cachedApi;
        if (resolution != null) {
            return resolution;
        }
        synchronized (TongDaTrackBridge.class) {
            resolution = cachedApi;
            if (resolution == null) {
                cachedApi = resolution = TongDaApi.resolve();
            }
            return resolution;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    private static String diagnostic(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** Submission states intentionally distinguish queued work from completion. */
    public enum SubmissionStatus {
        ALREADY_COMPLETE,
        QUEUED,
        MATERIALIZATION_PENDING,
        RETRY_DEFERRED,
        TONGDA_NOT_LOADED,
        API_INCOMPATIBLE,
        TRACK_SPAWNER_DISABLED,
        REGISTRY_ENTRY_MISSING,
        TRACK_SHAPE_CONFLICT,
        INVALID_POSITION,
        OUTSIDE_WORLD_BOUNDS,
        CONTROL_POSITION_BLOCKED,
        SPAWNER_PLACEMENT_FAILED,
        BLOCK_ENTITY_MISMATCH,
        SUBMISSION_FAILED
    }

    public record SubmissionResult(
            SubmissionStatus status,
            BlockPos spawnerPosition,
            String detail) {
        public SubmissionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(spawnerPosition, "spawnerPosition");
            Objects.requireNonNull(detail, "detail");
        }

        /** This only means TongDa accepted the instructions, not that tracks exist. */
        public boolean queued() {
            return status == SubmissionStatus.QUEUED;
        }
    }

    public record SegmentInspection(
            TongDaTrackPolicy.CompletionState state,
            int loadedTrackPositions,
            int matchingTrackPositions,
            int conflictingCreateTrackPositions,
            BlockPos spawnerPosition) {
        public SegmentInspection {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(spawnerPosition, "spawnerPosition");
        }

        public boolean actuallyComplete() {
            return TongDaTrackPolicy.canMarkMaterialized(state);
        }
    }

    /** All reflection against the optional TongDa implementation is confined here. */
    private record TongDaApi(
            Class<? extends BlockEntity> spawnerBlockEntityType,
            Method getByDir,
            Method shapeAccessor,
            Method addTrackPutInfo,
            Field enableTrackSpawner) {
        private static ApiResolution resolve() {
            try {
                ClassLoader loader = TongDaTrackBridge.class.getClassLoader();
                Class<?> trackPutInfo = Class.forName(TRACK_PUT_INFO_CLASS, false, loader);
                Class<?> bezierInfo = Class.forName(BEZIER_INFO_CLASS, false, loader);
                Class<? extends BlockEntity> blockEntityType = Class.forName(
                                TRACK_SPAWNER_BLOCK_ENTITY_CLASS,
                                false,
                                loader)
                        .asSubclass(BlockEntity.class);
                Class<?> config = Class.forName(CONFIG_CLASS, false, loader);

                Method factory = trackPutInfo.getMethod(
                        "getByDir",
                        BlockPos.class,
                        Vec3.class,
                        bezierInfo);
                Method shape = trackPutInfo.getMethod("shape");
                Method add = blockEntityType.getMethod("addTrackPutInfo", List.class);
                Field enabled = config.getField("enableTrackSpawner");

                if (!Modifier.isStatic(factory.getModifiers())
                        || factory.getReturnType() != trackPutInfo
                        || shape.getParameterCount() != 0
                        || add.getReturnType() != void.class
                        || !Modifier.isStatic(enabled.getModifiers())
                        || enabled.getType() != boolean.class) {
                    throw new NoSuchMethodException(
                            "TongDa 1.1.3 track-spawner signatures do not match");
                }
                return new ApiResolution(
                        new TongDaApi(blockEntityType, factory, shape, add, enabled),
                        "");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                return new ApiResolution(null, diagnostic(unwrap(exception)));
            }
        }

        private boolean isTrackSpawnerEnabled() throws IllegalAccessException {
            return enableTrackSpawner.getBoolean(null);
        }

        private boolean isSpawnerBlockEntity(BlockEntity blockEntity) {
            return spawnerBlockEntityType.isInstance(blockEntity);
        }

        private List<Object> createPutInfos(List<BlockPos> positions, Direction direction)
                throws ReflectiveOperationException {
            Vec3 vector = new Vec3(
                    direction.getStepX(),
                    direction.getStepY(),
                    direction.getStepZ());
            String expectedShape = direction.getAxis() == Direction.Axis.X ? "XO" : "ZO";
            List<Object> result = new ArrayList<>(positions.size());
            for (BlockPos position : positions) {
                Object putInfo = getByDir.invoke(null, position, vector, null);
                if (putInfo == null || getByDir.getReturnType() != putInfo.getClass()) {
                    throw new ReflectiveOperationException(
                            "TrackPutInfo.getByDir returned an unexpected value");
                }
                Object shape = shapeAccessor.invoke(putInfo);
                if (!(shape instanceof Enum<?> trackShape)
                        || !trackShape.name().equals(expectedShape)) {
                    throw new ReflectiveOperationException(
                            "TrackPutInfo.getByDir did not produce shape " + expectedShape);
                }
                result.add(putInfo);
            }
            if (result.size() != positions.size()) {
                throw new ReflectiveOperationException(
                        "Expected " + positions.size() + " TrackPutInfo entries, got "
                                + result.size());
            }
            return List.copyOf(result);
        }

        private void addTrackPutInfos(BlockEntity blockEntity, List<Object> putInfos)
                throws ReflectiveOperationException {
            addTrackPutInfo.invoke(blockEntity, putInfos);
        }
    }

    private record ApiResolution(TongDaApi api, String failure) {
    }
}
