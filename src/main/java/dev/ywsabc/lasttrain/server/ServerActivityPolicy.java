package dev.ywsabc.lasttrain.server;

/** 无有效玩家时暂停任务与路线世界副作用的纯接线策略。 */
public final class ServerActivityPolicy {
    private ServerActivityPolicy() {
    }

    public static boolean shouldRunCampaignWorldDirectors(int activePlayers) {
        return activePlayers > 0;
    }
}
