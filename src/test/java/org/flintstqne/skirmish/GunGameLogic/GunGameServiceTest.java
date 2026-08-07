package org.flintstqne.skirmish.GunGameLogic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gun Game's ladder arithmetic (design doc §8.5) - a 7-tier ladder has valid indices 0-6. */
class GunGameServiceTest {

    private static final int LADDER_SIZE = 7;

    @Test
    void promotionAdvancesOneTier() {
        assertEquals(1, GunGameService.nextTierAfterPromotion(0, LADDER_SIZE));
        assertEquals(4, GunGameService.nextTierAfterPromotion(3, LADDER_SIZE));
    }

    @Test
    void promotionClampsAtTheFinalTierRatherThanOverrunning() {
        assertEquals(6, GunGameService.nextTierAfterPromotion(6, LADDER_SIZE));
    }

    @Test
    void demotionDropsOneTier() {
        assertEquals(2, GunGameService.nextTierAfterDemotion(3));
    }

    @Test
    void demotionClampsAtZeroRatherThanGoingNegative() {
        assertEquals(0, GunGameService.nextTierAfterDemotion(0));
    }

    @Test
    void onlyTheLastIndexIsTheFinalTier() {
        assertFalse(GunGameService.isFinalTier(5, LADDER_SIZE));
        assertTrue(GunGameService.isFinalTier(6, LADDER_SIZE));
    }

    @Test
    void emptyLadderIsNeverTheFinalTierRatherThanDividingByNothing() {
        // A misconfigured server (empty gungame.weapon-ladder) must not crash the check.
        assertFalse(GunGameService.isFinalTier(0, 0));
    }
}
