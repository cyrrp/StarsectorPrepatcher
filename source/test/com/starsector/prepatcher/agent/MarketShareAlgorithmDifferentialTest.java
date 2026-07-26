package com.starsector.prepatcher.agent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Pure differential model for the identity/equality semantics used by the injected bodies. */
public final class MarketShareAlgorithmDifferentialTest {
    private MarketShareAlgorithmDifferentialTest() {}

    public static void main(String[] args) {
        runDirectedCases();
        runRandomCases();
        runPunitiveCacheCases();
        System.out.println("OK market-share-algorithm differential=20000 directed identity "
                + "player-owned overflow fresh-map punitive-cache owned-fork-cache");
    }

    private static void runDirectedCases() {
        Faction player = new Faction("player", 1, true);
        Faction equalPlayerAlias = new Faction("player-alias", 1, false);
        Faction other = new Faction("other", 2, false);
        Faction otherAlias = new Faction("other-alias", 2, false);
        Faction third = new Faction("third", 3, false);
        List<Market> markets = List.of(
                new Market(other, true, 7),
                new Market(player, true, 11),
                new Market(equalPlayerAlias, false, 13),
                new Market(otherAlias, false, 17),
                new Market(third, false, 0),
                new Market(other, false, Integer.MAX_VALUE),
                new Market(other, false, 10));
        assertEquivalent(markets);

        // The first equality representative controls the vanilla identity and
        // isPlayerFaction semantics for the map entry.
        assertEquivalent(List.of(
                new Market(equalPlayerAlias, true, 5),
                new Market(player, true, 9),
                new Market(other, false, -3)));

        // Preserve int overflow rather than widening the aggregation.
        assertEquivalent(List.of(
                new Market(other, false, Integer.MAX_VALUE),
                new Market(other, false, 1),
                new Market(player, false, Integer.MIN_VALUE),
                new Market(player, true, -1)));

        // A null owner takes the raw path; the model verifies both the result
        // and the exceptional behavior of the original algorithm.
        assertEquivalent(List.of(new Market(null, false, 1)));
        assertEquivalent(List.of(
                new Market(null, false, 1),
                new Market(other, false, 2)));
    }

    private static void runRandomCases() {
        Random random = new Random(0x5A17C0DEL);
        for (int iteration = 0; iteration < 20_000; iteration++) {
            int factionCount = 1 + random.nextInt(12);
            List<Faction> factions = new ArrayList<>();
            for (int index = 0; index < factionCount; index++) {
                int equalityGroup = random.nextInt(Math.max(1, factionCount / 2));
                boolean player = random.nextInt(7) == 0;
                factions.add(new Faction("f" + iteration + "-" + index,
                        equalityGroup, player));
            }
            int marketCount = random.nextInt(80);
            List<Market> markets = new ArrayList<>();
            for (int index = 0; index < marketCount; index++) {
                Faction owner = factions.get(random.nextInt(factions.size()));
                int selector = random.nextInt(40);
                int share = switch (selector) {
                    case 0 -> Integer.MAX_VALUE;
                    case 1 -> Integer.MIN_VALUE;
                    case 2 -> -1;
                    case 3 -> 0;
                    default -> random.nextInt(20_001) - 10_000;
                };
                markets.add(new Market(owner, random.nextBoolean(), share));
            }
            assertEquivalent(markets);
        }
    }

    private static void assertEquivalent(List<Market> markets) {
        Outcome vanilla = capture(() -> vanilla(markets));
        Outcome linear = capture(() -> linear(markets));
        require(vanilla.failureClass == linear.failureClass,
                "failure class differs: vanilla=" + vanilla.failureClass
                        + " linear=" + linear.failureClass + " markets=" + markets);
        if (vanilla.failureClass != null) return;
        assertIdentityOrderedMap(vanilla.result, linear.result);

        LinkedHashMap<Faction, Integer> second = computeLinearUncounted(markets);
        require(second != linear.result, "linear result map was retained across calls");
        assertIdentityOrderedMap(linear.result, second);
        Faction synthetic = new Faction("mutable", Integer.MIN_VALUE, false);
        linear.result.put(synthetic, 123);
        require(!second.containsKey(synthetic),
                "mutating one returned map affected a later result");

        boolean hasNullOwner = markets.stream().anyMatch(market -> market.owner == null);
        if (hasNullOwner) return;

        int uniqueKeys = vanilla.result.size();
        require(linear.exportCalls == markets.size(),
                "linear export calls expected=" + markets.size()
                        + " actual=" + linear.exportCalls);
        require(linear.marketSnapshots == 1,
                "linear market snapshot count changed: " + linear.marketSnapshots);
        require(vanilla.exportCalls == uniqueKeys * markets.size(),
                "vanilla model operation count changed");
        require(vanilla.marketSnapshots == uniqueKeys + 1,
                "vanilla model snapshot count changed");
    }

    private static LinkedHashMap<Faction, Integer> computeLinearUncounted(List<Market> markets) {
        Counters counters = new Counters();
        ACTIVE_COUNTERS.set(counters);
        try {
            return linear(markets);
        } finally {
            ACTIVE_COUNTERS.remove();
        }
    }

    private static LinkedHashMap<Faction, Integer> vanilla(List<Market> markets) {
        Counters counters = ACTIVE_COUNTERS.get();
        counters.marketSnapshots++;
        LinkedHashMap<Faction, Integer> result = new LinkedHashMap<>();
        for (Market market : markets) {
            Faction faction = market.owner;
            if (result.containsKey(faction)) continue;
            result.put(faction, vanillaSingle(markets, faction));
        }
        return result;
    }

    private static int vanillaSingle(List<Market> markets, Faction faction) {
        Counters counters = ACTIVE_COUNTERS.get();
        counters.marketSnapshots++;
        int total = 0;
        for (Market market : markets) {
            counters.exportCalls++;
            int share = market.share;
            if (market.owner == faction || (faction.player && market.playerOwned)) {
                total += share;
            }
        }
        return total;
    }

    private static LinkedHashMap<Faction, Integer> linear(List<Market> markets) {
        Counters counters = ACTIVE_COUNTERS.get();
        counters.marketSnapshots++;
        LinkedHashMap<Faction, Integer> result = new LinkedHashMap<>();
        Faction[] owners = new Faction[markets.size()];
        IdentityHashMap<Faction, Boolean> playerKeys = new IdentityHashMap<>();
        for (int index = 0; index < owners.length; index++) {
            Faction owner = markets.get(index).owner;
            if (owner == null) return vanilla(markets);
            owners[index] = owner;
            if (!result.containsKey(owner)) {
                result.put(owner, 0);
                if (owner.player) playerKeys.put(owner, Boolean.TRUE);
            }
        }

        IdentityHashMap<Faction, int[]> totals = new IdentityHashMap<>();
        int playerOwnedTotal = 0;
        boolean hasPlayerKey = !playerKeys.isEmpty();
        for (int index = 0; index < owners.length; index++) {
            Market market = markets.get(index);
            Faction owner = owners[index];
            counters.exportCalls++;
            int share = market.share;
            int[] accumulator = totals.get(owner);
            if (accumulator == null) {
                accumulator = new int[2];
                totals.put(owner, accumulator);
            }
            accumulator[0] += share;
            if (hasPlayerKey && market.playerOwned) {
                playerOwnedTotal += share;
                accumulator[1] += share;
            }
        }

        for (Map.Entry<Faction, Integer> entry : result.entrySet()) {
            Faction faction = entry.getKey();
            int[] accumulator = totals.get(faction);
            int value = accumulator == null ? 0 : accumulator[0];
            if (playerKeys.containsKey(faction)) {
                value += playerOwnedTotal - (accumulator == null ? 0 : accumulator[1]);
            }
            entry.setValue(value);
        }
        return result;
    }

    private static void runPunitiveCacheCases() {
        Faction player = new Faction("player", 1, true);
        Faction equalAlias = new Faction("alias", 1, false);
        IdentityHashMap<Data, Integer> cache = new IdentityHashMap<>();

        Data exact = new Data(true, 19);
        LinkedHashMap<Faction, Integer> shares = new LinkedHashMap<>();
        shares.put(equalAlias, 999);
        shares.put(player, 19); // equal key does not replace representative identity
        int value = cachedPlayerShare(exact, player, shares, cache);
        require(value == 19 && exact.calls == 1,
                "identity lookup accepted an equal-but-not-identical key");
        value = cachedPlayerShare(exact, player, null, cache);
        require(value == 19 && exact.calls == 1,
                "exact data cache did not reuse the fallback value");

        cache.clear();
        shares = new LinkedHashMap<>();
        shares.put(player, 23);
        exact = new Data(true, 41);
        value = cachedPlayerShare(exact, player, shares, cache);
        require(value == 23 && exact.calls == 0,
                "competitive map value was not reused by identity");
        cachedPlayerShare(exact, player, null, cache);
        require(exact.calls == 0, "competitive map value was not cached locally");

        Data fork = new Data(true, 37);
        cache.clear();
        require(cachedPlayerShare(fork, player, null, cache) == 37,
                "owned fork fallback value changed");
        require(cachedPlayerShare(fork, player, null, cache) == 37,
                "owned fork fallback value was not reused");
        require(fork.calls == 1,
                "owned fork player share was not coalesced");

        Data custom = new Data(false, 31);
        cache.clear();
        for (int index = 0; index < 4; index++) {
            require(cachedPlayerShare(custom, player, shares, cache) == 31,
                    "custom data value changed");
        }
        require(custom.calls == 4 && cache.isEmpty(),
                "custom implementation call multiplicity was coalesced");
    }

    private static int cachedPlayerShare(Data data, Faction player,
                                         Map<Faction, Integer> shares,
                                         IdentityHashMap<Data, Integer> cache) {
        if (!data.eligible) return data.direct(player);
        Integer cached = cache.get(data);
        if (cached != null) return cached;
        if (shares != null) {
            for (Map.Entry<Faction, Integer> entry : shares.entrySet()) {
                if (entry.getKey() == player) {
                    int value = entry.getValue();
                    cache.put(data, value);
                    return value;
                }
            }
        }
        int value = data.direct(player);
        cache.put(data, value);
        return value;
    }

    private static Outcome capture(Computation computation) {
        Counters counters = new Counters();
        ACTIVE_COUNTERS.set(counters);
        try {
            return new Outcome(computation.run(), null,
                    counters.marketSnapshots, counters.exportCalls);
        } catch (Throwable failure) {
            return new Outcome(null, failure.getClass(),
                    counters.marketSnapshots, counters.exportCalls);
        } finally {
            ACTIVE_COUNTERS.remove();
        }
    }

    private static void assertIdentityOrderedMap(Map<Faction, Integer> expected,
                                                 Map<Faction, Integer> actual) {
        require(expected.size() == actual.size(),
                "map size differs expected=" + expected + " actual=" + actual);
        var expectedIterator = expected.entrySet().iterator();
        var actualIterator = actual.entrySet().iterator();
        int index = 0;
        while (expectedIterator.hasNext()) {
            Map.Entry<Faction, Integer> left = expectedIterator.next();
            Map.Entry<Faction, Integer> right = actualIterator.next();
            require(left.getKey() == right.getKey(),
                    "key identity/order differs at " + index + ": " + left + " vs " + right);
            require(left.getValue().intValue() == right.getValue().intValue(),
                    "value differs at " + index + ": " + left + " vs " + right);
            index++;
        }
    }

    private static final ThreadLocal<Counters> ACTIVE_COUNTERS = new ThreadLocal<>();

    private static final class Counters {
        int marketSnapshots;
        int exportCalls;
    }

    private record Outcome(LinkedHashMap<Faction, Integer> result,
                           Class<?> failureClass,
                           int marketSnapshots,
                           int exportCalls) {}

    @FunctionalInterface
    private interface Computation {
        LinkedHashMap<Faction, Integer> run();
    }

    private static final class Faction {
        final String label;
        final int equalityGroup;
        final boolean player;

        Faction(String label, int equalityGroup, boolean player) {
            this.label = label;
            this.equalityGroup = equalityGroup;
            this.player = player;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Faction faction
                    && equalityGroup == faction.equalityGroup;
        }

        @Override
        public int hashCode() {
            return equalityGroup;
        }

        @Override
        public String toString() {
            return label + "#" + equalityGroup + (player ? "[P]" : "");
        }
    }

    private record Market(Faction owner, boolean playerOwned, int share) {}

    private static final class Data {
        final boolean eligible;
        final int value;
        int calls;

        Data(boolean eligible, int value) {
            this.eligible = eligible;
            this.value = value;
        }

        int direct(Faction ignored) {
            calls++;
            return value;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
