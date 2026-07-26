package com.starsector.prepatcher.agent.templates;

import com.fs.starfarer.api.StarsectorPrepatcherMarketShareRuntime;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.CommodityMarketDataAPI;

import java.util.IdentityHashMap;
import java.util.Map;

/** Compile-time helper copied into vanilla and Nex punitive managers. */
public abstract class PunitivePlayerShareTemplate {
    private static int spp$punitiveCachedPlayerShare(
            CommodityMarketDataAPI data,
            FactionAPI player,
            Map<FactionAPI, Integer> shares,
            IdentityHashMap<CommodityMarketDataAPI, Integer> cache) {
        // Vanilla and the owned AoTD fork may be coalesced while the fork keeps
        // the inherited market-share surface. Unknown/custom implementations and
        // a future fork override retain the original call multiplicity.
        if (!StarsectorPrepatcherMarketShareRuntime.isEligible(data)) {
            return data.getMarketSharePercent(player);
        }

        Integer cached = cache.get(data);
        if (cached != null) {
            return cached.intValue();
        }

        int value;
        if (shares != null) {
            for (Map.Entry<FactionAPI, Integer> entry : shares.entrySet()) {
                if (entry.getKey() == player) {
                    value = entry.getValue().intValue();
                    cache.put(data, Integer.valueOf(value));
                    return value;
                }
            }
        }

        value = data.getMarketSharePercent(player);
        cache.put(data, Integer.valueOf(value));
        return value;
    }
}
