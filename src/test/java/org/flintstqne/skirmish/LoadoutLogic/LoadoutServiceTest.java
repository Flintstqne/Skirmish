package org.flintstqne.skirmish.LoadoutLogic;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-life affordability rule (design doc §7.4/§7.5.3). Signature is
 * (points, spent, currentCost, newCost) — a pick replaces its category, so only
 * the price difference has to fit.
 */
class LoadoutServiceTest {

    @Test
    void freeTiersAreAlwaysAffordableEvenAtZeroPoints() {
        assertTrue(LoadoutService.canAfford(0, 0, 0, 0));
    }

    @Test
    void cannotBuyWhatYouHaveNotEarned() {
        assertFalse(LoadoutService.canAfford(100, 0, 0, 120));
        assertTrue(LoadoutService.canAfford(120, 0, 0, 120));
    }

    @Test
    void alreadySpentPointsAreNotAvailableAgain() {
        // 200 earned, 150 committed to armor — a 120 gun no longer fits.
        assertFalse(LoadoutService.canAfford(200, 150, 0, 120));
        assertTrue(LoadoutService.canAfford(200, 150, 0, 50));
    }

    @Test
    void swappingWithinACategoryOnlyChargesTheDifference() {
        // Holding a 120 primary out of 200 points: a 150 primary costs 30 more, which fits.
        assertTrue(LoadoutService.canAfford(200, 120, 120, 150));
        // A 350 primary is 230 more — it does not.
        assertFalse(LoadoutService.canAfford(200, 120, 120, 350));
    }

    @Test
    void downgradingIsAlwaysAllowed() {
        // Even flat broke, dropping from a 350 pick to a 120 one refunds the difference.
        assertTrue(LoadoutService.canAfford(350, 350, 350, 120));
        assertTrue(LoadoutService.canAfford(350, 350, 350, 0));
    }

    // ---- preset allocation (§7.6) — greedy, category-declaration order --------

    private Map<LoadoutCatalog.Category, Integer> costs(int primary, int secondary, int armor) {
        Map<LoadoutCatalog.Category, Integer> costs = new EnumMap<>(LoadoutCatalog.Category.class);
        costs.put(LoadoutCatalog.Category.PRIMARY, primary);
        costs.put(LoadoutCatalog.Category.SECONDARY, secondary);
        costs.put(LoadoutCatalog.Category.ARMOR, armor);
        return costs;
    }

    @Test
    void everyCategoryFitsWhenThereIsEnoughForAll() {
        Set<LoadoutCatalog.Category> affordable = LoadoutService.affordableCategories(500, costs(120, 90, 150));
        assertEquals(Set.of(LoadoutCatalog.Category.PRIMARY, LoadoutCatalog.Category.SECONDARY,
                LoadoutCatalog.Category.ARMOR), affordable);
    }

    @Test
    void onlyTheCategoriesThatFitAreKept() {
        // 120 + 90 = 210 points: primary and secondary fit, but nothing is left for a 150 armor.
        Set<LoadoutCatalog.Category> affordable = LoadoutService.affordableCategories(210, costs(120, 90, 150));
        assertEquals(Set.of(LoadoutCatalog.Category.PRIMARY, LoadoutCatalog.Category.SECONDARY), affordable);
    }

    @Test
    void spendingIsGreedyInCategoryDeclarationOrder() {
        // 120 points: primary (declared first) spends 100, leaving 20 — armor's 50 cost
        // would fit a fresh 120-point budget, but not what's left once primary goes first.
        Set<LoadoutCatalog.Category> affordable = LoadoutService.affordableCategories(120, costs(100, 0, 50));
        assertEquals(Set.of(LoadoutCatalog.Category.PRIMARY, LoadoutCatalog.Category.SECONDARY), affordable);
    }

    @Test
    void categoriesWithNoRequestedCostAreNeverMarkedAffordable() {
        // An empty preset (nothing selected for secondary/armor) must not spuriously appear.
        Map<LoadoutCatalog.Category, Integer> costs = new EnumMap<>(LoadoutCatalog.Category.class);
        costs.put(LoadoutCatalog.Category.PRIMARY, 50);
        assertEquals(Set.of(LoadoutCatalog.Category.PRIMARY), LoadoutService.affordableCategories(1000, costs));
    }

    @Test
    void zeroPointsStillAffordsFreeCategoryEntries() {
        assertEquals(Set.of(LoadoutCatalog.Category.PRIMARY), LoadoutService.affordableCategories(0, costs(0, 10, 10)));
    }
}
