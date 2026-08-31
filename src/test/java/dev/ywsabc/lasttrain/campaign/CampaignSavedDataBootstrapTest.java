package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.server.BootstrapTransactionPolicy;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class CampaignSavedDataBootstrapTest {
    @Test
    void supplyAndAssemblyTransactionPhasesPersistAcrossRestart() {
        CampaignSavedData data = new CampaignSavedData();
        data.markStarterStationBuilt(new BlockPos(4, 70, 8));
        data.beginStarterPublicSupply();
        data.markStarterTrainPlaced();
        data.markStarterTrainAssemblyRequested();

        CampaignSavedData loaded = CampaignSavedData.load(
                data.save(new CompoundTag(), null),
                null);

        assertEquals(
                BootstrapTransactionPolicy.SupplyPhase.PREPARING,
                loaded.starterPublicSupplyPhase());
        assertEquals(
                BootstrapTransactionPolicy.AssemblyPhase.ASSEMBLY_REQUESTED,
                loaded.starterTrainAssemblyPhase());

        loaded.commitStarterPublicSupply();
        loaded.markStarterTrainAssembled(UUID.randomUUID());
        CampaignSavedData committed = CampaignSavedData.load(
                loaded.save(new CompoundTag(), null),
                null);
        assertTrue(committed.starterPublicSupplyCommitted());
        assertEquals(
                BootstrapTransactionPolicy.AssemblyPhase.COMMITTED,
                committed.starterTrainAssemblyPhase());
    }

    @Test
    void schemaElevenBuiltStationMigratesAsAlreadySuppliedWithoutRefill() {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 11);
        old.putString("campaign_id", UUID.randomUUID().toString());
        old.putBoolean("starter_station_built", true);
        old.putBoolean("starter_train_placed", true);

        CampaignSavedData loaded = CampaignSavedData.load(old, null);

        assertEquals(
                BootstrapTransactionPolicy.SupplyPhase.COMMITTED,
                loaded.starterPublicSupplyPhase());
        assertEquals(
                BootstrapTransactionPolicy.AssemblyPhase.LAYOUT_PREPARED,
                loaded.starterTrainAssemblyPhase());
    }

    @Test
    void corruptTransactionEnumsFailClosedAgainstDuplicateSuppliesAndTrain() {
        CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        corrupt.putString("campaign_id", UUID.randomUUID().toString());
        corrupt.putString("starter_public_supply_phase", "HALF_WRITTEN");
        corrupt.putString("starter_train_assembly_phase", "UNKNOWN_BACKEND_STATE");

        CampaignSavedData loaded = CampaignSavedData.load(corrupt, null);

        assertEquals(
                BootstrapTransactionPolicy.SupplyPhase.COMMITTED,
                loaded.starterPublicSupplyPhase());
        assertEquals(
                BootstrapTransactionPolicy.AssemblyPhase.ASSEMBLY_REQUESTED,
                loaded.starterTrainAssemblyPhase());
        assertTrue(loaded.integrityEvents().stream().anyMatch(issue ->
                issue.detail().contains("starter_train_assembly_phase")));
    }
}
