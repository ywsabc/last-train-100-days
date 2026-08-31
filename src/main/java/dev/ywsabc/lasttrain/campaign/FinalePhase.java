package dev.ywsabc.lasttrain.campaign;

import java.util.Locale;

/**
 * 终局的持久化阶段。
 *
 * <p>抵达、重启和守住黎明分别保存，服务器重启后只恢复当前阶段，不会把已经
 * 完成的枢纽工作回滚，也不会越过尚未满足的列车抵达条件。</p>
 */
public enum FinalePhase {
    DORMANT("dormant"),
    ARRIVAL("arrival"),
    RESTART("restart"),
    HOLD_DAWN("hold_dawn"),
    COMPLETED("completed");

    private final String serializedName;

    FinalePhase(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static FinalePhase fromSerializedName(String value) {
        if (value == null || value.isBlank()) {
            return DORMANT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (FinalePhase phase : values()) {
            if (phase.serializedName.equals(normalized)
                    || phase.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return phase;
            }
        }
        return DORMANT;
    }
}
