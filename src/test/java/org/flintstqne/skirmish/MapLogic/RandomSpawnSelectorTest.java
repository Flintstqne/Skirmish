package org.flintstqne.skirmish.MapLogic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FFA's min-spawn-distance rule (design doc §8.4) — don't spawn someone next to a fight. */
class RandomSpawnSelectorTest {

    @Test
    void emptyOccupiedListIsAlwaysFarEnough() {
        assertTrue(RandomSpawnSelector.isFarEnough(new double[] {0, 0, 0}, new double[0][], 15));
    }

    @Test
    void rejectsACandidateInsideTheMinimumDistance() {
        double[][] occupied = {{10, 64, 0}};
        assertFalse(RandomSpawnSelector.isFarEnough(new double[] {0, 64, 0}, occupied, 15));
    }

    @Test
    void acceptsACandidateBeyondTheMinimumDistance() {
        double[][] occupied = {{20, 64, 0}};
        assertTrue(RandomSpawnSelector.isFarEnough(new double[] {0, 64, 0}, occupied, 15));
    }

    @Test
    void exactlyAtTheThresholdIsAccepted() {
        // "min distance" reads as >= — exactly 15 blocks away counts as far enough.
        double[][] occupied = {{15, 64, 0}};
        assertTrue(RandomSpawnSelector.isFarEnough(new double[] {0, 64, 0}, occupied, 15));
    }

    @Test
    void mustClearTheDistanceFromEveryOccupant() {
        double[][] occupied = {{100, 64, 0}, {5, 64, 0}};
        assertFalse(RandomSpawnSelector.isFarEnough(new double[] {0, 64, 0}, occupied, 15));
    }
}
