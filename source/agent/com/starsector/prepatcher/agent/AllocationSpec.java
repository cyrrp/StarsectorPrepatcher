package com.starsector.prepatcher.agent;


record AllocationSpec(String type, String constructorDesc, String hookName, String hookDesc, int expected) {
}
