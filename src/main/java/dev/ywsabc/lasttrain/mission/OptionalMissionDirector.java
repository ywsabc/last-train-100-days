package dev.ywsabc.lasttrain.mission;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.route.RouteDirector;
import dev.ywsabc.lasttrain.route.RouteGeometry;
import dev.ywsabc.lasttrain.server.SableTrainTracker;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * World-side director for optional missions: prepares and reconciles the
 * salvage car structure and the rescue survivor entity, observes objective
 * completion, executes the durable reward outbox and runs deferred site
 * cleanups once the site chunks load again.
 */
public final class OptionalMissionDirector {
    static final int UPDATE_ALL = 3;
    static final String REWARD_OPERATION_KEY = "lasttrain_reward_operation";

    private static final int APRON_HALF_LENGTH = 5;
    private static final int CAR_HALF_LENGTH = 2;
    private static final int CAR_BUFFER_LENGTH = 3;
    private static final int CAR_HALF_WIDTH = 1;
    private static final int CAR_TRACK_GAP = 4;
    static final int SALVAGE_DAMAGE_POINTS = 6;

    private static final double FOLLOW_RADIUS = 24.0D;
    private static final double FOLLOW_RADIUS_SQUARED = FOLLOW_RADIUS * FOLLOW_RADIUS;
    private static final double SAFE_RADIUS = 5.0D;
    private static final double SAFE_RADIUS_SQUARED = SAFE_RADIUS * SAFE_RADIUS;

    private OptionalMissionDirector() {
    }

    public static void tick(MinecraftServer server, CampaignSavedData data) {
        int timedOut = data.settleOptionalTimeouts();
        if (timedOut > 0) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.lasttrain.optional_timed_out", timedOut),
                    false);
        }
        dispatchPendingRewards(server.overworld(), data);
        processPendingCleanups(server.overworld(), data);
        for (ActiveMission mission : data.optionalMissions()) {
            tickMission(server, data, mission);
        }
    }

    private static void tickMission(
            MinecraftServer server,
            CampaignSavedData data,
            ActiveMission mission) {
        switch (mission.stage()) {
            case ACTIVE -> {
                if (mission.site() == null) {
                    BlockPos anchor = RouteDirector.missionAnchor(data, mission.routeSegment());
                    data.assignOptionalMissionSite(mission.id(), anchor.offset(0, 0, CAR_TRACK_GAP));
                    return;
                }
                BlockPos site = mission.site();
                if (data.generatedRouteSegment()
                                < RouteGeometry.missionSegment(mission.routeSegment())
                        || !server.overworld().hasChunkAt(site)) {
                    return;
                }
                if (!mission.worldPrepared()) {
                    if (!prepare(server.overworld(), mission)) {
                        return;
                    }
                    data.markOptionalMissionWorldPrepared(mission.id());
                    mission = data.optionalMission(mission.id()).orElse(null);
                    if (mission == null) {
                        return;
                    }
                }
                observe(server.overworld(), data, mission);
                ActiveMission refreshed = data.optionalMission(mission.id()).orElse(null);
                if (refreshed != null && refreshed.stage() == MissionStage.READY_TO_TURN_IN) {
                    data.completeOptionalMission(mission.id());
                }
            }
            case READY_TO_TURN_IN -> data.completeOptionalMission(mission.id());
            case REWARD_PENDING, PROPOSED, COMPLETED, FAILED, SKIPPED -> {
            }
        }
    }

    private static boolean prepare(ServerLevel level, ActiveMission mission) {
        if (!MissionWorldDirector.worldPreparationAllowed()) {
            LastTrain.LOGGER.warn(
                    "Fault injected: optional mission world preparation failed for {}",
                    mission.id());
            return false;
        }
        try {
            return switch (mission.type()) {
                case SALVAGE_CAR -> prepareSalvageCar(level, mission);
                case RESCUE_SURVIVOR -> true;
                default -> false;
            };
        } catch (RuntimeException exception) {
            LastTrain.LOGGER.error(
                    "Could not prepare optional mission {} at {}",
                    mission.id(),
                    mission.site(),
                    exception);
            return false;
        }
    }

    private static void observe(ServerLevel level, CampaignSavedData data, ActiveMission mission) {
        switch (mission.type()) {
            case SALVAGE_CAR -> repairPreparedSalvage(level, mission);
            case RESCUE_SURVIVOR -> observeRescue(level, data, mission);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // Salvage car
    // ------------------------------------------------------------------

    /** The damage points of a salvage car, in stable index order. */
    static BlockPos salvageDamageOffset(int index) {
        int x = index % 3 - 1;
        int z = index < 3 ? -1 : 1;
        return new BlockPos(x, 1, z);
    }

    /** Reverse lookup of a damage index at an absolute position, or -1. */
    static int salvageDamageIndexAt(BlockPos site, BlockPos pos) {
        int dx = pos.getX() - site.getX();
        int dy = pos.getY() - site.getY();
        int dz = pos.getZ() - site.getZ();
        if (dy != 1 || Math.abs(dz) != 1 || Math.abs(dx) > 1) {
            return -1;
        }
        int index = (dz < 0 ? 0 : 3) + dx + 1;
        return index >= 0 && index < SALVAGE_DAMAGE_POINTS ? index : -1;
    }

    private static boolean prepareSalvageCar(ServerLevel level, ActiveMission mission) {
        BlockPos site = mission.site();
        // Small apron so the car never floats over air next to the track deck.
        for (int x = -APRON_HALF_LENGTH; x <= APRON_HALF_LENGTH; x++) {
            for (int z = -CAR_TRACK_GAP; z <= CAR_TRACK_GAP; z++) {
                if (Math.abs(z + CAR_TRACK_GAP) <= 1) {
                    continue;
                }
                level.setBlock(
                        site.offset(x, -1, z),
                        Blocks.STONE_BRICKS.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
        int damageCount = Math.min(SALVAGE_DAMAGE_POINTS, mission.target());
        for (int x = -CAR_BUFFER_LENGTH; x <= CAR_BUFFER_LENGTH; x++) {
            for (int z = -CAR_HALF_WIDTH; z <= CAR_HALF_WIDTH; z++) {
                level.setBlock(site.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), UPDATE_ALL);
            }
        }
        for (int x = -CAR_HALF_LENGTH; x <= CAR_HALF_LENGTH; x++) {
            for (int z = -CAR_HALF_WIDTH; z <= CAR_HALF_WIDTH; z += 2) {
                BlockPos wall = site.offset(x, 1, z);
                int index = salvageDamageIndexAt(site, wall);
                Block wallBlock = index >= 0 && index < damageCount ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS;
                level.setBlock(wall, wallBlock.defaultBlockState(), UPDATE_ALL);
            }
        }
        for (int x = -CAR_BUFFER_LENGTH; x <= CAR_BUFFER_LENGTH; x++) {
            for (int z = -CAR_HALF_WIDTH; z <= CAR_HALF_WIDTH; z++) {
                if (Math.abs(x) == CAR_BUFFER_LENGTH) {
                    level.setBlock(site.offset(x, 1, z), Blocks.STONE_BRICKS.defaultBlockState(), UPDATE_ALL);
                }
                level.setBlock(site.offset(x, 2, z), Blocks.AIR.defaultBlockState(), UPDATE_ALL);
            }
        }
        return true;
    }

    /**
     * Restores the structure and reconciles block states with the persisted
     * repair index set: repaired indices are iron, the rest stay damaged.
     * Only blocks at reserved offsets in the car palette are touched, so
     * ordinary terrain and player blocks are never matched or overwritten.
     */
    private static void repairPreparedSalvage(ServerLevel level, ActiveMission mission) {
        BlockPos site = mission.site();
        int damageCount = Math.min(SALVAGE_DAMAGE_POINTS, mission.target());
        for (int index = 0; index < damageCount; index++) {
            BlockPos point = site.offset(salvageDamageOffset(index));
            Block expected = mission.hasRepairIndex(index) ? Blocks.IRON_BLOCK : Blocks.CRACKED_STONE_BRICKS;
            if (!level.getBlockState(point).is(expected)) {
                level.setBlock(point, expected.defaultBlockState(), UPDATE_ALL);
            }
        }
        for (int x = -CAR_BUFFER_LENGTH; x <= CAR_BUFFER_LENGTH; x++) {
            for (int z = -CAR_HALF_WIDTH; z <= CAR_HALF_WIDTH; z++) {
                BlockPos floor = site.offset(x, 0, z);
                if (!isSalvageCarBlock(level.getBlockState(floor))) {
                    level.setBlock(floor, Blocks.STONE_BRICKS.defaultBlockState(), UPDATE_ALL);
                }
                if (Math.abs(x) <= CAR_HALF_LENGTH && Math.abs(z) == 1) {
                    BlockPos wall = site.offset(x, 1, z);
                    if (!isSalvageCarBlock(level.getBlockState(wall))) {
                        level.setBlock(wall, Blocks.STONE_BRICKS.defaultBlockState(), UPDATE_ALL);
                    }
                }
                if (Math.abs(x) == CAR_BUFFER_LENGTH) {
                    BlockPos buffer = site.offset(x, 1, z);
                    if (!isSalvageCarBlock(level.getBlockState(buffer))) {
                        level.setBlock(buffer, Blocks.STONE_BRICKS.defaultBlockState(), UPDATE_ALL);
                    }
                }
            }
        }
    }

    static boolean isSalvageCarBlock(BlockState state) {
        return state.is(Blocks.STONE_BRICKS) || state.is(Blocks.CRACKED_STONE_BRICKS) || state.is(Blocks.IRON_BLOCK);
    }

    static boolean hasProtectedSalvageBlocks(ActiveMission mission) {
        return mission != null
                && mission.type() == MissionType.SALVAGE_CAR
                && mission.site() != null
                && mission.worldPrepared()
                && mission.stage() == MissionStage.ACTIVE;
    }

    /** Breaks, drops and pistons cannot touch the car body while it runs. */
    static boolean isProtectedSalvageBlock(ActiveMission mission, BlockPos pos) {
        if (!hasProtectedSalvageBlocks(mission)) {
            return false;
        }
        BlockPos site = mission.site();
        int dx = pos.getX() - site.getX();
        int dy = pos.getY() - site.getY();
        int dz = pos.getZ() - site.getZ();
        if (dy == 0) {
            return Math.abs(dx) <= CAR_BUFFER_LENGTH && Math.abs(dz) <= CAR_HALF_WIDTH;
        }
        if (dy == 1) {
            return (Math.abs(dx) <= CAR_HALF_LENGTH && Math.abs(dz) == 1)
                    || (Math.abs(dx) == CAR_BUFFER_LENGTH && Math.abs(dz) <= CAR_HALF_WIDTH);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Rescue survivor
    // ------------------------------------------------------------------

    private static void observeRescue(ServerLevel level, CampaignSavedData data, ActiveMission mission) {
        String tag = MissionWorldDirector.survivorEntityTag(mission);
        Villager survivor = findSurvivor(level, tag);
        if (survivor == null) {
            spawnSurvivor(level, mission, tag);
            return;
        }
        ServerPlayer nearest = nearestFollowingPlayer(level, survivor);
        if (nearest != null) {
            survivor.getNavigation().moveTo(nearest, 1.1D);
            survivor.getLookControl().setLookAt(nearest, 30.0F, 30.0F);
        }
        Vec3 safePoint = safePoint(level, data);
        if (safePoint != null && survivor.position().distanceToSqr(safePoint) <= SAFE_RADIUS_SQUARED) {
            if (data.recordSurvivorRescued(mission.id())) {
                survivor.discard();
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.translatable("message.lasttrain.survivor_found"),
                        false);
            }
        }
    }

    private static Villager findSurvivor(ServerLevel level, String tag) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager villager
                    && !villager.isRemoved()
                    && villager.getTags().contains(tag)) {
                return villager;
            }
        }
        return null;
    }

    private static boolean spawnSurvivor(ServerLevel level, ActiveMission mission, String tag) {
        Villager survivor = EntityType.VILLAGER.create(level);
        if (survivor == null) {
            return false;
        }
        BlockPos site = mission.site();
        survivor.moveTo(
                site.getX() + 0.5D,
                site.getY(),
                site.getZ() + 0.5D,
                0.0F,
                0.0F);
        survivor.setPersistenceRequired();
        survivor.addTag(tag);
        survivor.setCustomName(Component.translatable("mission.lasttrain.rescue_survivor"));
        return level.addFreshEntity(survivor);
    }

    private static ServerPlayer nearestFollowingPlayer(ServerLevel level, Villager survivor) {
        ServerPlayer nearest = null;
        double nearestDistance = FOLLOW_RADIUS_SQUARED;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double distance = player.position().distanceToSqr(survivor.position());
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /** The train, when bound, otherwise the starter station safe point. */
    private static Vec3 safePoint(ServerLevel level, CampaignSavedData data) {
        if (data.starterTrainAssembled() && data.starterTrainSublevelId() != null) {
            Optional<Vec3> train = SableTrainTracker.position(level, data.starterTrainSublevelId());
            if (train.isPresent()) {
                return train.orElseThrow();
            }
        }
        return Vec3.atBottomCenterOf(data.starterStationAnchor());
    }

    // ------------------------------------------------------------------
    // Durable reward outbox
    // ------------------------------------------------------------------

    private static void dispatchPendingRewards(ServerLevel level, CampaignSavedData data) {
        for (CampaignSavedData.RewardReceipt receipt : data.rewardReceipts()) {
            String operationId = RewardOutboxPolicy.operationId(receipt.missionId());
            BlockPos cratePos = rewardCratePos(data);
            boolean markerPresent = crateMarkerPresent(level, cratePos, operationId);
            RewardOutboxPolicy.GrantDecision decision =
                    RewardOutboxPolicy.reconcile(receipt.state(), markerPresent);
            if (decision == RewardOutboxPolicy.GrantDecision.NO_OP) {
                continue;
            }
            if (!level.hasChunkAt(cratePos)) {
                continue;
            }
            ChestBlockEntity chest = ensureRewardCrate(level, cratePos);
            if (chest == null) {
                continue;
            }
            RewardOutboxPolicy.CrateAccess access = new ChestCrateAccess(chest);
            boolean complete = RewardOutboxPolicy.fillCrate(
                    access,
                    operationId,
                    RewardOutboxPolicy.payload(receipt.type(), receipt.missionId()));
            if (complete && data.markRewardClaimed(receipt.missionId())) {
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.translatable("message.lasttrain.optional_reward_delivered"),
                        false);
            }
        }
    }

    /** The team supply crate sits near the starter station anchor. */
    private static BlockPos rewardCratePos(CampaignSavedData data) {
        return data.starterStationAnchor().offset(-7, 1, 3);
    }

    private static boolean crateMarkerPresent(ServerLevel level, BlockPos cratePos, String operationId) {
        BlockEntity blockEntity = level.getBlockEntity(cratePos);
        return blockEntity instanceof ChestBlockEntity chest
                && operationId.equals(chest.getPersistentData().getString(REWARD_OPERATION_KEY));
    }

    private static ChestBlockEntity ensureRewardCrate(ServerLevel level, BlockPos cratePos) {
        BlockEntity blockEntity = level.getBlockEntity(cratePos);
        if (blockEntity instanceof ChestBlockEntity chest) {
            return chest;
        }
        if (!level.getBlockState(cratePos).isAir()) {
            return null;
        }
        level.setBlock(cratePos, Blocks.CHEST.defaultBlockState(), UPDATE_ALL);
        blockEntity = level.getBlockEntity(cratePos);
        return blockEntity instanceof ChestBlockEntity chest ? chest : null;
    }

    private static final class ChestCrateAccess implements RewardOutboxPolicy.CrateAccess {
        private final ChestBlockEntity chest;

        private ChestCrateAccess(ChestBlockEntity chest) {
            this.chest = chest;
        }

        @Override
        public List<RewardOutboxPolicy.RewardItem> contents() {
            List<RewardOutboxPolicy.RewardItem> items = new java.util.ArrayList<>();
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                net.minecraft.world.item.ItemStack stack = chest.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                Optional<String> credential = credentialMissionIdOf(stack);
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getResourceKey(stack.getItem())
                        .ifPresent(key -> items.add(new RewardOutboxPolicy.RewardItem(
                                key.location().toString(),
                                stack.getCount(),
                                credential)));
            }
            return items;
        }

        @Override
        public void addStack(RewardOutboxPolicy.RewardItem item) {
            net.minecraft.world.item.ItemStack stack = materializeReward(item);
            if (stack == null) {
                return;
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                net.minecraft.world.item.ItemStack existing = chest.getItem(slot);
                if (existing.isEmpty()) {
                    chest.setItem(slot, stack.copy());
                    return;
                }
                if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(existing, stack)
                        && existing.getCount() + stack.getCount() <= existing.getMaxStackSize()) {
                    existing.grow(stack.getCount());
                    return;
                }
            }
            // Crate is full of unrelated content: drop nothing, keep PENDING.
            LastTrain.LOGGER.warn(
                    "Optional reward crate is full; the outbox stays pending until space frees up");
        }

        @Override
        public String operationId() {
            return chest.getPersistentData().getString(REWARD_OPERATION_KEY);
        }

        @Override
        public void markOperation(String operationId) {
            chest.getPersistentData().putString(REWARD_OPERATION_KEY, operationId);
            chest.setChanged();
        }
    }

    /**
     * Materializes a registry-free reward entry into an item stack and
     * enforces the live item stack limit: an entry above its limit (such as a
     * count-10 minecart) is rejected and logged instead of being placed.
     * The salvage credential is a single paper item carrying the mission id
     * tag, the verifiable permanent-upgrade credential. The functional
     * railcar attachment itself is a later milestone.
     */
    private static net.minecraft.world.item.ItemStack materializeReward(
            RewardOutboxPolicy.RewardItem item) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.resources.ResourceLocation.parse(item.itemId());
        net.minecraft.world.item.Item registered =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
        if (registered == null) {
            LastTrain.LOGGER.warn(
                    "Optional reward references unknown item {}; the entry is skipped",
                    item.itemId());
            return null;
        }
        net.minecraft.world.item.ItemStack stack =
                new net.minecraft.world.item.ItemStack(registered, item.count());
        if (stack.getCount() > stack.getMaxStackSize()) {
            LastTrain.LOGGER.warn(
                    "Optional reward {} of {} exceeds the item stack limit {}; the entry is rejected",
                    item.itemId(),
                    item.count(),
                    stack.getMaxStackSize());
            return null;
        }
        if (item.credentialMissionId().isPresent()) {
            stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.translatable("item.lasttrain.salvage_credential"));
            stack.set(
                    net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(
                            List.of(Component.translatable("item.lasttrain.salvage_credential.lore"))));
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString(
                    RewardOutboxPolicy.CREDENTIAL_TAG,
                    item.credentialMissionId().orElseThrow());
            stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
        }
        return stack;
    }

    private static Optional<String> credentialMissionIdOf(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.nbt.CompoundTag tag = stack
                .getOrDefault(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.EMPTY)
                .copyTag();
        if (tag == null || !tag.contains(RewardOutboxPolicy.CREDENTIAL_TAG)) {
            return Optional.empty();
        }
        return Optional.of(tag.getString(RewardOutboxPolicy.CREDENTIAL_TAG));
    }

    // ------------------------------------------------------------------
    // Deferred site cleanup
    // ------------------------------------------------------------------

    private static void processPendingCleanups(ServerLevel level, CampaignSavedData data) {
        for (CampaignSavedData.PendingSiteCleanup cleanup : data.pendingSiteCleanups()) {
            BlockPos site = cleanup.site();
            if (site == null || !level.hasChunkAt(site)) {
                continue;
            }
            clearOptionalSite(level, cleanup);
            data.completePendingCleanup(cleanup.missionId());
        }
    }

    /** Idempotent: only mission-owned blocks and entities are removed. */
    private static void clearOptionalSite(ServerLevel level, CampaignSavedData.PendingSiteCleanup cleanup) {
        BlockPos site = cleanup.site();
        switch (cleanup.type()) {
            case SALVAGE_CAR -> {
                for (int x = -CAR_BUFFER_LENGTH; x <= CAR_BUFFER_LENGTH; x++) {
                    for (int z = -CAR_HALF_WIDTH; z <= CAR_HALF_WIDTH; z++) {
                        BlockPos floor = site.offset(x, 0, z);
                        if (isSalvageCarBlock(level.getBlockState(floor))) {
                            level.setBlock(floor, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                        }
                        for (int y = 1; y <= 2; y++) {
                            BlockPos upper = site.offset(x, y, z);
                            if (isSalvageCarBlock(level.getBlockState(upper))) {
                                level.setBlock(upper, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
                            }
                        }
                    }
                }
            }
            case RESCUE_SURVIVOR -> {
                String tag = MissionWorldDirector.survivorEntityTag(cleanup.missionId());
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof Villager survivor
                            && survivor.getTags().contains(tag)) {
                        survivor.discard();
                    }
                }
            }
            default -> {
            }
        }
    }
}
