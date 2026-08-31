package dev.ywsabc.lasttrain.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BootstrapTransactionPolicyTest {
    @Test
    void publicSupplyRestartReconcilesWorldMarkerWithoutRefilling() {
        assertEquals(
                BootstrapTransactionPolicy.SupplyDirective.WRITE_AND_COMMIT,
                BootstrapTransactionPolicy.supplyDirective(
                        BootstrapTransactionPolicy.SupplyPhase.NOT_STARTED,
                        false));
        assertEquals(
                BootstrapTransactionPolicy.SupplyDirective.COMMIT_ONLY,
                BootstrapTransactionPolicy.supplyDirective(
                        BootstrapTransactionPolicy.SupplyPhase.PREPARING,
                        true));
        assertEquals(
                BootstrapTransactionPolicy.SupplyDirective.NONE,
                BootstrapTransactionPolicy.supplyDirective(
                        BootstrapTransactionPolicy.SupplyPhase.COMMITTED,
                        false));
    }

    @Test
    void assemblyRestartRecoversTaggedTrainAndNeverPlacesASecondOne() {
        assertEquals(
                BootstrapTransactionPolicy.AssemblyDirective.PLACE_LAYOUT,
                BootstrapTransactionPolicy.assemblyDirective(
                        BootstrapTransactionPolicy.AssemblyPhase.NOT_STARTED,
                        false,
                        false));
        assertEquals(
                BootstrapTransactionPolicy.AssemblyDirective.RESUME_ASSEMBLY,
                BootstrapTransactionPolicy.assemblyDirective(
                        BootstrapTransactionPolicy.AssemblyPhase.LAYOUT_PREPARED,
                        true,
                        false));
        assertEquals(
                BootstrapTransactionPolicy.AssemblyDirective.RECOVER_TRAIN,
                BootstrapTransactionPolicy.assemblyDirective(
                        BootstrapTransactionPolicy.AssemblyPhase.ASSEMBLY_REQUESTED,
                        false,
                        true));
        assertEquals(
                BootstrapTransactionPolicy.AssemblyDirective.WAIT_FOR_RECOVERY,
                BootstrapTransactionPolicy.assemblyDirective(
                        BootstrapTransactionPolicy.AssemblyPhase.ASSEMBLY_REQUESTED,
                        false,
                        false));
    }
}
