package com.starsector.prepatcher.agent.patch;


public final class AlreadyPatchedException extends RuntimeException {
  public AlreadyPatchedException(String message) {
    super(message);
  }
}
