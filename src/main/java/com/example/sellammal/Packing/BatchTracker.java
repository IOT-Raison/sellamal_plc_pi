package com.example.sellammal.Packing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps the current batch. A new batch starts whenever the set weight changes:
 * batchId = yyMMddHHmm (batch start) + set weight, e.g. 2611201145 + 25 -> "261120114525".
 * State is saved to data/batch-state.properties so a restart does not start a new batch.
 */
@Component
public class BatchTracker {

    private static final Logger log = LoggerFactory.getLogger(BatchTracker.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyMMddHHmm");

    private final Path stateFile = Paths.get("data", "batch-state.properties").toAbsolutePath();

    @Value("${packing.ignore-zero-set-weight:true}")
    private boolean ignoreZero;

    private String batchId;
    private Double setWeight;
    private boolean loaded;

    /** Returns the batchId for this reading, starting a new batch if set weight changed. */
    public synchronized String batchFor(double currentSetWeight, ZonedDateTime now) {
        load();
        boolean zero = Math.abs(currentSetWeight) < 0.0005;

        if (batchId != null && zero && ignoreZero) {
            return batchId; // machine idle / HMI cleared: stay in current batch
        }
        if (batchId == null || setWeight == null
                || Math.abs(currentSetWeight - setWeight) >= 0.0005) {
            String old = batchId;
            batchId = now.format(ID_TIME) + weightToken(currentSetWeight);
            setWeight = currentSetWeight;
            save();
            log.info("New batch {} (set weight {}), previous batch {}", batchId, currentSetWeight, old);
        }
        return batchId;
    }

    public synchronized String currentBatchId() {
        load();
        return batchId;
    }

    /** 25.0 -> "25", 12.5 -> "12.5" */
    static String weightToken(double w) {
        return new BigDecimal(Double.toString(w)).stripTrailingZeros().toPlainString();
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (Files.exists(stateFile)) {
                Properties p = new Properties();
                try (var r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                batchId = p.getProperty("batchId");
                String w = p.getProperty("setWeight");
                setWeight = w == null ? null : Double.valueOf(w);
                log.info("Resumed batch {} (set weight {})", batchId, setWeight);
            }
        } catch (Exception ex) {
            log.error("Could not read {}: {}", stateFile, ex.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(stateFile.getParent());
            Properties p = new Properties();
            p.setProperty("batchId", batchId);
            p.setProperty("setWeight", Double.toString(setWeight));
            Path tmp = stateFile.resolveSibling("batch-state.properties.tmp");
            try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "Current packing batch");
            }
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            log.error("Could not save {}: {}", stateFile, ex.getMessage());
        }
    }
}
