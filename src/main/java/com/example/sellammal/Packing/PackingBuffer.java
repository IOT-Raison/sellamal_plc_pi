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
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    public synchronized PackingRecord peek() throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                return mapper.readValue(line, PackingRecord.class);
            }
        }
        return null;
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
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
