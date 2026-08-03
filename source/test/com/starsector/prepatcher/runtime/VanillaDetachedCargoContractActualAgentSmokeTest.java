package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;

/** Verifies that the installed javaagent publishes the final vanilla Economy contract. */
public final class VanillaDetachedCargoContractActualAgentSmokeTest {
    private VanillaDetachedCargoContractActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        ClassLoader loader = VanillaDetachedCargoContractActualAgentSmokeTest.class.getClassLoader();
        Class.forName("com.fs.starfarer.campaign.econ.Economy", false, loader);

        String status = System.getProperty(
                "starsector.prepatcher.detachedCargoVanillaEconomyContract", "");
        if (!"READY".equals(status)) {
            throw new AssertionError("Expected READY vanilla Cargo contract, got " + status);
        }
        if (!StarsectorPrepatcherRuntimeBridge
                .isVanillaDetachedCargoEconomyContractOperational()) {
            throw new AssertionError("Runtime bridge did not receive vanilla Cargo contract");
        }
        System.out.println("OK actual-agent vanilla detached-Cargo Economy contract READY");
    }
}
