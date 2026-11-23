package com.example.sellammal.Packing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.example.sellammal.Config.PLCConfig;
import com.example.sellammal.Modbus.PlcConnectionManager;

/**
 * Packing machine (on the Huller PLC).
 * Polls every packing.poll-ms (default 1 s). Whenever the bag count changes, one record is
 * created with the batch and the actual bag weight (totalizer difference), buffered to
 * data/packing-buffer.jsonl and POSTed to packing.api.url (separate from the energy API).
 */
@Service
public class PackingService {

    private static final Logger log = LoggerFactory.getLogger(PackingService.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_PER_RUN = 500;

    private final PlcConnectionManager plcConnections;
    private final BatchTracker batches;
    private final PackingBuffer buffer;
    private final RestTemplate rest;
    private final Path lastFile = Paths.get("data", "packing-last.properties").toAbsolutePath();

    @Value("${packing.enabled:true}") private boolean enabled;
    @Value("${packing.api.url:}") private String apiUrl;
    @Value("${packing.api.key:}") private String apiKey;
    @Value("${packing.device-id:23}") private int deviceId;
    @Value("${packing.zone:Asia/Kolkata}") private String zone;
    @Value("${packing.plc-ip:192.168.1.1}") private String plcIp;
    @Value("${packing.plc-port:502}") private int plcPort;
    @Value("${packing.set-weight-address:41390}") private int setWeightAddr;
    @Value("${packing.totalizer-address:41398}") private int totalizerAddr;
    @Value("${packing.bag-count-address:41400}") private int bagCountAddr;
    @Value("${packing.set-weight-type:FLOAT32}") private String setWeightType;
    @Value("${packing.totalizer-type:FLOAT32}") private String totalizerType;
    @Value("${packing.bag-count-type:FLOAT32}") private String bagCountType;
    @Value("${modbus.address-base:40001}") private int addressBase;
    @Value("${modbus.word-order:ABCD}") private String wordOrder;
    @Value("${modbus.unit-id:1}") private int unitId;

    // Last seen bag count / totalizer (persisted so a restart keeps bag weights correct)
    private Long lastBagCount;
    private Double lastTotalizer;
    private boolean lastLoaded;
    private int consecutiveFailures;
    private volatile boolean blockReadRefused;

    public PackingService(PlcConnectionManager plcConnections, BatchTracker batches, PackingBuffer buffer) {
        this.plcConnections = plcConnections;
        this.batches = batches;
        this.buffer = buffer;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(30_000);
        this.rest = new RestTemplate(f);
    }

    /** One consistent snapshot of the three values. */
    public record Snapshot(double setWeight, double totalizer, long bagCount) {
    }

    public Snapshot readSnapshot() throws Exception {
        PLCConfig plc = new PLCConfig("Huller PLC", plcIp, plcPort);
        int start = Math.min(setWeightAddr, Math.min(totalizerAddr, bagCountAddr));
        int end = Math.max(setWeightAddr, Math.max(totalizerAddr, bagCountAddr)) + 2;
        synchronized (plcConnections.lockFor(plc)) {
            if (end - start <= 60 && !blockReadRefused) {
                // All three in ONE Modbus request, so totalizer and bag count are from the same moment
                try {
                    int[] regs = plcConnections.readHoldingRegisters(plc, unitId, start - addressBase, end - start);
                    return new Snapshot(
                            round3(decode(regs, setWeightAddr - start, setWeightType, setWeightAddr)),
                            round3(decode(regs, totalizerAddr - start, totalizerType, totalizerAddr)),
                            Math.round(decode(regs, bagCountAddr - start, bagCountType, bagCountAddr)));
                } catch (com.ghgande.j2mod.modbus.ModbusSlaveException ex) {
                    // PLC refused the address range (gap in its map): read the values one by one from now on
                    blockReadRefused = true;
                    log.warn("PLC refused block read {}-{} ({}); reading packing values individually",
                            start, end - 1, ex.getMessage());
                }
            }
            return new Snapshot(
                    round3(readOne(plc, setWeightAddr, setWeightType)),
                    round3(readOne(plc, totalizerAddr, totalizerType)),
                    Math.round(readOne(plc, bagCountAddr, bagCountType)));
        }
    }

    /** Polls the PLC; creates a record only when the bag count changes. */
    @Scheduled(fixedDelayString = "${packing.poll-ms:1000}")
    public synchronized void poll() {
        if (!enabled) {
            return;
        }
        Snapshot s;
        try {
            s = readSnapshot();
            if (consecutiveFailures > 0) {
                log.info("Packing machine reachable again after {} failed polls", consecutiveFailures);
            }
            consecutiveFailures = 0;
        } catch (Exception ex) {
            // Log first failure, then once a minute, so a dead PLC does not flood the log
            if (consecutiveFailures++ % 60 == 0) {
                log.error("Failed to read packing machine from {} ({} failures): {}",
                        plcIp, consecutiveFailures, ex.getMessage());
            }
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zone));
        String batchId = batches.batchFor(s.setWeight(), now);   // tracks set-weight changes every poll

        loadLast();
        if (lastBagCount != null && s.bagCount() == lastBagCount) {
            return; // no new bag
        }

        Double bagWeight = null;
        if (lastBagCount != null && lastTotalizer != null) {
            long bags = s.bagCount() - lastBagCount;
            double weight = s.totalizer() - lastTotalizer;
            if (bags > 0 && weight >= 0) {
                bagWeight = round3(weight / bags);
                if (bags > 1) {
                    log.warn("{} bags since last reading (bag count {} -> {}); bagWeight is the average",
                            bags, lastBagCount, s.bagCount());
                }
            } else {
                log.warn("Counter reset (bag count {} -> {}, totalizer {} -> {}); bagWeight not calculated",
                        lastBagCount, s.bagCount(), lastTotalizer, s.totalizer());
            }
        }

        PackingRecord r = new PackingRecord(now.format(DT), deviceId, batchId,
                s.setWeight(), s.totalizer(), s.bagCount(), bagWeight);
        saveLast(s.bagCount(), s.totalizer());

        if (apiUrl == null || apiUrl.isBlank()) {
            log.info("Bag (not uploaded, packing.api.url empty): {}", r);
            return;
        }
        try {
            buffer.append(r);
            log.info("Bag {} batch={} bagWeight={}", r.bagCount(), r.batchId(), r.bagWeight());
        } catch (Exception ex) {
            log.error("Could not buffer packing record: {}", ex.getMessage());
        }
    }

    /** Live values for /api/packing/test (does not create a record). */
    public Object live() throws Exception {
        Snapshot s = readSnapshot();
        loadLast();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("setWeight", s.setWeight());
        m.put("totalizer", s.totalizer());
        m.put("bagCount", s.bagCount());
        m.put("currentBatch", batches.currentBatchId());
        m.put("lastLoggedBagCount", lastBagCount);
        m.put("lastLoggedTotalizer", lastTotalizer);
        return m;
    }

    @Scheduled(fixedDelay = 5000)
    public void upload() {
        if (!enabled || apiUrl == null || apiUrl.isBlank()) {
            return;
        }
        for (int i = 0; i < MAX_PER_RUN; i++) {
            try {
                PackingRecord r = buffer.peek();
                if (r == null) {
                    return;
                }
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                if (apiKey != null && !apiKey.isBlank()) {
                    h.set("X-API-Key", apiKey);
                }
                ResponseEntity<String> resp = rest.postForEntity(apiUrl, new HttpEntity<>(r, h), String.class);
                if (!resp.getStatusCode().is2xxSuccessful()) {
                    log.error("Packing upload rejected HTTP {}; record retained", resp.getStatusCode());
                    return;
                }
                buffer.removeFirst();
                log.info("Uploaded bag {} batch={}", r.bagCount(), r.batchId());
            } catch (HttpClientErrorException ex) {
                log.error("Packing record rejected permanently (HTTP {}), dropping it: {}",
                        ex.getStatusCode(), ex.getResponseBodyAsString());
                try {
                    buffer.removeFirst();
                } catch (Exception e) {
                    return;
                }
            } catch (Exception ex) {
                log.error("Packing upload failed; record retained: {}", ex.getMessage());
                return;
            }
        }
    }

    // ---------- helpers ----------

    private double readOne(PLCConfig plc, int address, String type) throws Exception {
        int n = isOneRegister(type) ? 1 : 2;
        int[] regs = plcConnections.readHoldingRegisters(plc, unitId, address - addressBase, n);
        return decode(regs, 0, type, address);
    }

    private static boolean isOneRegister(String type) {
        String t = type.toUpperCase(Locale.ROOT);
        return t.equals("INT16") || t.equals("UINT16");
    }

    private double decode(int[] regs, int i, String type, int address) {
        String t = type.toUpperCase(Locale.ROOT);
        if (t.equals("INT16")) return (short) regs[i];
        if (t.equals("UINT16")) return regs[i];
        boolean swap = "CDAB".equalsIgnoreCase(wordOrder);
        int hi = swap ? regs[i + 1] : regs[i];
        int lo = swap ? regs[i] : regs[i + 1];
        int raw = (hi << 16) | (lo & 0xFFFF);
        switch (t) {
            case "INT32":  return raw;
            case "UINT32": return raw & 0xFFFFFFFFL;
            default:
                float f = Float.intBitsToFloat(raw);
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    throw new IllegalStateException("NaN/Inf at " + address + " - check type/word order");
                }
                return f;
        }
    }

    private void loadLast() {
        if (lastLoaded) {
            return;
        }
        lastLoaded = true;
        try {
            if (Files.exists(lastFile)) {
                Properties p = new Properties();
                try (var r = Files.newBufferedReader(lastFile, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                lastBagCount = Long.valueOf(p.getProperty("bagCount"));
                lastTotalizer = Double.valueOf(p.getProperty("totalizer"));
                log.info("Resumed packing at bag count {}, totalizer {}", lastBagCount, lastTotalizer);
            }
        } catch (Exception ex) {
            log.error("Could not read {}: {}", lastFile, ex.getMessage());
        }
    }

    private void saveLast(long bagCount, double totalizer) {
        lastBagCount = bagCount;
        lastTotalizer = totalizer;
        try {
            Files.createDirectories(lastFile.getParent());
            Properties p = new Properties();
            p.setProperty("bagCount", Long.toString(bagCount));
            p.setProperty("totalizer", Double.toString(totalizer));
            Path tmp = lastFile.resolveSibling("packing-last.properties.tmp");
            try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "Last logged bag");
            }
            Files.move(tmp, lastFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            log.error("Could not save {}: {}", lastFile, ex.getMessage());
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
