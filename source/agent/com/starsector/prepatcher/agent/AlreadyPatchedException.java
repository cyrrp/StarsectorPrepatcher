package com.starsector.prepatcher.agent;


final class AlreadyPatchedException extends RuntimeException {
  AlreadyPatchedException(String message) {
    super(message);
  }
}
