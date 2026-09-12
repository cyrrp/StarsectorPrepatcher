package com.starsector.prepatcher.agent;

import java.util.LinkedHashMap;

final class Names {
  static final String HOOKS = "com/fs/starfarer/api/StarsectorPrepatcherHooks";
  static final String CORE_WORLDS_RUNTIME = "com/fs/starfarer/api/StarsectorPrepatcherCoreWorldsRuntime";

  static final String STRATEGIC_JUMP_DESTINATION_FIRST_PATCH_ID = "strategicJumpDestinationFirst";
  static final String STRATEGIC_JUMP_DESTINATION_INDEX_PATCH_ID = "strategicJumpDestinationIndex";

  static final String LINKED_MAP_DESC = "Ljava/util/LinkedHashMap;";
  static final String ENTITY_TOKEN_DESC = "Lcom/fs/starfarer/api/campaign/SectorEntityToken;";
  static final String MAP_API_DESC = "Lcom/fs/starfarer/api/ui/SectorMapAPI;";
  static final String INTEL_DESC = "Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;";
  static final String LIST_DESC = "Ljava/util/List;";
  static final String STAR_SYSTEM_DESC = "Lcom/fs/starfarer/api/campaign/StarSystemAPI;";
  static final String LOCATION_DESC = "Lcom/fs/starfarer/api/campaign/LocationAPI;";

}
