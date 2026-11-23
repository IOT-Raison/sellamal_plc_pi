package com.example.sellammal.Packing;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Commissioning helper: /api/packing/test shows live packing values, current batch and
 * the last logged bag (nothing buffered or uploaded). Raw registers: /api/plc/raw?ip=192.168.1.1&start=41388&count=16
 */
@RestController
public class PackingController {

    private final PackingService service;
    private final BatchTracker batches;

    public PackingController(PackingService service, BatchTracker batches) {
        this.service = service;
        this.batches = batches;
    }

    @GetMapping("/api/packing/test")
    public Object test() {
        try {
            return service.live();
        } catch (Exception ex) {
            return Map.of("error", String.valueOf(ex.getMessage()),
                    "currentBatch", String.valueOf(batches.currentBatchId()));
        }
    }
}
