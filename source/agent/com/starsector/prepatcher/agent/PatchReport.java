package com.starsector.prepatcher.agent;

import java.util.ArrayList;
import java.util.List;

final class PatchReport {
  int total;
  final List<String> details = new ArrayList<>();

  void add(String label, int count) {
    if (count <= 0) return;
    total += count;
    details.add(label + "=" + count);
  }

  @Override
  public String toString() {
    return String.join(", ", details);
  }
}
