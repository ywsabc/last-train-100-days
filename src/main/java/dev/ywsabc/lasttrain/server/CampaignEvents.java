package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionWorldDirector;
import dev.ywsabc.lasttrain.route.RouteDirector;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class CampaignEvents {
    private static Vec3 lastTrainPosition;
    private static int lastTrainPositionTick;
    private static TrainRecoveryPolicy.Directive lastTrainDirective;

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

        int activePlayers = (int) server.getPlayerList().getPlayers().stream()
                .filter(player -> !player.isSpectator())
                .count();
        observeTrainRecovery(server, data, activePlayers);
        if (activePlayers == 0) {
            return;
        }

        if (data.status() == CampaignStatus.NOT_STARTED) {
            UUID trainId = data.starterTrainSublevelId();
            boolean trainLocated = trainId != null
                    && SableTrainTracker.position(server.overworld(), trainId).isPresent();
            if (CampaignStartPolicy.shouldAutoStart(
                            activePlayers > 0,
                            data.starterStationBuilt(),
                            data.starterTrainAssembled(),
                            trainId != null,
                            trainLocated)
                    && data.start()) {
                IntegrationBridge.syncCampaignNumbers(server, data);
                broadcast(server, Component.translatable("message.lasttrain.campaign_started"));
            }
        }

        CampaignSavedData.TickOutcome outcome = data.tick(activePlayers);
        if (outcome != CampaignSavedData.TickOutcome.NONE
                || server.getTickCount() % 200 == 0) {
            IntegrationBridge.syncCampaignNumbers(server, data);
        }
        switch (outcome) {
            case DAY_ADVANCED -> broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
            case DAY_ADVANCED_WITH_MISSION -> {
                broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
                broadcastMission(server, data.activeMission());
            }
            case DAY_ADVANCED_WITH_FINALE -> {
                broadcast(server, Component.translatable("message.lasttrain.day_advanced", data.day()));
                broadcastFinale(server, data.activeMission());
            }
            case FINALE_MISSION_STARTED -> broadcastFinale(server, data.activeMission());
            case SIEGE_TRIGGERED -> broadcastSiege(server, data.activeMission());
            case FINAL_DAY_ELAPSED ->
                    broadcast(server, Component.translatable("message.lasttrain.final_day_elapsed"));
            case CAMPAIGN_COMPLETED ->
                    broadcast(server, Component.translatable("message.lasttrain.campaign_completed"));
            case NONE -> {
            }
        }
    }

    /**
     * Feeds world-side train facts into the recovery policy every tick. The
     * position cache lives here because it is a per-session observation, not
     * campaign state; safe-mode transitions inside the saved data still apply
     * while nobody is online.
     */
    private static void observeTrainRecovery(
            MinecraftServer server,
            CampaignSavedData data,
            int activePlayers) {
        UUID trainId = data.starterTrainSublevelId();
        boolean referenceKnown = trainId != null;
        Optional<Vec3> located = trainId == null
                ? Optional.empty()
                : SableTrainTracker.position(server.overworld(), trainId);

        boolean moving = false;
        boolean playerInDanger = false;
        if (located.isPresent()) {
            Vec3 position = located.orElseThrow();
            int currentTick = server.getTickCount();
            moving = lastTrainPosition != null
                    && currentTick - lastTrainPositionTick == 1
                    && TrainRecoveryPolicy.isMoving(
                            position.distanceToSqr(lastTrainPosition));
            lastTrainPosition = position;
            lastTrainPositionTick = currentTick;
            playerInDanger = server.getPlayerList().getPlayers().stream()
                    .anyMatch(player -> !player.isSpectator()
                            && TrainRecoveryPolicy.playerInDanger(
                                    player.distanceToSqr(position)));
        } else {
            lastTrainPosition = null;
        }

        TrainRecoveryPolicy.Directive directive = data.observeTrain(
                SimurailTrainBootstrap.hasVehicleStack(),
                referenceKnown,
                located.isPresent(),
                moving,
                playerInDanger,
                activePlayers);
        if (directive != lastTrainDirective) {
            lastTrainDirective = directive;
            LastTrain.LOGGER.info("Train recovery assessment: {}", directive);
        }
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static void broadcastMission(MinecraftServer server, ActiveMission mission) {
        if (mission == null) {
            return;
        }
        broadcast(server, Component.translatable(
                "message.lasttrain.mission_generated",
                Component.translatable("mission.lasttrain." + mission.type().serializedName()),
                mission.routeSegment()));
    }

    private static void broadcastFinale(MinecraftServer server, ActiveMission mission) {
        if (mission == null) {
            return;
        }
        broadcast(server, Component.translatable(
                "message.lasttrain.finale_started",
                mission.target(),
                mission.routeSegment()));
    }

    private static void broadcastSiege(MinecraftServer server, ActiveMission mission) {
        if (mission == null) {
            return;
        }
        broadcast(server, Component.translatable(
                "message.lasttrain.siege_triggered",
                mission.target(),
                mission.routeSegment()));
    }
}
