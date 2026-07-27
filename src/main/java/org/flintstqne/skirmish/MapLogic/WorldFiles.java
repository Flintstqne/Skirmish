package org.flintstqne.skirmish.MapLogic;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * Filesystem half of the per-round world lifecycle (design doc §7.3), kept free of Bukkit
 * so it can be tested directly — a bug in {@link #deleteRecursively} deletes real data.
 */
public final class WorldFiles {

    private WorldFiles() {
    }

    /**
     * Copies a world folder, skipping any file or directory whose name is in {@code skip}.
     *
     * @throws IOException so the caller decides how loudly to fail
     */
    public static void copy(Path source, Path destination, Set<String> skip) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (skip.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (skip.contains(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                Files.copy(file, destination.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Deletes a directory tree. A path that doesn't exist counts as already deleted. */
    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Total size of a directory tree, for logging how much a round world costs. */
    public static long size(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }
}
