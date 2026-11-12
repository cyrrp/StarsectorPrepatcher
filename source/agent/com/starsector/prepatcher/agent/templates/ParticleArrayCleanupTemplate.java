package com.starsector.prepatcher.agent.templates;

import com.fs.graphics.particle.BaseParticle;

/** Read-only structural contract for an existing allocation-free array cleanup; never installed or loaded. */
public final class ParticleArrayCleanupTemplate {
  private BaseParticle[] particlesArray;
  private int particlesNum;

  public void advance(float amount) {
    if (particlesNum == 0) return;
    int i = 0;
    while (i < particlesNum) {
      BaseParticle particle = particlesArray[i];
      particle.advance(amount);
      if (particle.isExpired()) {
        particlesArray[i] = particlesArray[particlesNum - 1];
        particlesArray[particlesNum - 1] = null;
        particlesNum--;
      } else {
        i++;
      }
    }
  }
}
