package org.flintstqne.skirmish.StatLogic;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-rolled `wins_by_mode` (de)serializer (design doc §5.1) — round-trips its own output. */
class WinsByModeTest {

    @Test
    void emptyMapRoundTrips() {
        assertEquals("v1:{}", WinsByMode.serialize(Map.of()));
        assertTrue(WinsByMode.parse("v1:{}").isEmpty());
    }

    @Test
    void legacyUnversionedBlobsStillParse() {
        // Rows written before the v1: prefix existed — must keep reading correctly.
        assertEquals(Map.of("TDM", 5), WinsByMode.parse("{\"TDM\":5}"));
        assertTrue(WinsByMode.parse("{}").isEmpty());
    }

    @Test
    void singleEntryRoundTrips() {
        String json = WinsByMode.serialize(Map.of("TDM", 5));
        assertEquals(Map.of("TDM", 5), WinsByMode.parse(json));
    }

    @Test
    void multipleEntriesRoundTrip() {
        Map<String, Integer> wins = new LinkedHashMap<>();
        wins.put("KOTH", 3);
        wins.put("TDM", 5);
        wins.put("FFA", 1);
        assertEquals(wins, WinsByMode.parse(WinsByMode.serialize(wins)));
    }

    @Test
    void nullIsTreatedAsEmpty() {
        assertTrue(WinsByMode.parse(null).isEmpty());
    }

    @Test
    void blankIsTreatedAsEmpty() {
        assertTrue(WinsByMode.parse("").isEmpty());
        assertTrue(WinsByMode.parse("   ").isEmpty());
    }

    @Test
    void malformedEntryIsSkippedRatherThanFailingTheWholeRead() {
        // A hand-corrupted or partially-written value shouldn't nuke every other entry.
        Map<String, Integer> result = WinsByMode.parse("{\"TDM\":5,\"KOTH\":oops,\"FFA\":1}");
        assertEquals(Map.of("TDM", 5, "FFA", 1), result);
    }
}
