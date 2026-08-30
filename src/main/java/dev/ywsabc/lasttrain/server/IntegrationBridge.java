package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/**
 * Optional integrations expressed through stable server commands.
 *
 * <p>This avoids linking the campaign JAR against implementation details of
 * beta gameplay mods. If an optional mod is absent or changes its command
 * surface, the core campaign still loads and keeps its save data intact.</p>
 */
public final class IntegrationBridge {
    private IntegrationBridge() {
    }

    public static void syncCampaignNumbers(MinecraftServer server, CampaignSavedData data) {
        if (!ModList.get().isLoaded("incontrol")) {
            return;
        }

        executeOptional(server, "incontrol setnumber lasttrain_day " + data.day());
        executeOptional(server, "incontrol setnumber lasttrain_threat " + data.threat());
        executeOptional(server, "incontrol setnumber lasttrain_players " + data.effectivePlayers());
        executeOptional(server, "incontrol setnumber lasttrain_attention " + data.attention());
        executeOptional(server, "incontrol setnumber lasttrain_pursuit " + data.pursuitDistance());
        executeOptional(server, "incontrol setnumber lasttrain_infection_stage "
                + data.infectionSample().stageIndex());
    }

    private static void executeOptional(MinecraftServer server, String command) {
        try {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(),
                    command);
        } catch (RuntimeException exception) {
            LastTrain.LOGGER.warn("Optional integration command failed: {}", command, exception);
        }
    }
}
