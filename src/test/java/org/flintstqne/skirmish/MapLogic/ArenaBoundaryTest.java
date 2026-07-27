package org.flintstqne.skirmish.MapLogic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Boundary math for the fake-wall renderer (feature added on top of design doc §10's
 * `/arena setboundary`). These are the two things that go visibly wrong if botched:
 * corners stored in the wrong order, and a render window that overruns the box.
 */
class ArenaBoundaryTest {

    @Test
    void axisOrdersCornersRegardlessOfInputOrder() {
        assertArrayEquals(new int[] {-10, 10}, ArenaBoundary.axis(-10, 10));
        assertArrayEquals(new int[] {-10, 10}, ArenaBoundary.axis(10, -10), "corners can be set in either order");
    }

    @Test
    void axisFloorsFractionalCoordinates() {
        // Players stand at fractional coordinates; the box must still be block-aligned.
        assertArrayEquals(new int[] {-11, 10}, ArenaBoundary.axis(-10.5, 10.9));
    }

    @Test
    void windowCentersOnPlayerWhenFarFromBothEdges() {
        assertArrayEquals(new int[] {40, 60}, ArenaBoundary.window(50, 10, -100, 100));
    }

    @Test
    void windowClampsAtTheMinEdgeRatherThanOverrunningTheBox() {
        // Standing right at the box's low edge: the window must not extend past it.
        assertArrayEquals(new int[] {-100, -90}, ArenaBoundary.window(-100, 10, -100, 100));
    }

    @Test
    void windowClampsAtTheMaxEdge() {
        assertArrayEquals(new int[] {90, 100}, ArenaBoundary.window(100, 10, -100, 100));
    }

    @Test
    void windowNeverExceedsTheAxisEvenWhenHalfWidthIsLargerThanTheBox() {
        // A tiny arena with a generously configured wall-half-width must not paint blocks
        // outside the box's own bounds.
        assertArrayEquals(new int[] {-5, 5}, ArenaBoundary.window(0, 50, -5, 5));
    }
}
