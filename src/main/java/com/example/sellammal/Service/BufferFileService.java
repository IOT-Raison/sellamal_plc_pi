package com.example.sellammal.Service;

import com.example.sellammal.DTO.CloudPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

@Service
public class BufferFileService {

    private final ObjectMapper mapper;
    private final Path bufferPath =
            Paths.get("data", "mfm-buffer.jsonl").toAbsolutePath();

    public BufferFileService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized void append(CloudPayload payload)
            throws IOException {

        Files.createDirectories(bufferPath.getParent());

        String json = mapper.writeValueAsString(payload);

        Files.writeString(
                bufferPath,
                json + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC
        );
    }

    /**
     * Oldest valid record, or null if none.
     * Corrupt lines (e.g. NUL bytes after a power cut) are moved to
     * <file>.bad and removed, so one bad line can never block the queue.
     */
    public synchronized CloudPayload peek()
            throws IOException {

        if (!Files.exists(bufferPath)) {
            return null;
        }

        List<String> lines = Files.readAllLines(bufferPath, StandardCharsets.UTF_8);
        List<String> bad = new java.util.ArrayList<>();
        CloudPayload found = null;
        int i = 0;
        for (; i < lines.size(); i++) {
            String line = lines.get(i).replace("\u0000", "").trim();
            if (line.isEmpty()) {
                if (!lines.get(i).isEmpty()) bad.add(lines.get(i)); // only NULs / junk
                continue;
            }
            try {
                found = mapper.readValue(line, CloudPayload.class);
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
        Path badFile = bufferPath.resolveSibling(bufferPath.getFileName() + ".bad");
        StringBuilder sb = new StringBuilder();
        for (String b : bad) sb.append(b.replace("\u0000", "\\0")).append(System.lineSeparator());
        Files.writeString(badFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Moved {} corrupt line(s) from {} to {}", bad.size(), bufferPath.getFileName(), badFile.getFileName());
    }

    private void rewrite(List<String> lines) throws IOException {
        Path tmp = bufferPath.resolveSibling(bufferPath.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.DSYNC);
        try {
            Files.move(tmp, bufferPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, bufferPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void removeFirst()
            throws IOException {

        if (!Files.exists(bufferPath)) {
            return;
        }

        List<String> lines = Files.readAllLines(
                bufferPath, StandardCharsets.UTF_8);

        int first = -1;

        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                first = i;
                break;
            }
        }

        if (first < 0) {
            Files.deleteIfExists(bufferPath);
            return;
        }

        lines.remove(first);

        Path tempPath = bufferPath.resolveSibling(
                bufferPath.getFileName() + ".tmp");

        Files.write(
                tempPath,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.DSYNC
        );

        try {
            Files.move(
                    tempPath,
                    bufferPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(
                    tempPath,
                    bufferPath,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
