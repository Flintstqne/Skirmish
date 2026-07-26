package org.flintstqne.skirmish.TeamLogic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Imbalance lock rule from design doc §7.2. Ratio is fuller/shorter, default 1.5. */
class TeamServiceTest {

    private static final double RATIO = 1.5;

    @Test
    void balancedTeamsAreNeverLocked() {
        assertFalse(TeamService.isLocked(0, 0, RATIO));
        assertFalse(TeamService.isLocked(5, 5, RATIO));
    }

    @Test
    void theShorterSideIsNeverLocked() {
        assertFalse(TeamService.isLocked(4, 6, RATIO));
        assertFalse(TeamService.isLocked(0, 8, RATIO));
    }

    @Test
    void fullerSideLocksOnlyOnceRatioIsMet() {
        // 6v4 = 1.5 exactly — the doc's own worked example, locked.
        assertTrue(TeamService.isLocked(6, 4, RATIO));
        // 5v4 = 1.25 — ahead, but not far enough to lock.
        assertFalse(TeamService.isLocked(5, 4, RATIO));
    }

    @Test
    void firstJoinerDoesNotLockAnEmptyServer() {
        // 0 vs 0: joining either side must stay open, or nobody can ever join.
        assertFalse(TeamService.isLocked(0, 0, RATIO));
        // 1 vs 0: any lead over an empty team is infinite ratio — locked.
        assertTrue(TeamService.isLocked(1, 0, RATIO));
    }

    @Test
    void lockLiftsWhenTheGapCloses() {
        assertTrue(TeamService.isLocked(6, 4, RATIO));
        assertFalse(TeamService.isLocked(6, 5, RATIO));
    }

    @Test
    void ratioIsConfigurable() {
        assertFalse(TeamService.isLocked(6, 4, 2.0));
        assertTrue(TeamService.isLocked(8, 4, 2.0));
    }
}
