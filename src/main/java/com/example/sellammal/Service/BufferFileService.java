package com.example.sellammal.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.sellammal.DTO.CloudPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * File-backed FIFO queue of readings waiting for upload (data/mfm-buffer.jsonl),
 * one JSON record per line.
 *  - append() syncs each new line to the SD card (survives a power cut)
 *  - peekBatch()/removeFirst(n) let the uploader rewrite the file once per batch
 *  - corrupt lines (e.g. NUL bytes after a power cut) are moved to *.bad and skipped
 */
@Service
public class BufferFileService {

    private static final Logger log = LoggerFactory.getLogger(BufferFileService.class);

    private final ObjectMapper mapper;
    private final Path bufferPath = Paths.get("data", "mfm-buffer.jsonl").toAbsolutePath();

    public BufferFileService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized void append(CloudPayload payload) throws IOException {
        Files.createDirectories(bufferPath.getParent());
        Files.writeString(bufferPath, mapper.writeValueAsString(payload) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, StandardOpenOption.DSYNC);
    }

    /** Oldest record, or null if none. */
    public synchronized CloudPayload peek() throws IOException {
        List<CloudPayload> l = peekBatch(1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Up to max oldest records, in order. Corrupt lines in front of them are removed. */
    public synchronized List<CloudPayload> peekBatch(int max) throws IOException {
        List<CloudPayload> out = new ArrayList<>();
        if (!Files.exists(bufferPath)) {
            return out;
        }
        List<String> lines = Files.readAllLines(bufferPath, StandardCharsets.UTF_8);
        List<String> good = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        boolean dirty = false;
        int i = 0;
        for (; i < lines.size() && out.size() < max; i++) {
            String raw = lines.get(i);
            String line = raw.replace("\u0000", "").trim();
            if (line.isEmpty()) {
                if (!raw.isEmpty()) { bad.add(raw); dirty = true; }
                continue;
            }
            try {
                out.add(mapper.readValue(line, CloudPayload.class));
                good.add(line);
                if (!line.equals(raw)) dirty = true;
            } catch (Exception ex) {
                bad.add(raw);
                dirty = true;
            }
        }
        if (dirty) {
            quarantine(bad);
            List<String> rest = new ArrayList<>(good);
            for (int k = i; k < lines.size(); k++) {
                String l = lines.get(k).replace("\u0000", "").trim();
                if (!l.isEmpty()) rest.add(l);
            }
            writeAtomically(rest);
        }
        return out;
    }

    public synchronized void removeFirst() throws IOException {
        removeFirst(1);
    }

    /** Remove the n oldest records (one file rewrite). */
    public synchronized void removeFirst(int n) throws IOException {
        if (n <= 0 || !Files.exists(bufferPath)) {
            return;
        }
        List<String> lines = Files.readAllLines(bufferPath, StandardCharsets.UTF_8);
        List<String> rest = new ArrayList<>(Math.max(0, lines.size() - n));
        int removed = 0;
        for (String l : lines) {
            if (l.isBlank()) continue;
            if (removed < n) { removed++; continue; }
            rest.add(l);
        }
        if (rest.isEmpty()) {
            Files.deleteIfExists(bufferPath);
        } else {
            writeAtomically(rest);
        }
    }

    public synchronized long size() throws IOException {
        if (!Files.exists(bufferPath)) return 0;
        try (var s = Files.lines(bufferPath, StandardCharsets.UTF_8)) {
            return s.filter(l -> !l.isBlank()).count();
        }
    }

    /** Write to a temp file, sync it ONCE, then atomically replace the buffer. */
    private void writeAtomically(List<String> lines) throws IOException {
        Path tmp = bufferPath.resolveSibling(bufferPath.getFileName() + ".tmp");
        StringBuilder sb = new StringBuilder(lines.size() * 400);
        for (String l : lines) sb.append(l).append(System.lineSeparator());
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        try {
            Files.move(tmp, bufferPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, bufferPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine(List<String> bad) throws IOException {
        if (bad.isEmpty()) return;
        Path badFile = bufferPath.resolveSibling(bufferPath.getFileName() + ".bad");
        StringBuilder sb = new StringBuilder();
        for (String b : bad) sb.append(b.replace("\u0000", "\\0")).append(System.lineSeparator());
        Files.writeString(badFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        log.warn("Moved {} corrupt line(s) from {} to {}", bad.size(), bufferPath.getFileName(), badFile.getFileName());
    }
}
