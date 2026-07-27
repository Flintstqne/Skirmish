package org.flintstqne.skirmish.ObjectiveLogic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure geometry behind the shared ring (KOTH) and beam (Domination) particle shapes —
 * the part worth verifying without a live World (design doc §8.1/§8.2).
 */
class ObjectiveParticleManagerTest {

    @Test
    void ringProducesExactlyTheRequestedPointCount() {
        double[][] offsets = ObjectiveParticleManager.ringOffsets(8, 24);
        assertEquals(24, offsets.length);
    }

    @Test
    void everyRingPointSitsExactlyRadiusFromCenter() {
        int radius = 8;
        for (double[] offset : ObjectiveParticleManager.ringOffsets(radius, 16)) {
            double distance = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
            assertEquals(radius, distance, 1e-9);
        }
    }

    @Test
    void ringFirstPointLiesOnThePositiveXAxis() {
        double[][] offsets = ObjectiveParticleManager.ringOffsets(10, 4);
        assertEquals(10.0, offsets[0][0], 1e-9);
        assertEquals(0.0, offsets[0][1], 1e-9);
    }

    @Test
    void beamStepCountMatchesHeightTimesDensity() {
        assertEquals(20, ObjectiveParticleManager.beamHeights(5, 4).length);
    }

    @Test
    void beamStartsAtTheBaseAndRisesInEvenSteps() {
        double[] heights = ObjectiveParticleManager.beamHeights(2, 2);
        assertEquals(0.0, heights[0], 1e-9);
        assertEquals(0.5, heights[1], 1e-9);
        assertEquals(1.0, heights[2], 1e-9);
    }

    @Test
    void beamAlwaysProducesAtLeastOneStepEvenForZeroHeight() {
        // A degenerate 0-height beam config shouldn't produce a zero-length array and
        // silently render nothing at all — always draw at least the base point.
        assertEquals(1, ObjectiveParticleManager.beamHeights(0, 4).length);
    }
}
