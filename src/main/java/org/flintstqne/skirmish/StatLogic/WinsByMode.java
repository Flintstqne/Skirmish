package org.flintstqne.skirmish.StatLogic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-rolled (de)serializer for the `wins_by_mode` column's flat {@code {"KOTH":3,"TDM":5}}
 * blob (design doc §5.1) - a single-level string-to-int map is well within what a few lines
 * can do, so no JSON library was added just for this one column.
 *
 * Only ever reads back what {@link #serialize} itself wrote, so it doesn't need to handle
 * arbitrary/untrusted JSON - just this exact shape.
 *
 * {@code serialize} stamps a {@code v1:} prefix so a future format change has somewhere to
 * branch from; {@code parse} strips the prefix if present and falls back to the original
 * unversioned/bare-JSON shape otherwise, so rows written before this existed still read fine.
 *
 * Public (not package-private) because {@code DatabaseManager} needs it to read/write the
 * column - the same cross-package relationship it already has with {@code LoadoutCatalog}.
 */
public final class WinsByMode {

    private static final String VERSION_PREFIX = "v1:";

    private WinsByMode() {
    }

    public static String serialize(Map<String, Integer> wins) {
        StringBuilder sb = new StringBuilder(VERSION_PREFIX).append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> entry : wins.entrySet()) {
            if (!first) sb.append(",");
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }

    public static Map<String, Integer> parse(String json) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (json == null) return map;
        String body = json.trim();
        if (body.startsWith(VERSION_PREFIX)) body = body.substring(VERSION_PREFIX.length()).trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
        body = body.trim();
        if (body.isEmpty()) return map;

        for (String pair : body.split(",")) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = pair.substring(0, colon).trim().replaceAll("^\"|\"$", "");
            String value = pair.substring(colon + 1).trim();
            try {
                map.put(key, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                // Malformed entry - skip it rather than fail the whole read.
            }
        }
        return map;
    }
}
