package dev.ywsabc.lasttrain.integration;

import dev.ywsabc.lasttrain.LastTrain;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

/**
 * Creates one conservative TaCZ sidearm kit without making TaCZ a linkage
 * dependency of the campaign save module.
 */
public final class TaczStarterKit {
    private static final ResourceLocation GUN_ITEM =
            ResourceLocation.parse("tacz:modern_kinetic_gun");
    private static final ResourceLocation AMMO_ITEM =
            ResourceLocation.parse("tacz:ammo");
    private static final ResourceLocation STARTER_GUN =
            ResourceLocation.parse("tacz:glock_17");
    private static final ResourceLocation STARTER_AMMO =
            ResourceLocation.parse("tacz:9mm");
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

    private TaczStarterKit() {
    }

    public static List<ItemStack> create() {
        if (!ModList.get().isLoaded("tacz")) {
            return List.of();
        }

        try {
            Item gunItem = registeredItem(GUN_ITEM);
            Item ammoItem = registeredItem(AMMO_ITEM);
            if (gunItem == Items.AIR || ammoItem == Items.AIR) {
                throw new IllegalStateException("TaCZ starter item registrations are missing");
            }

            ItemStack gun = new ItemStack(gunItem);
            Class<?> gunApi = Class.forName("com.tacz.guns.api.item.IGun");
            Method getGun = gunApi.getMethod("getIGunOrNull", ItemStack.class);
            Object gunAccess = getGun.invoke(null, gun);
            if (gunAccess == null) {
                throw new IllegalStateException("TaCZ rejected its registered gun item");
            }
            gunApi.getMethod("setGunId", ItemStack.class, ResourceLocation.class)
                    .invoke(gunAccess, gun, STARTER_GUN);
            gunApi.getMethod("setCurrentAmmoCount", ItemStack.class, int.class)
                    .invoke(gunAccess, gun, 17);

            ItemStack ammo = new ItemStack(ammoItem, 48);
            Class<?> ammoApi = Class.forName("com.tacz.guns.api.item.IAmmo");
            Method getAmmo = ammoApi.getMethod("getIAmmoOrNull", ItemStack.class);
            Object ammoAccess = getAmmo.invoke(null, ammo);
            if (ammoAccess == null) {
                throw new IllegalStateException("TaCZ rejected its registered ammunition item");
            }
            ammoApi.getMethod("setAmmoId", ItemStack.class, ResourceLocation.class)
                    .invoke(ammoAccess, ammo, STARTER_AMMO);

            return List.of(gun, ammo);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                LastTrain.LOGGER.warn(
                        "TaCZ is loaded but its starter sidearm API is incompatible; "
                                + "the campaign will continue without issuing a broken item",
                        exception);
            }
            return List.of();
        }
    }

    private static Item registeredItem(ResourceLocation id) {
        return BuiltInRegistries.ITEM.containsKey(id)
                ? BuiltInRegistries.ITEM.get(id)
                : Items.AIR;
    }
}
