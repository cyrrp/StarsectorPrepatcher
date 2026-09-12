package com.starsector.prepatcher.agent;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static com.starsector.prepatcher.agent.Contract.*;
import static com.starsector.prepatcher.agent.Queries.*;

final class MarketAdvance {
  static final String MARKET_ADVANCE_PLAN_BODY = "smo$marketAdvancePlanBody";
  static final String CONSTRUCTION_QUEUE_DESC = "Lcom/fs/starfarer/api/impl/campaign/econ/impl/ConstructionQueue;";
  static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
  static final String MARKET_API_DESC = "L" + MARKET_API + ";";

  static MethodNode marketAdvanceSemanticBody(ClassNode node) {
    MethodNode body = optionalMethod(node, MARKET_ADVANCE_PLAN_BODY, "(F)V");
    return body != null ? body : requireMethod(node, "advance", "(F)V");
  }

}
