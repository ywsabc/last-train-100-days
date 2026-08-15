package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.integration.TaczStarterKit;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-thread-only player provisioning and starter-train return lifecycle. */
public final class PlayerEvents {
    /**
     * Identity keys prevent two logical servers in one JVM from sharing a
     * queue. Values retain UUIDs and timing primitives only, never player or
     * world objects.
     */
    private static final Map<MinecraftServer, Map<UUID, PendingReturn>> PENDING_RETURNS =
            new IdentityHashMap<>();

    private PlayerEvents() {
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.isSpectator()) {
            removePending(player);
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(player.getServer());
        data.registerTeamMember(player.getUUID());
        data.observeCaptainOnline(
                isCaptainOnline(player.getServer(), data),
                player.getServer().overworld().getGameTime());
        issueStarterSupplies(player, data);
        sendCampaignSummary(player, data);
        enqueueReturn(player);
    }

    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        CampaignSavedData data = CampaignSavedData.get(player.getServer());
        sendCampaignSummary(player, data);
        enqueueReturn(player);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CampaignSavedData data = CampaignSavedData.get(player.getServer());
            if (data.isCaptain(player.getUUID())) {
                data.observeCaptainOnline(
                        false,
                        player.getServer().overworld().getGameTime());
            }
            removePending(player);
        }
    }

    private static boolean isCaptainOnline(
            MinecraftServer server,
            CampaignSavedData data) {
        UUID captain = data.captainId();
        ServerPlayer player = captain == null
                ? null
                : server.getPlayerList().getPlayer(captain);
        return player != null && !player.isSpectator();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Map<UUID, PendingReturn> pendingByPlayer = PENDING_RETURNS.get(server);
        if (pendingByPlayer == null || pendingByPlayer.isEmpty()) {
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(server);
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        UUID starterTrainId = data.starterTrainAssembled()
                ? data.starterTrainSublevelId()
                : null;

        Iterator<Map.Entry<UUID, PendingReturn>> iterator = pendingByPlayer.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingReturn> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            PendingReturn pending = entry.getValue();
            int elapsed = Math.max(0, now - pending.queuedAtTick);
            boolean online = player != null;
            boolean spectator = online && player.isSpectator();
            boolean trackingStarter = elapsed >= PlayerReturnPolicy.INITIAL_DELAY_TICKS
                    && online
                    && starterTrainId != null
                    && SableTrainTracker.isTracking(player, starterTrainId);
            boolean attemptDue = now >= pending.nextAttemptTick;

            Optional<Vec3> gatheringPoint = Optional.empty();
            if (online
                    && !spectator
                    && !trackingStarter
                    && starterTrainId != null
                    && elapsed >= PlayerReturnPolicy.INITIAL_DELAY_TICKS
                    && elapsed < PlayerReturnPolicy.MAX_WAIT_TICKS
                    && attemptDue) {
                List<Vec3> gatheringPoints = SableTrainTracker.gatheringPoints(level, starterTrainId)
                        .stream()
                        .filter(point -> level.isInWorldBounds(BlockPos.containing(point)))
                        .toList();
                gatheringPoint = selectUnoccupiedGatheringPoint(player, gatheringPoints);
            }

            PlayerReturnPolicy.Decision decision = PlayerReturnPolicy.decide(
                    elapsed,
                    online,
                    spectator,
                    trackingStarter,
                    gatheringPoint.isPresent(),
                    attemptDue);
            switch (decision) {
                case WAIT -> {
                }
                case DISCARD, COMPLETE_IN_PLACE -> iterator.remove();
                case TRY_TRAIN -> {
                    Vec3 target = gatheringPoint.orElseThrow();
                    // Synchronous chunk access and teleport both occur on the
                    // logical server thread. Sable gets the following entity
                    // tick to bind the player to the moving body.
                    level.getChunkAt(BlockPos.containing(target));
                    player.stopRiding();
                    boolean teleported = player.teleportTo(
                            level,
                            target.x,
                            target.y,
                            target.z,
                            Set.of(),
                            player.getYRot(),
                            player.getXRot());
                    pending.nextAttemptTick = now + PlayerReturnPolicy.RETRY_INTERVAL_TICKS;
                    if (teleported) {
                        player.setDeltaMovement(Vec3.ZERO);
                        player.fallDistance = 0.0F;
                        if (!pending.trainTeleportAnnounced) {
                            player.sendSystemMessage(Component.translatable(
                                    "message.lasttrain.returning_to_train"));
                            pending.trainTeleportAnnounced = true;
                        }
                    }
                }
                case FALLBACK_TO_STATION -> {
                    if (teleportToStarterStation(player, level, data)) {
                        player.sendSystemMessage(Component.translatable(
                                "message.lasttrain.returned_to_station"));
                        iterator.remove();
                    }
                }
            }
        }

        if (pendingByPlayer.isEmpty()) {
            PENDING_RETURNS.remove(server);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING_RETURNS.remove(event.getServer());
    }

    private static void issueStarterSupplies(ServerPlayer player, CampaignSavedData data) {
        if (data.claimStarterKit(player.getUUID())) {
            give(player, new ItemStack(Items.BREAD, 8));
            give(player, new ItemStack(Items.BAKED_POTATO, 8));
            give(player, new ItemStack(Items.TORCH, 24));
            give(player, new ItemStack(Items.IRON_INGOT, 6));
            give(player, new ItemStack(Items.STONE_PICKAXE));
            give(player, new ItemStack(Items.STONE_AXE));
            player.sendSystemMessage(Component.translatable("message.lasttrain.starter_kit"));
        }

        if (!data.hasClaimedStarterGun(player.getUUID())) {
            List<ItemStack> gunKit = TaczStarterKit.create(player.registryAccess());
            if (gunKit.isEmpty()) {
                player.sendSystemMessage(Component.translatable("message.lasttrain.starter_gun_pending"));
            } else {
                gunKit.forEach(stack -> give(player, stack));
                data.claimStarterGun(player.getUUID());
                player.sendSystemMessage(Component.translatable("message.lasttrain.starter_gun"));
            }
        }
    }

    private static void sendCampaignSummary(ServerPlayer player, CampaignSavedData data) {
        player.sendSystemMessage(Component.translatable(
                "message.lasttrain.personal_status",
                data.day(),
                CampaignSavedData.FINAL_DAY,
                Component.translatable("campaign.lasttrain.status."
                        + data.status().name().toLowerCase(Locale.ROOT)),
                data.routeSegment(),
                data.threat(),
                data.attention(),
                data.pursuitDistance()));
        ActiveMission mission = data.activeMission();
        if (mission != null) {
            player.sendSystemMessage(Component.translatable(
                    "message.lasttrain.personal_mission",
                    Component.translatable("mission.lasttrain." + mission.type().serializedName()),
                    mission.progress(),
                    mission.target(),
                    mission.routeSegment(),
                    Component.translatable("mission.lasttrain.stage."
                            + mission.stage().name().toLowerCase(Locale.ROOT))));
        }
    }

    private static void enqueueReturn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        int now = server.getTickCount();
        PENDING_RETURNS
                .computeIfAbsent(server, ignored -> new HashMap<>())
                .put(player.getUUID(), new PendingReturn(now));
    }

    private static void removePending(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        Map<UUID, PendingReturn> pendingByPlayer = PENDING_RETURNS.get(server);
        if (pendingByPlayer == null) {
            return;
        }
        pendingByPlayer.remove(player.getUUID());
        if (pendingByPlayer.isEmpty()) {
            PENDING_RETURNS.remove(server);
        }
    }

    private static boolean teleportToStarterStation(
            ServerPlayer player,
            ServerLevel level,
            CampaignSavedData data) {
        BlockPos safePos = data.starterStationBuilt()
                ? data.starterStationAnchor().offset(-7, 1, 3)
                : level.getSharedSpawnPos();
        Optional<BlockPos> resolved = findSafeReturnPosition(player, level, safePos);
        if (resolved.isEmpty() && !safePos.equals(level.getSharedSpawnPos())) {
            resolved = findSafeReturnPosition(
                    player,
                    level,
                    level.getSharedSpawnPos());
        }
        if (resolved.isEmpty()) {
            return false;
        }
        safePos = resolved.orElseThrow();
        level.getChunkAt(safePos);
        Vec3 target = Vec3.atBottomCenterOf(safePos);
        player.stopRiding();
        boolean teleported = player.teleportTo(
                level,
                target.x,
                target.y,
                target.z,
                Set.of(),
                player.getYRot(),
                player.getXRot());
        if (teleported) {
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
        }
        return teleported;
    }

    private static Optional<BlockPos> findSafeReturnPosition(
            ServerPlayer returningPlayer,
            ServerLevel level,
            BlockPos origin) {
        int[] verticalOffsets = {0, 1, -1, 2, -2};
        for (int verticalOffset : verticalOffsets) {
            for (int radius = 0; radius <= 6; radius++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;
                        }
                        BlockPos candidate = origin.offset(x, verticalOffset, z);
                        if (isSafeReturnPosition(returningPlayer, level, candidate)) {
                            return Optional.of(candidate);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isSafeReturnPosition(
            ServerPlayer returningPlayer,
            ServerLevel level,
            BlockPos feet) {
        if (!level.isInWorldBounds(feet) || !level.isInWorldBounds(feet.above())) {
            return false;
        }
        level.getChunkAt(feet);
        BlockPos floor = feet.below();
        if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
                || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above())
                        .getCollisionShape(level, feet.above())
                        .isEmpty()
                || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.above()).isEmpty()) {
            return false;
        }
        Vec3 target = Vec3.atBottomCenterOf(feet);
        return level.players().stream()
                .noneMatch(other -> other != returningPlayer
                        && !other.isSpectator()
                        && other.isAlive()
                        && other.position().distanceToSqr(target) < 0.64D);
    }

    private static Optional<Vec3> selectUnoccupiedGatheringPoint(
            ServerPlayer returningPlayer,
            List<Vec3> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int start = Math.floorMod(returningPlayer.getUUID().hashCode(), candidates.size());
        for (int offset = 0; offset < candidates.size(); offset++) {
            Vec3 candidate = candidates.get((start + offset) % candidates.size());
            boolean occupied = returningPlayer.serverLevel().players().stream()
                    .anyMatch(other -> other != returningPlayer
                            && !other.isSpectator()
                            && other.isAlive()
                            && other.position().distanceToSqr(candidate) < 0.64D);
            if (!occupied) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static final class PendingReturn {
        private final int queuedAtTick;
        private int nextAttemptTick;
        private boolean trainTeleportAnnounced;

        private PendingReturn(int queuedAtTick) {
            this.queuedAtTick = queuedAtTick;
            this.nextAttemptTick = queuedAtTick + PlayerReturnPolicy.INITIAL_DELAY_TICKS;
        }
    }
}
