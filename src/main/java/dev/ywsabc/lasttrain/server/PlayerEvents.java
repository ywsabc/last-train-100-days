package dev.ywsabc.lasttrain.server;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.integration.TaczStarterKit;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class PlayerEvents {
    private PlayerEvents() {
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        CampaignSavedData data = CampaignSavedData.get(player.getServer());
        if (data.start()) {
            IntegrationBridge.syncCampaignNumbers(player.getServer(), data);
            player.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.lasttrain.campaign_started"),
                    false);
        }

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

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
