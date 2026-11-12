package com.example.sellammal.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.sellammal.Config.MFMConfig;

/**
 * Commissioning helper: open http://localhost:8080/api/plc/test
 * to read every meter live (nothing is buffered or sent to cloud).
 */
@RestController
public class PlcTestController {

    private final List<MFMConfig> mfmConfigs;
    private final MFMReadingService readingService;

    public PlcTestController(List<MFMConfig> mfmConfigs, MFMReadingService readingService) {
        this.mfmConfigs = mfmConfigs;
        this.readingService = readingService;
    }

    @GetMapping("/api/plc/test")
    public Map<String, Object> test() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (MFMConfig c : mfmConfigs) {
            String key = c.getMeterName() + " [" + c.getPlc().getIpAddress()
                    + ", deviceId " + c.getDeviceId() + "]";
            try {
                result.put(key, readingService.readMeter(c));
            } catch (Exception ex) {
                result.put(key, "ERROR: " + ex.getMessage());
            }
        }
        return result;
    }
}
