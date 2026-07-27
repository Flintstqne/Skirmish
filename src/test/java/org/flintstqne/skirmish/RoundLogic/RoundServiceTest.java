package org.flintstqne.skirmish.RoundLogic;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FFA's winner-by-score pick (design doc §8.4) — used both for a threshold win and a timer-expiry draw check. */
class RoundServiceTest {

    @Test
    void emptyScoresIsADraw() {
        assertNull(RoundService.pickLeader(Map.of()));
    }

    @Test
    void soleScorerWins() {
        UUID player = UUID.randomUUID();
        assertEquals(player, RoundService.pickLeader(Map.of(player, 3)));
    }

    @Test
    void highestScorerWins() {
        UUID leader = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, Integer> scores = Map.of(leader, 10, second, 4);
        assertEquals(leader, RoundService.pickLeader(scores));
    }

    @Test
    void tiedTopScoreIsADraw() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertNull(RoundService.pickLeader(Map.of(a, 5, b, 5)));
    }

    @Test
    void aLaterEqualScoreBreaksTheLeadRatherThanWinningItBack() {
        // Encoded via insertion order: first entry takes the lead, second ties it — the
        // eventual third, higher entry should cleanly resolve to a single winner again.
        UUID first = UUID.randomUUID();
        UUID tying = UUID.randomUUID();
        UUID overtaking = UUID.randomUUID();
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        scores.put(first, 5);
        scores.put(tying, 5);
        scores.put(overtaking, 8);
        assertEquals(overtaking, RoundService.pickLeader(scores));
    }

    @Test
    void zeroScoresStillProduceALeaderIfOnlyOnePlayerHasAny() {
        UUID player = UUID.randomUUID();
        assertEquals(player, RoundService.pickLeader(Map.of(player, 0)));
    }

    // ---- warm-up countdown (design doc §11 item 1) -----------------------------

    @Test
    void oneSecondLeftIsTheFinalTick() {
        assertTrue(RoundService.isFinalWarmupTick(1));
    }

    @Test
    void zeroOrNegativeSecondsAreAlsoFinal() {
        assertTrue(RoundService.isFinalWarmupTick(0));
        assertTrue(RoundService.isFinalWarmupTick(-1));
    }

    @Test
    void moreThanOneSecondLeftIsNotFinal() {
        assertFalse(RoundService.isFinalWarmupTick(2));
    }
}
