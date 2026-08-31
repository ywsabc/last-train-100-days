package dev.ywsabc.lasttrain.campaign;

import java.util.Locale;
import java.util.Optional;

/** Persisted ownership of every condition currently holding SAFE_MODE. */
public enum SafeModeReason {
    VEHICLE_STACK_UNAVAILABLE,
    TRAIN_RECOVERY_IN_PROGRESS,
    TRAIN_RECOVERY_BACKEND_FAILURE,
    REWARD_OUTBOX_OVERFLOW,
    SAVE_INTEGRITY,
    UNKNOWN;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SafeModeReason> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
