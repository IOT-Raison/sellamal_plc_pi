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
                StandardOpenOption.APPEND
        );
    }

    public synchronized CloudPayload peek()
            throws IOException {

        if (!Files.exists(bufferPath)) {
            return null;
        }

        for (String line : Files.readAllLines(
                bufferPath, StandardCharsets.UTF_8)) {

            if (!line.isBlank()) {
                return mapper.readValue(line, CloudPayload.class);
            }
        }

        return null;
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
                StandardOpenOption.TRUNCATE_EXISTING
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
