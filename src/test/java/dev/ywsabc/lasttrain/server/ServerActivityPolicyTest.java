package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerActivityPolicyTest {
    @Test
    void worldDirectorsPauseWithoutEffectivePlayers() {
        assertFalse(ServerActivityPolicy.shouldRunCampaignWorldDirectors(-1));
        assertFalse(ServerActivityPolicy.shouldRunCampaignWorldDirectors(0));
        assertTrue(ServerActivityPolicy.shouldRunCampaignWorldDirectors(1));
        assertTrue(ServerActivityPolicy.shouldRunCampaignWorldDirectors(8));
    }
}
