package org.flintstqne.skirmish.ObjectiveLogic;

import org.bukkit.Location;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KOTH's standard contest rule and hardpoint-style rotation (design doc §8.1). */
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

    private Location at(double x, double y, double z) {
        return new Location(null, x, y, z);
    }

    @Test
    void rotationExcludesTheCurrentPointWhenOthersExist() {
        Location current = at(0, 64, 0);
        Location other = at(50, 64, 50);
        List<Location> candidates = HillObjective.candidatesExcluding(List.of(current, other), current);
        assertEquals(List.of(other), candidates);
    }

    @Test
    void rotationFallsBackToTheFullPoolWhenOnlyOnePointExists() {
        Location onlyPoint = at(0, 64, 0);
        List<Location> candidates = HillObjective.candidatesExcluding(List.of(onlyPoint), onlyPoint);
        assertEquals(List.of(onlyPoint), candidates);
    }

    @Test
    void rotationWithNoCurrentPointReturnsTheWholePool() {
        List<Location> pool = List.of(at(0, 64, 0), at(50, 64, 50));
        assertEquals(pool, HillObjective.candidatesExcluding(pool, null));
    }

    @Test
    void rotationComparesCoordinatesNotObjectIdentity() {
        // Pool entries are re-deserialized from arena.yml every call, so a fresh Location with
        // the same coordinates as "current" must still be excluded, not just an identical ref.
        Location current = at(10, 64, 10);
        Location sameSpotDifferentInstance = at(10, 64, 10);
        Location elsewhere = at(20, 64, 20);
        List<Location> candidates = HillObjective.candidatesExcluding(
                List.of(sameSpotDifferentInstance, elsewhere), current);
        assertEquals(List.of(elsewhere), candidates);
    }

    @Test
    void emptyPoolStaysEmpty() {
        assertTrue(HillObjective.candidatesExcluding(List.of(), at(0, 64, 0)).isEmpty());
    }
}
