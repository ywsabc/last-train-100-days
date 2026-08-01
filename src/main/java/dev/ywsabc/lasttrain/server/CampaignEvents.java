package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionWorldDirector;
import dev.ywsabc.lasttrain.route.RouteDirector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class CampaignEvents {
    private CampaignEvents() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        CampaignSavedData data = CampaignSavedData.get(server);
        data.initialize(server.overworld().getSeed());
        WorldBootstrap.ensureStarterStation(server.overworld(), data);
        SimurailTrainBootstrap.ensureLayout(server.overworld(), data);
        IntegrationBridge.syncCampaignNumbers(server, data);
        LastTrain.LOGGER.info(
                "Loaded campaign {} (status={}, day={}, route={})",
                data.campaignId(),
                data.status(),
                data.day(),
                data.routeSegment());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        CampaignSavedData data = CampaignSavedData.get(server);
        SimurailTrainBootstrap.tick(server.overworld(), data, server.getTickCount());
        RouteDirector.tick(server, data, server.getTickCount());
        MissionWorldDirector.tick(server, data, server.getTickCount());

        boolean hasActivePlayer = server.getPlayerList().getPlayers().stream()
                .anyMatch(player -> !player.isSpectator());
        if (!hasActivePlayer) {
            return;
        }

        CampaignSavedData.TickOutcome outcome = data.tick();
        if (outcome != CampaignSavedData.TickOutcome.NONE) {
            IntegrationBridge.syncCampaignNumbers(server, data);
        }
        switch (outcome) {
            case DAY_ADVANCED -> broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
            case DAY_ADVANCED_WITH_MISSION -> {
                broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
                ActiveMission mission = data.activeMission();
                if (mission != null) {
                    broadcast(server, Component.translatable(
                            "message.lasttrain.mission_generated",
                            Component.translatable("mission.lasttrain." + mission.type().serializedName()),
                            mission.routeSegment()));
                }
            }
            case CAMPAIGN_COMPLETED ->
                    broadcast(server, Component.translatable("message.lasttrain.campaign_completed"));
            case NONE -> {
            }
        }
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }
}
