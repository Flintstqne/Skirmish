package org.flintstqne.skirmish.LoadoutLogic;

import org.junit.jupiter.api.Test;

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
}
