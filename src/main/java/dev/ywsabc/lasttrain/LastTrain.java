package dev.ywsabc.lasttrain;

import com.mojang.logging.LogUtils;
import dev.ywsabc.lasttrain.command.LastTrainCommands;
import dev.ywsabc.lasttrain.integration.TaczGunfireBridge;
import dev.ywsabc.lasttrain.server.CampaignEvents;
import dev.ywsabc.lasttrain.server.PlayerEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Server-authoritative campaign glue for Last Train: 100 Days.
 *
 * <p>The mod intentionally owns only state that cannot be expressed reliably by
 * data packs or KubeJS. Vehicle physics, rail generation, firearms and zombie AI
 * remain the responsibility of the selected third-party mods.</p>
 */
@Mod(LastTrain.MOD_ID)
public final class LastTrain {
    public static final String MOD_ID = "lasttrain";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LastTrain(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(LastTrainCommands::register);
        NeoForge.EVENT_BUS.addListener(CampaignEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CampaignEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::onPlayerLoggedIn);
        TaczGunfireBridge.install(NeoForge.EVENT_BUS);
        LOGGER.info("Last Train campaign core is loading");
    }
}
