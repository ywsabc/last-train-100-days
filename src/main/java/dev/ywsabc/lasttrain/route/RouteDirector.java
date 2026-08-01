package dev.ywsabc.lasttrain.route;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Incrementally materializes an unbounded, deterministic route continuity
 * corridor in front of the party.
 *
 * <p>The director generates at most one 64-block segment per second and only
 * keeps two segments ahead. It complements TongDa's richer generated railways
 * rather than keeping a huge permanently-loaded world.</p>
 */
public final class RouteDirector {
    private static final int UPDATE_ALL = 3;
    private static final int TICK_INTERVAL = 20;
    private static final int SEGMENTS_AHEAD = 2;
    private static boolean missingCreateTrackLogged;

    private RouteDirector() {
    }

    public static void tick(MinecraftServer server, CampaignSavedData data, int serverTick) {
        if (serverTick % TICK_INTERVAL != 0
                || !data.starterStationBuilt()
                || !ModList.get().isLoaded("create")) {
            return;
        }

        ServerLevel level = server.overworld();
        Block trackBlock = registeredBlock("create:track");
        if (trackBlock == Blocks.AIR) {
            if (!missingCreateTrackLogged) {
                missingCreateTrackLogged = true;
                LastTrain.LOGGER.error(
                        "Create is loaded but create:track is unavailable; route generation is paused");
            }
            return;
        }

        int occupiedSegment = occupiedSegment(level, data);
        if (occupiedSegment > data.routeSegment()) {
            boolean hadMission = data.activeMission() != null;
            data.advanceRouteTo(occupiedSegment);
            server.getPlayerList().broadcastSystemMessage(
                    Component.translatable(
                            "message.lasttrain.route_advanced",
                            data.routeSegment()),
                    false);
            if (!hadMission && data.activeMission() != null) {
                broadcastMission(server, data.activeMission());
            }
        }

        int desiredSegment = Math.max(
                SEGMENTS_AHEAD,
                Math.max(data.routeSegment(), occupiedSegment) + SEGMENTS_AHEAD);
        ActiveMission mission = data.activeMission();
        if (mission != null) {
            desiredSegment = Math.max(
                    desiredSegment,
                    RouteGeometry.missionSegment(mission.routeSegment()));
        }

        int nextSegment = data.generatedRouteSegment() + 1;
        if (nextSegment <= desiredSegment && generateSegment(level, data, trackBlock, nextSegment)) {
            data.markRouteSegmentGenerated(nextSegment);
            LastTrain.LOGGER.info(
                    "Generated guaranteed route segment {} (through x offset {})",
                    nextSegment,
                    RouteGeometry.segmentEndOffset(nextSegment));
        }
    }

    public static BlockPos missionAnchor(CampaignSavedData data, int missionRouteSegment) {
        BlockPos station = data.starterStationAnchor();
        return station.offset(
                RouteGeometry.missionCenterOffset(missionRouteSegment),
                1,
                0);
    }

    private static int occupiedSegment(ServerLevel level, CampaignSavedData data) {
        int farthest = data.routeSegment();
        int stationX = data.starterStationAnchor().getX();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            int offset = player.blockPosition().getX() - stationX;
            farthest = Math.max(farthest, RouteGeometry.segmentForOffset(offset));
        }
        return farthest;
    }

    private static boolean generateSegment(
            ServerLevel level,
            CampaignSavedData data,
            Block trackBlock,
            int segment) {
        BlockPos station = data.starterStationAnchor();
        int startOffset = RouteGeometry.segmentStartOffset(segment);
        int endOffset = RouteGeometry.segmentEndOffset(segment);
        BlockPos borderProbe = station.offset(endOffset, 1, 0);
        if (!level.getWorldBorder().isWithinBounds(borderProbe)) {
            LastTrain.LOGGER.warn(
                    "Route segment {} reaches the world border at {}; generation is paused",
                    segment,
                    borderProbe);
            return false;
        }

        BlockState track = trackBlock.defaultBlockState();
        for (int offset = startOffset; offset <= endOffset; offset++) {
            BlockPos deckCenter = station.offset(offset, 0, 0);
            for (int z = -1; z <= 1; z++) {
                level.setBlock(
                        deckCenter.offset(0, 0, z),
                        Blocks.POLISHED_ANDESITE.defaultBlockState(),
                        UPDATE_ALL);
            }
            level.setBlock(deckCenter.above(), track, UPDATE_ALL);

            if ((offset - startOffset) % 8 == 0) {
                placeSupport(level, deckCenter.offset(0, -1, -1));
                placeSupport(level, deckCenter.offset(0, -1, 1));
            }
        }

        if (segment % 4 == 0) {
            buildWaypointPlatform(level, station, endOffset - 18, endOffset);
        }
        return true;
    }

    private static void placeSupport(ServerLevel level, BlockPos top) {
        BlockPos cursor = top;
        int limit = Math.max(level.getMinBuildHeight(), top.getY() - 24);
        while (cursor.getY() >= limit && level.getBlockState(cursor).isAir()) {
            level.setBlock(cursor, Blocks.POLISHED_ANDESITE.defaultBlockState(), UPDATE_ALL);
            cursor = cursor.below();
        }
    }

    private static void buildWaypointPlatform(
            ServerLevel level,
            BlockPos station,
            int startOffset,
            int endOffset) {
        for (int offset = startOffset; offset <= endOffset; offset++) {
            for (int z = -4; z <= 4; z++) {
                if (z == 0) {
                    continue;
                }
                level.setBlock(
                        station.offset(offset, 0, z),
                        Blocks.STONE_BRICKS.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
        for (int offset : new int[]{startOffset + 2, endOffset - 2}) {
            for (int z : new int[]{-3, 3}) {
                level.setBlock(
                        station.offset(offset, 1, z),
                        Blocks.LANTERN.defaultBlockState(),
                        UPDATE_ALL);
            }
        }
    }

    private static Block registeredBlock(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        return BuiltInRegistries.BLOCK.containsKey(key)
                ? BuiltInRegistries.BLOCK.get(key)
                : Blocks.AIR;
    }

    private static void broadcastMission(MinecraftServer server, ActiveMission mission) {
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable(
                        "message.lasttrain.mission_generated",
                        Component.translatable(
                                "mission.lasttrain." + mission.type().serializedName()),
                        mission.routeSegment()),
                false);
    }
}
