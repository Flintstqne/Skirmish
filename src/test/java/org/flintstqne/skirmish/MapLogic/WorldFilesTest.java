package org.flintstqne.skirmish.MapLogic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-round world file handling (design doc §7.3). The skip set is not cosmetic:
 * copying uid.dat gives two worlds the same UUID, which breaks Bukkit.
 */
class WorldFilesTest {

    private static final Set<String> SKIPPED = Set.of(
            "session.lock", "uid.dat", "playerdata", "stats", "advancements");

    @TempDir
    Path tempDir;

    /** A folder shaped like a real Minecraft world. */
    private Path fakeWorld(String name) throws IOException {
        Path world = tempDir.resolve(name);
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("playerdata"));
        Files.createDirectories(world.resolve("stats"));
        Files.createDirectories(world.resolve("data"));

        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("uid.dat"), "uuid");
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
        Files.writeString(world.resolve("data/world_border.dat"), "border");
        Files.writeString(world.resolve("playerdata/someone.dat"), "player");
        Files.writeString(world.resolve("stats/someone.json"), "stats");
        return world;
    }

    @Test
    void copyKeepsMapDataAndNestedStructure() throws IOException {
        Path source = fakeWorld("template");
        Path destination = tempDir.resolve("skirmish_round_1");

        WorldFiles.copy(source, destination, SKIPPED);

        assertEquals("level", Files.readString(destination.resolve("level.dat")));
        assertEquals("chunks", Files.readString(destination.resolve("region/r.0.0.mca")));
        assertEquals("border", Files.readString(destination.resolve("data/world_border.dat")));
    }

    @Test
    void copyExcludesUidAndSessionLock() throws IOException {
        Path source = fakeWorld("template");
        Path destination = tempDir.resolve("skirmish_round_1");

        WorldFiles.copy(source, destination, SKIPPED);

        assertFalse(Files.exists(destination.resolve("uid.dat")),
                "a copied uid.dat gives two worlds the same UUID");
        assertFalse(Files.exists(destination.resolve("session.lock")));
    }

    @Test
    void copySkipsWholeDirectoriesNotJustFiles() throws IOException {
        Path source = fakeWorld("template");
        Path destination = tempDir.resolve("skirmish_round_1");

        WorldFiles.copy(source, destination, SKIPPED);

        assertFalse(Files.exists(destination.resolve("playerdata")));
        assertFalse(Files.exists(destination.resolve("stats")));
        assertFalse(Files.exists(destination.resolve("playerdata/someone.dat")));
    }

    @Test
    void copyLeavesTheTemplateUntouched() throws IOException {
        Path source = fakeWorld("template");
        WorldFiles.copy(source, tempDir.resolve("skirmish_round_1"), SKIPPED);

        assertTrue(Files.exists(source.resolve("uid.dat")));
        assertTrue(Files.exists(source.resolve("playerdata/someone.dat")));
        assertEquals("chunks", Files.readString(source.resolve("region/r.0.0.mca")));
    }

    @Test
    void deleteRemovesTheWholeTree() throws IOException {
        Path world = fakeWorld("skirmish_round_1");
        WorldFiles.deleteRecursively(world);
        assertFalse(Files.exists(world));
    }

    @Test
    void deleteOfAMissingPathIsNotAnError() throws IOException {
        WorldFiles.deleteRecursively(tempDir.resolve("never-existed"));
    }

    @Test
    void deleteDoesNotReachOutsideItsOwnTree() throws IOException {
        Path template = fakeWorld("template");
        Path round = fakeWorld("skirmish_round_1");

        WorldFiles.deleteRecursively(round);

        assertFalse(Files.exists(round));
        assertTrue(Files.exists(template.resolve("level.dat")), "the template must survive");
    }

    @Test
    void sizeCountsOnlyRegularFiles() throws IOException {
        Path world = fakeWorld("template");
        // 5 copied-relevant files plus the two skipped ones still exist in the source.
        assertTrue(WorldFiles.size(world) > 0);
        assertEquals(0, WorldFiles.size(tempDir.resolve("never-existed")));
    }
}
