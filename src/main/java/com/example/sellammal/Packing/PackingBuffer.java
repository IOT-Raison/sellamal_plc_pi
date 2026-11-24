package com.example.sellammal.Packing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** File-backed FIFO queue (data/packing-buffer.jsonl), one JSON record per line. */
@Component
public class PackingBuffer {

    private final Path path = Paths.get("data", "packing-buffer.jsonl").toAbsolutePath();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public synchronized void append(PackingRecord r) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writeValueAsString(r) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC);
    }

    /**
     * Oldest valid record, or null if none.
     * Corrupt lines (e.g. NUL bytes after a power cut) are moved to
     * <file>.bad and removed, so one bad line can never block the queue.
     */
    public synchronized PackingRecord peek()
            throws IOException {

        if (!Files.exists(path)) {
            return null;
        }

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> bad = new java.util.ArrayList<>();
        PackingRecord found = null;
        int i = 0;
        for (; i < lines.size(); i++) {
            String line = lines.get(i).replace("\u0000", "").trim();
            if (line.isEmpty()) {
                if (!lines.get(i).isEmpty()) bad.add(lines.get(i)); // only NULs / junk
                continue;
            }
            try {
                found = mapper.readValue(line, PackingRecord.class);
                break;
            } catch (Exception ex) {
                bad.add(lines.get(i));
            }
        }

        if (!bad.isEmpty() || (found != null && lines.get(i).indexOf('\u0000') >= 0)) {
            quarantine(bad);
            // Rewrite the file starting at the first valid record (cleaned of NULs)
            List<String> rest = new java.util.ArrayList<>();
            for (int k = i; k < lines.size(); k++) {
                String l = lines.get(k).replace("\u0000", "");
                if (!l.isBlank()) rest.add(l.trim());
            }
            rewrite(rest);
        }
        return found;
    }

    private void quarantine(List<String> bad) throws IOException {
        if (bad.isEmpty()) return;
        Path badFile = path.resolveSibling(path.getFileName() + ".bad");
        StringBuilder sb = new StringBuilder();
        for (String b : bad) sb.append(b.replace("\u0000", "\\0")).append(System.lineSeparator());
        Files.writeString(badFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Moved {} corrupt line(s) from {} to {}", bad.size(), path.getFileName(), badFile.getFileName());
    }

    private void rewrite(List<String> lines) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        syncFile(tmp);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void removeFirst() throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int first = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            Files.deleteIfExists(path);
            return;
        }
        lines.remove(first);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        syncFile(tmp);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Flush a written file to the SD card once (cheaper than syncing every write). */
    private static void syncFile(Path p) throws IOException {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(p, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }
}
