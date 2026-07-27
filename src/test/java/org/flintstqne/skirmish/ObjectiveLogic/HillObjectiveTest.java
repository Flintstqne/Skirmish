package org.flintstqne.skirmish.ObjectiveLogic;

import org.flintstqne.skirmish.TeamLogic.Team;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** KOTH's standard contest rule (design doc §8.1). */
class HillObjectiveTest {

    @Test
    void soleTeamPresentHoldsTheHill() {
        assertEquals(Team.RED, HillObjective.resolveHolder(true, false));
        assertEquals(Team.BLUE, HillObjective.resolveHolder(false, true));
    }

    @Test
    void bothTeamsPresentIsContestedAndScoresForNeither() {
        assertNull(HillObjective.resolveHolder(true, true));
    }

    @Test
    void emptyHillIsUnheld() {
        assertNull(HillObjective.resolveHolder(false, false));
    }
}
