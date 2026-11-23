package com.example.sellammal.Packing;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One record per bag-count change, as sent to the packing API:
 * {"dateTime":"2026-11-20 12:47:05","deviceId":23,"batchId":"261120124525",
 *  "setWeight":25.0,"totalizer":12345.5,"bagCount":241,"bagWeight":25.04}
 *
 * bagWeight = (totalizer - previous totalizer) / (bagCount - previous bagCount);
 * null when it cannot be calculated (first ever reading, or a counter reset).
 */
@JsonPropertyOrder({"dateTime", "deviceId", "batchId", "setWeight", "totalizer", "bagCount", "bagWeight"})
public record PackingRecord(
        String dateTime,
        int deviceId,
        String batchId,
        double setWeight,
        double totalizer,
        long bagCount,
        Double bagWeight) {
}
