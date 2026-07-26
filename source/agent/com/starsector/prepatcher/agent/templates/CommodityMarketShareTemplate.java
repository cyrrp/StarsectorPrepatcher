package com.starsector.prepatcher.agent.templates;

import com.fs.starfarer.api.StarsectorPrepatcherMarketShareRuntime;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.campaign.econ.reach.CommodityMarketData;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-time body copied into vanilla CommodityMarketData.
 * The template itself is never instantiated at runtime.
 */
public abstract class CommodityMarketShareTemplate {
    private Map<FactionAPI, Integer> spp$commodityMarketDataRawMarketSharePerFaction() {
        throw new AssertionError("template placeholder");
    }

    public Map<FactionAPI, Integer> getMarketSharePercentPerFaction() {
        // The owned AoTD fork inherits the complete market-share surface and is
        // therefore eligible too. A ClassValue-backed runtime gate revalidates
        // that no future fork revision overrides any semantic dependency.
        if (!StarsectorPrepatcherMarketShareRuntime.isEligible(this)) {
            return spp$commodityMarketDataRawMarketSharePerFaction();
        }

        CommodityMarketData self = (CommodityMarketData) (Object) this;
        List<MarketAPI> markets = self.getMarkets();
        LinkedHashMap<FactionAPI, Integer> result = new LinkedHashMap<>();
        FactionAPI[] owners = new FactionAPI[markets.size()];
        IdentityHashMap<FactionAPI, Boolean> playerKeys = new IdentityHashMap<>();

        for (int i = 0; i < owners.length; i++) {
            FactionAPI owner = markets.get(i).getFaction();
            // A null owner makes vanilla's repeated single-faction method
            // observably exceptional. Fall back rather than invent semantics.
            if (owner == null) {
                return spp$commodityMarketDataRawMarketSharePerFaction();
            }
            owners[i] = owner;
            if (!result.containsKey(owner)) {
                result.put(owner, Integer.valueOf(0));
                if (owner.isPlayerFaction()) {
                    playerKeys.put(owner, Boolean.TRUE);
                }
            }
        }

        IdentityHashMap<FactionAPI, int[]> totals = new IdentityHashMap<>();
        int playerOwnedTotal = 0;
        boolean hasPlayerKey = !playerKeys.isEmpty();
        for (int i = 0; i < owners.length; i++) {
            MarketAPI market = markets.get(i);
            FactionAPI owner = owners[i];
            int share = self.getExportMarketSharePercent(market);

            int[] accumulator = totals.get(owner);
            if (accumulator == null) {
                accumulator = new int[2];
                totals.put(owner, accumulator);
            }
            accumulator[0] += share;

            if (hasPlayerKey && market.isPlayerOwned()) {
                playerOwnedTotal += share;
                accumulator[1] += share;
            }
        }

        for (Map.Entry<FactionAPI, Integer> entry : result.entrySet()) {
            FactionAPI faction = entry.getKey();
            int[] accumulator = totals.get(faction);
            int value = accumulator == null ? 0 : accumulator[0];
            if (playerKeys.containsKey(faction)) {
                value += playerOwnedTotal - (accumulator == null ? 0 : accumulator[1]);
            }
            entry.setValue(Integer.valueOf(value));
        }
        return result;
    }
}
