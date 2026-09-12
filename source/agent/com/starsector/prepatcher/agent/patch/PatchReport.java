package com.starsector.prepatcher.agent.patch;

import java.util.ArrayList;
import java.util.List;

public final class PatchReport {
  public int total;
  public final List<String> details = new ArrayList<>();

  public void add(String label, int count) {
    if (count <= 0) return;
    total += count;
    details.add(label + "=" + count);
  }

  @Override
  public String toString() {
    return String.join(", ", details);
  }
}
