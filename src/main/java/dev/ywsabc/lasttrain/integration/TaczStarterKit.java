package dev.ywsabc.lasttrain.integration;

import dev.ywsabc.lasttrain.LastTrain;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Creates one conservative TaCZ sidearm kit without making TaCZ a linkage
 * dependency of the campaign save module.
 */
public final class TaczStarterKit {
    private static final ResourceLocation STARTER_GUN =
            ResourceLocation.parse("tacz:glock_17");
    private static final ResourceLocation STARTER_AMMO =
            ResourceLocation.parse("tacz:9mm");
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

    private TaczStarterKit() {
    }

    public static List<ItemStack> create(HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        if (!ModList.get().isLoaded("tacz")) {
            return List.of();
        }

        try {
            Class<?> fireModeType = Class.forName("com.tacz.guns.api.item.gun.FireMode");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object semiAutomatic = Enum.valueOf((Class<? extends Enum>) fireModeType, "SEMI");

            Class<?> gunBuilderType = Class.forName("com.tacz.guns.api.item.builder.GunItemBuilder");
            Object gunBuilder = gunBuilderType.getMethod("create").invoke(null);
            gunBuilderType.getMethod("setCount", int.class).invoke(gunBuilder, 1);
            gunBuilderType.getMethod("setId", ResourceLocation.class).invoke(gunBuilder, STARTER_GUN);
            gunBuilderType.getMethod("setAmmoCount", int.class).invoke(gunBuilder, 17);
            gunBuilderType.getMethod("setFireMode", fireModeType).invoke(gunBuilder, semiAutomatic);
            gunBuilderType.getMethod("setAmmoInBarrel", boolean.class).invoke(gunBuilder, true);
            Object builtGun = gunBuilderType
                    .getMethod("build", HolderLookup.Provider.class)
                    .invoke(gunBuilder, registries);

            Class<?> ammoBuilderType = Class.forName("com.tacz.guns.api.item.builder.AmmoItemBuilder");
            Object ammoBuilder = ammoBuilderType.getMethod("create").invoke(null);
            ammoBuilderType.getMethod("setCount", int.class).invoke(ammoBuilder, 48);
            ammoBuilderType.getMethod("setId", ResourceLocation.class).invoke(ammoBuilder, STARTER_AMMO);
            Object builtAmmo = ammoBuilderType.getMethod("build").invoke(ammoBuilder);

            if (!(builtGun instanceof ItemStack gun)
                    || !(builtAmmo instanceof ItemStack ammo)
                    || gun.isEmpty()
                    || ammo.isEmpty()) {
                throw new IllegalStateException("TaCZ rejected the configured Glock 17 starter kit");
            }

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
}
