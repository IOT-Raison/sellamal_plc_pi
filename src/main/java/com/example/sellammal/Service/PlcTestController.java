package com.example.sellammal.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.sellammal.Config.MFMConfig;
import com.example.sellammal.Config.PLCConfig;
import com.example.sellammal.Modbus.PlcConnectionManager;

/**
 * Commissioning helpers (nothing is buffered or sent to cloud):
 *   /api/plc/test  - read every meter live, as the cloud would receive it
 *   /api/plc/raw?ip=192.168.1.10&start=40701&count=60
 *                  - raw holding registers + float/int decoding of each pair
 */
@RestController
public class PlcTestController {

    private final List<MFMConfig> mfmConfigs;
    private final MFMReadingService readingService;
    private final PlcConnectionManager plcConnections;

    @Value("${modbus.address-base:40001}")
    private int addressBase;

    @Value("${modbus.unit-id:1}")
    private int unitId;

    public PlcTestController(List<MFMConfig> mfmConfigs,
                             MFMReadingService readingService,
                             PlcConnectionManager plcConnections) {
        this.mfmConfigs = mfmConfigs;
        this.readingService = readingService;
        this.plcConnections = plcConnections;
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

    @GetMapping("/api/plc/raw")
    public Object raw(@RequestParam String ip,
                      @RequestParam int start,
                      @RequestParam(defaultValue = "40") int count) {

        PLCConfig plc = mfmConfigs.stream()
                .map(MFMConfig::getPlc)
                .filter(p -> p.getIpAddress().equals(ip))
                .findFirst()
                .orElse(new PLCConfig("PLC " + ip, ip, 502));

        count = Math.max(2, Math.min(count, 120));
        int[] regs = new int[count];
        try {
            for (int done = 0; done < count; ) {
                int n = Math.min(60, count - done);
                int[] part = plcConnections.readHoldingRegisters(
                        plc, unitId, start - addressBase + done, n);
                System.arraycopy(part, 0, regs, done, n);
                done += n;
            }
        } catch (Exception ex) {
            return "ERROR: " + ex.getMessage();
        }

        List<String> rows = new ArrayList<>();
        rows.add("address | raw (hex) | float ABCD | float CDAB | int32 ABCD | int32 CDAB");
        for (int i = 0; i < count; i++) {
            int addr = start + i;
            StringBuilder sb = new StringBuilder();
            sb.append(addr).append(" | ").append(String.format("%04X", regs[i]));
            if (i + 1 < count) {
                int abcd = (regs[i] << 16) | regs[i + 1];
                int cdab = (regs[i + 1] << 16) | regs[i];
                sb.append(" ").append(String.format("%04X", regs[i + 1]))
                  .append(" | ").append(Float.intBitsToFloat(abcd))
                  .append(" | ").append(Float.intBitsToFloat(cdab))
                  .append(" | ").append(abcd)
                  .append(" | ").append(cdab);
            }
            rows.add(sb.toString());
        }
        return rows;
    }
}
