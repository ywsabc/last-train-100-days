package dev.ywsabc.lasttrain.integration;

import dev.ywsabc.lasttrain.LastTrain;
import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/**
 * Optional server-side bridge from TaCZ gunfire to vanilla zombie aggression.
 *
 * <p>The bridge deliberately has no compile-time reference to a TaCZ class.
 * TaCZ's public {@code GunFireEvent} is resolved only after the loader confirms
 * that the mod is present. This keeps Last Train loadable when TaCZ is removed
 * and isolates API drift in one fail-safe integration point.</p>
 *
 * <p>A successful server-side shot attracts nearby, loaded zombies to the
 * shooter. It does not play a fake explosion sound, load chunks, or call
 * Zombie Awareness internals. Automatic fire is coalesced per shooter to avoid
 * scanning the same entity set every tick.</p>
 */
public final class TaczGunfireBridge {
    static final String TACZ_MOD_ID = "tacz";
    static final String GUN_FIRE_EVENT_CLASS = "com.tacz.guns.api.event.common.GunFireEvent";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean LISTENER_FAILURE_LOGGED = new AtomicBoolean();
    private static final Map<LivingEntity, Long> LAST_PULSE_BY_SHOOTER =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Method shooterAccessor;
    private static volatile Settings activeSettings = Settings.defaults();

    private TaczGunfireBridge() {
    }

    /**
     * Installs the optional listener on the NeoForge gameplay event bus.
     *
     * @return {@code true} when the listener is installed (or was installed
     * previously), otherwise {@code false} when TaCZ is absent or incompatible
     */
    public static boolean install(IEventBus gameEventBus) {
        return install(gameEventBus, Settings.defaults());
    }

    /**
     * Installs the bridge with explicit tuning values.
     *
     * <p>This method is idempotent. The first successful installation owns the
     * settings for the lifetime of the game process.</p>
     */
    public static synchronized boolean install(IEventBus gameEventBus, Settings settings) {
        Objects.requireNonNull(gameEventBus, "gameEventBus");
        Objects.requireNonNull(settings, "settings");

        if (INSTALLED.get()) {
            return true;
        }
        if (!ModList.get().isLoaded(TACZ_MOD_ID)) {
            LastTrain.LOGGER.info("TaCZ is not loaded; gunfire attraction bridge is disabled");
            return false;
        }

        try {
            Class<?> rawEventClass = Class.forName(
                    GUN_FIRE_EVENT_CLASS,
                    false,
                    TaczGunfireBridge.class.getClassLoader());
            Class<? extends Event> eventClass = rawEventClass.asSubclass(Event.class);
            Method accessor = eventClass.getMethod("getShooter");
            if (!LivingEntity.class.isAssignableFrom(accessor.getReturnType())) {
                throw new NoSuchMethodException("getShooter() does not return LivingEntity");
            }

            shooterAccessor = accessor;
            activeSettings = settings;
            addTypedListener(gameEventBus, eventClass);
            INSTALLED.set(true);
            LastTrain.LOGGER.info(
                    "Installed TaCZ gunfire attraction bridge (radius={}, verticalRadius={}, cooldown={} ticks)",
                    settings.horizontalRadius(),
                    settings.verticalRadius(),
                    settings.pulseCooldownTicks());
            return true;
        } catch (ClassNotFoundException | ClassCastException | NoSuchMethodException | LinkageError exception) {
            shooterAccessor = null;
            LastTrain.LOGGER.warn(
                    "TaCZ is loaded, but its GunFireEvent API is incompatible; "
                            + "gunfire attraction is disabled without affecting gun operation",
                    exception);
            return false;
        }
    }

    private static <E extends Event> void addTypedListener(IEventBus eventBus, Class<E> eventClass) {
        // LOWEST + receiveCanceled=false means canceled shots do not attract mobs.
        eventBus.addListener(EventPriority.LOWEST, false, eventClass, TaczGunfireBridge::onGunFire);
    }

    private static void onGunFire(Event event) {
        try {
            Method accessor = shooterAccessor;
            if (accessor == null) {
                return;
            }

            Object value = accessor.invoke(event);
            if (!(value instanceof LivingEntity shooter)
                    || !shooter.isAlive()
                    || !(shooter.level() instanceof ServerLevel level)) {
                return;
            }

            Settings settings = activeSettings;
            if (!claimPulse(shooter, level.getGameTime(), settings.pulseCooldownTicks())) {
                return;
            }
            boolean hasActivePlayers = level.getServer().getPlayerList().getPlayers().stream()
                    .anyMatch(player -> !player.isSpectator());
            CampaignSavedData.get(level.getServer()).registerGunfire(hasActivePlayers);
            attractLoadedZombies(level, shooter, settings);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException | LinkageError exception) {
            // An optional integration must never break TaCZ's firing code path.
            if (LISTENER_FAILURE_LOGGED.compareAndSet(false, true)) {
                LastTrain.LOGGER.warn(
                        "TaCZ gunfire attraction failed; further bridge failures will be suppressed",
                        exception);
            }
        }
    }

    private static boolean claimPulse(LivingEntity shooter, long gameTime, int cooldownTicks) {
        if (cooldownTicks == 0) {
            return true;
        }
        synchronized (LAST_PULSE_BY_SHOOTER) {
            Long previousPulse = LAST_PULSE_BY_SHOOTER.get(shooter);
            if (previousPulse != null
                    && gameTime >= previousPulse
                    && gameTime - previousPulse < cooldownTicks) {
                return false;
            }
            LAST_PULSE_BY_SHOOTER.put(shooter, gameTime);
            return true;
        }
    }

    private static int attractLoadedZombies(ServerLevel level, LivingEntity shooter, Settings settings) {
        double horizontalRadius = settings.horizontalRadius();
        double verticalRadius = settings.verticalRadius();
        AABB searchBounds = shooter.getBoundingBox().inflate(
                horizontalRadius,
                verticalRadius,
                horizontalRadius);

        List<Zombie> candidates = level.getEntitiesOfClass(
                Zombie.class,
                searchBounds,
                zombie -> zombie.isAlive()
                        && !zombie.isRemoved()
                        && !zombie.isAlliedTo(shooter)
                        // Keep Entity#distanceToSqr here: Sable replaces it
                        // with a sub-level-aware physical distance calculation.
                        && GunfireAttractionPolicy.isWithinHearingDistance(
                                zombie.distanceToSqr(shooter),
                                settings.horizontalRadius()));
        candidates.sort(Comparator.comparingDouble(zombie -> zombie.distanceToSqr(shooter)));

        int attracted = 0;
        for (Zombie zombie : candidates) {
            LivingEntity currentTarget = zombie.getTarget();
            if (currentTarget == shooter) {
                continue;
            }

            boolean currentTargetUsable = currentTarget != null
                    && currentTarget.isAlive()
                    && !currentTarget.isRemoved();
            double currentTargetDistanceSquared = currentTargetUsable
                    ? zombie.distanceToSqr(currentTarget)
                    : Double.POSITIVE_INFINITY;
            double shooterDistanceSquared = zombie.distanceToSqr(shooter);
            if (!GunfireAttractionPolicy.shouldRetarget(
                    currentTargetUsable,
                    shooterDistanceSquared,
                    currentTargetDistanceSquared)) {
                continue;
            }

            zombie.setTarget(shooter);
            if (zombie.getTarget() == shooter) {
                attracted++;
                if (attracted >= settings.maxZombiesPerPulse()) {
                    break;
                }
            }
        }
        return attracted;
    }

    /**
     * Runtime tuning for one installation of the bridge.
     *
     * <p>The default horizontal radius matches TaCZ's default unsuppressed
     * third-person sound distance. The bridge currently does not inspect
     * attachment internals, so suppressors use the same gameplay radius.</p>
     */
    public record Settings(
            double horizontalRadius,
            double verticalRadius,
            int pulseCooldownTicks,
            int maxZombiesPerPulse) {

        public Settings {
            if (!Double.isFinite(horizontalRadius) || horizontalRadius <= 0.0D) {
                throw new IllegalArgumentException("horizontalRadius must be finite and positive");
            }
            if (!Double.isFinite(verticalRadius) || verticalRadius <= 0.0D) {
                throw new IllegalArgumentException("verticalRadius must be finite and positive");
            }
            if (pulseCooldownTicks < 0) {
                throw new IllegalArgumentException("pulseCooldownTicks cannot be negative");
            }
            if (maxZombiesPerPulse <= 0) {
                throw new IllegalArgumentException("maxZombiesPerPulse must be positive");
            }
        }

        public static Settings defaults() {
            return new Settings(64.0D, 32.0D, 4, 64);
        }
    }
}
