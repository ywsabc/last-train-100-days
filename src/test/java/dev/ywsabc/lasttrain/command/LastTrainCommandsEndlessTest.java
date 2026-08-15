package dev.ywsabc.lasttrain.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ywsabc.lasttrain.campaign.CampaignMode;
import org.junit.jupiter.api.Test;

class LastTrainCommandsEndlessTest {
    @Test
    void statusUsesAnExplicitModeTranslationKey() {
        assertEquals(
                "campaign.lasttrain.mode.endless",
                LastTrainCommands.modeTranslationKey(CampaignMode.ENDLESS));
    }
}
