package dev.ywsabc.lasttrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.mission.MissionWorldDirector;
import dev.ywsabc.lasttrain.server.IntegrationBridge;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class LastTrainCommands {
    private LastTrainCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("lasttrain")
                .then(Commands.literal("status")
                        .executes(LastTrainCommands::status))
                .then(Commands.literal("start")
                        .requires(source -> source.hasPermission(2))
                        .executes(LastTrainCommands::start))
                .then(Commands.literal("advance_day")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 100))
                                .executes(LastTrainCommands::advanceDay)))
                .then(Commands.literal("route")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("advance")
                                .then(Commands.argument("segments", IntegerArgumentType.integer(1, 10_000))
                                        .executes(LastTrainCommands::advanceRoute))))
                .then(Commands.literal("mission")
                        .then(Commands.literal("status")
                                .executes(LastTrainCommands::missionStatus))
                        .then(Commands.literal("create")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            Arrays.stream(MissionType.values())
                                                    .map(MissionType::serializedName)
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(LastTrainCommands::createMission)))
                        .then(Commands.literal("progress")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000))
                                        .executes(LastTrainCommands::progressMission)))
                        .then(Commands.literal("turn_in")
                                .executes(LastTrainCommands::turnInMission))
                        .then(Commands.literal("clear")
                                .requires(source -> source.hasPermission(2))
                                .executes(LastTrainCommands::clearMission))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "command.lasttrain.status",
                        data.day(),
                        CampaignSavedData.FINAL_DAY,
                        data.status().name(),
                        data.routeSegment(),
                        data.threat()),
                false);
        return data.day();
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        if (!data.start()) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.already_started"));
            return 0;
        }
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        context.getSource().sendSuccess(() -> Component.translatable("command.lasttrain.started"), true);
        return 1;
    }

    private static int advanceDay(CommandContext<CommandSourceStack> context) {
        int days = IntegerArgumentType.getInteger(context, "days");
        CampaignSavedData data = data(context);
        data.advanceDays(days);
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.day_set", data.day()),
                true);
        return data.day();
    }

    private static int advanceRoute(CommandContext<CommandSourceStack> context) {
        int segments = IntegerArgumentType.getInteger(context, "segments");
        CampaignSavedData data = data(context);
        data.advanceRoute(segments);
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.route_advanced", data.routeSegment()),
                true);
        return data.routeSegment();
    }

    private static int missionStatus(CommandContext<CommandSourceStack> context) {
        ActiveMission mission = data(context).activeMission();
        if (mission == null) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.lasttrain.mission.none"),
                    false);
            return 0;
        }
        context.getSource().sendSuccess(() -> missionSummary(mission), false);
        return mission.progress();
    }

    private static int createMission(CommandContext<CommandSourceStack> context) {
        String rawType = StringArgumentType.getString(context, "type");
        MissionType type = MissionType.parse(rawType).orElse(null);
        if (type == null) {
            String choices = Arrays.stream(MissionType.values())
                    .map(MissionType::serializedName)
                    .collect(Collectors.joining(", "));
            context.getSource().sendFailure(
                    Component.translatable("command.lasttrain.mission.invalid_type", choices));
            return 0;
        }

        CampaignSavedData data = data(context);
        if (!data.createMission(type)) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.exists"));
            return 0;
        }
        context.getSource().sendSuccess(() -> missionSummary(data.activeMission()), true);
        return 1;
    }

    private static int progressMission(CommandContext<CommandSourceStack> context) {
        int amount = IntegerArgumentType.getInteger(context, "amount");
        CampaignSavedData data = data(context);
        if (!data.addMissionProgress(amount)) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.no_progress"));
            return 0;
        }
        context.getSource().sendSuccess(() -> missionSummary(data.activeMission()), true);
        return data.activeMission().progress();
    }

    private static int turnInMission(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        ActiveMission mission = data.activeMission();
        if (mission == null || mission.stage() != MissionStage.READY_TO_TURN_IN) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.not_ready"));
            return 0;
        }
        if (!MissionWorldDirector.clearMissionWorld(
                context.getSource().getServer().overworld(),
                mission)) {
            context.getSource().sendFailure(missionSiteUnavailable(mission));
            return 0;
        }
        if (!data.turnInMission()) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.not_ready"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.mission.completed"),
                true);
        IntegrationBridge.syncCampaignNumbers(context.getSource().getServer(), data);
        return 1;
    }

    private static int clearMission(CommandContext<CommandSourceStack> context) {
        CampaignSavedData data = data(context);
        ActiveMission mission = data.activeMission();
        if (mission == null) {
            context.getSource().sendFailure(Component.translatable("command.lasttrain.mission.none"));
            return 0;
        }
        if (!MissionWorldDirector.clearMissionWorld(
                context.getSource().getServer().overworld(),
                mission)) {
            context.getSource().sendFailure(missionSiteUnavailable(mission));
            return 0;
        }
        data.clearMission();
        context.getSource().sendSuccess(
                () -> Component.translatable("command.lasttrain.mission.cleared"),
                true);
        return 1;
    }

    private static CampaignSavedData data(CommandContext<CommandSourceStack> context) {
        return CampaignSavedData.get(context.getSource().getServer());
    }

    private static Component missionSummary(ActiveMission mission) {
        return Component.translatable(
                "command.lasttrain.mission.status",
                Component.translatable("mission.lasttrain." + mission.type().serializedName()),
                mission.progress(),
                mission.target(),
                mission.routeSegment(),
                mission.stage().name());
    }

    private static Component missionSiteUnavailable(ActiveMission mission) {
        if (mission.site() == null) {
            return Component.translatable(
                    "command.lasttrain.mission.site_unavailable", "?", "?", "?");
        }
        return Component.translatable(
                "command.lasttrain.mission.site_unavailable",
                mission.site().getX(),
                mission.site().getY(),
                mission.site().getZ());
    }
}
