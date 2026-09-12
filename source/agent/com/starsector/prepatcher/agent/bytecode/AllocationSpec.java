package com.starsector.prepatcher.agent.bytecode;


public record AllocationSpec(String type, String constructorDesc, String hookName, String hookDesc, int expected) {
}
