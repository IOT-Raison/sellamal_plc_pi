package com.example.sellammal.Modbus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.sellammal.Config.PLCConfig;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.Register;

import jakarta.annotation.PreDestroy;

/**
 * Keeps one persistent j2mod Modbus TCP connection per PLC.
 * A broken connection is dropped and re-opened on the next read.
 * Calls for the same PLC are serialised, different PLCs run in parallel.
 */
@Component
public class PlcConnectionManager {

    private static final Logger log =
            LoggerFactory.getLogger(PlcConnectionManager.class);

    @Value("${modbus.timeout-ms:3000}")
    private int timeoutMs;

    private final Map<String, ModbusTCPMaster> masters = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private static String key(PLCConfig plc) {
        int port = plc.getPort() > 0 ? plc.getPort() : 502;
        return plc.getIpAddress() + ":" + port;
    }

    /** Lock to hold while reading several registers of one meter from this PLC. */
    public Object lockFor(PLCConfig plc) {
        return locks.computeIfAbsent(key(plc), k -> new Object());
    }

    /**
     * Read holding registers (FC03).
     * @param offset 0-based register offset (40001 -> 0)
     */
    public int[] readHoldingRegisters(PLCConfig plc, int unitId, int offset, int count)
            throws Exception {

        synchronized (lockFor(plc)) {
            ModbusTCPMaster master = connect(plc);
            try {
                Register[] regs = master.readMultipleRegisters(unitId, offset, count);
                int[] values = new int[regs.length];
                for (int i = 0; i < regs.length; i++) {
                    values[i] = regs[i].getValue() & 0xFFFF;
                }
                return values;
            } catch (com.ghgande.j2mod.modbus.ModbusSlaveException ex) {
                // PLC answered with an exception (e.g. illegal address): connection is fine.
                throw ex;
            } catch (Exception ex) {
                // Timeout / socket error: drop the connection, reconnect next time.
                drop(plc);
                throw ex;
            }
        }
    }

    private ModbusTCPMaster connect(PLCConfig plc) throws Exception {
        String k = key(plc);
        ModbusTCPMaster master = masters.get(k);
        if (master != null) {
            return master;
        }
        int port = plc.getPort() > 0 ? plc.getPort() : 502;
        master = new ModbusTCPMaster(plc.getIpAddress(), port, timeoutMs, false);
        master.connect();
        masters.put(k, master);
        log.info("Connected to {} ({})", plc.getPlcName(), k);
        return master;
    }

    private void drop(PLCConfig plc) {
        ModbusTCPMaster master = masters.remove(key(plc));
        if (master != null) {
            try {
                master.disconnect();
            } catch (Exception ignored) {
                // already broken
            }
            log.warn("Connection to {} ({}) dropped, will reconnect", plc.getPlcName(), key(plc));
        }
    }

    @PreDestroy
    public void closeAll() {
        masters.values().forEach(m -> {
            try {
                m.disconnect();
            } catch (Exception ignored) {
            }
        });
        masters.clear();
    }
}
