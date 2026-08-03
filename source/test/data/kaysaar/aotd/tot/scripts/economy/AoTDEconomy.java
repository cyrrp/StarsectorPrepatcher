package data.kaysaar.aotd.tot.scripts.economy;

import com.fs.starfarer.api.campaign.econ.MarketAPI;

/** Same-loader fixture for the explicit owned-fork UI dispatcher ABI. */
public class AoTDEconomy {
    public int calls;
    public int lastAction;
    public MarketAPI lastMarket;
    public long lastDetail;
    public String[] lastCommodityIds;
    public boolean result = true;
    public boolean fail;

    public final boolean dispatchPrepatcherUiEconomyStep(
            int action, MarketAPI market, long detail, String[] commodityIds) {
        calls++;
        lastAction = action;
        lastMarket = market;
        lastDetail = detail;
        lastCommodityIds = commodityIds;
        if (fail) throw new IllegalStateException("fixture-dispatch-failure");
        return result;
    }
}
