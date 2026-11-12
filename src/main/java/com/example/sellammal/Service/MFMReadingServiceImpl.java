package com.example.sellammal.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.example.sellammal.Config.MFMConfig;
import com.example.sellammal.DTO.CloudPayload;
import com.example.sellammal.DTO.MeterData;
import com.example.sellammal.Modbus.PlcConnectionManager;

/**
 * Reads MFM values from the PLCs over Modbus TCP (holding registers, FC03) using j2mod.
 * Every value is assumed to be a 32-bit value spread over 2 registers.
 */
@Service
public class MFMReadingServiceImpl implements MFMReadingService {

    private static final Logger log =
            LoggerFactory.getLogger(MFMReadingServiceImpl.class);

    /** 40001 -> offset 0. Set to 40000 if every value comes out shifted by one register. */
    @Value("${modbus.address-base:40001}")
    private int addressBase;

    /** ABCD = high word first (big endian), CDAB = low word first (word swapped). */
    @Value("${modbus.word-order:ABCD}")
    private String wordOrder;

    /** FLOAT32, INT32 or UINT32 */
    @Value("${modbus.data-type:FLOAT32}")
    private String dataType;

    @Value("${modbus.unit-id:1}")
    private int defaultUnitId;

    /** true = send MFMConfig.slaveId as the Modbus unit id (only for gateways that route by id). */
    @Value("${modbus.use-slave-id-as-unit-id:false}")
    private boolean useSlaveIdAsUnitId;

    private final PlcConnectionManager plcConnections;
    private final Environment env;

    public MFMReadingServiceImpl(PlcConnectionManager plcConnections, Environment env) {
        this.plcConnections = plcConnections;
        this.env = env;
    }

    @Override
    public CloudPayload readMeter(MFMConfig config) throws Exception {

        String ip = config.getPlc().getIpAddress();
        int unitId = useSlaveIdAsUnitId ? config.getSlaveId() : defaultUnitId;

        synchronized (plcConnections.lockFor(config.getPlc())) {

            Reader r = new Reader(config, unitId);
            MeterData d = new MeterData();

            Double v1 = r.read(config.getVoltageL1());
            Double v2 = r.read(config.getVoltageL2());
            Double v3 = r.read(config.getVoltageL3());
            Double i1 = r.read(config.getCurrentL1());
            Double i2 = r.read(config.getCurrentL2());
            Double i3 = r.read(config.getCurrentL3());
            Double pf1 = r.read(config.getPfL1());
            Double pf2 = r.read(config.getPfL2());
            Double pf3 = r.read(config.getPfL3());
            // Per-meter scaling, e.g. meter.18.kw-scale=0.001 when the PLC holds W instead of kW
            double kwScale = env.getProperty("meter." + config.getDeviceId() + ".kw-scale", Double.class, 1.0);
            double kwhScale = env.getProperty("meter." + config.getDeviceId() + ".kwh-scale", Double.class, 1.0);

            Double kw1 = scale(r.read(config.getKwL1()), kwScale);
            Double kw2 = scale(r.read(config.getKwL2()), kwScale);
            Double kw3 = scale(r.read(config.getKwL3()), kwScale);
            Double kwh = scale(r.read(config.getTotalKwh()), kwhScale);

            // Huller-style registers (direct totals / averages)
            Double totalKw = scale(r.read(config.getTotalKw()), kwScale);
            Double freq = r.read(config.getFrequency());
            Double avgVln = r.read(config.getAverageVln());
            Double avgVll = r.read(config.getAverageVll());
            Double avgI = r.read(config.getAverageCurrent());
            Double avgPf = r.read(config.getAveragePf());

            if (r.successCount == 0) {
                throw new IOException("No register could be read from " + config.getMeterName()
                        + " (" + ip + "): " + r.firstError);
            }

            set(v1, 2, d::setV_l1_n);
            set(v2, 2, d::setV_l2_n);
            set(v3, 2, d::setV_l3_n);
            set(i1, 2, d::setI_l1);
            set(i2, 2, d::setI_l2);
            set(i3, 2, d::setI_l3);
            set(pf1, 3, d::setPf_l1);
            set(pf2, 3, d::setPf_l2);
            set(pf3, 3, d::setPf_l3);
            set(kwh, 3, d::setKwh);
            set(freq, 2, d::setSys_freq);
            set(avgVll, 2, d::setAvg_vll);

            Double kw = totalKw != null ? totalKw : sum(kw1, kw2, kw3);
            set(kw, 3, d::setTotal_kw);
            set(kw, 3, d::setMax_kw);
            set(avgVln != null ? avgVln : avg(v1, v2, v3), 2, d::setAvg_vln);
            set(avgI != null ? avgI : avg(i1, i2, i3), 2, d::setAvg_i);
            set(avgPf != null ? avgPf : avg(pf1, pf2, pf3), 3, d::setAvg_pf);

            if (r.failCount > 0) {
                log.warn("{}: {} register(s) failed, first error: {}",
                        config.getMeterName(), r.failCount, r.firstError);
            }

            return new CloudPayload(config.getDeviceId(), d);
        }
    }

    /** Reads one 32-bit value; returns null when address is 0 (not configured) or read fails. */
    private class Reader {
        final MFMConfig config;
        final int unitId;
        int successCount = 0;
        int failCount = 0;
        String firstError;

        Reader(MFMConfig config, int unitId) {
            this.config = config;
            this.unitId = unitId;
        }

        Double read(int address) throws IOException {
            if (address <= 0) {
                return null;
            }
            int offset = address - addressBase;
            try {
                int[] regs = plcConnections.readHoldingRegisters(
                        config.getPlc(), unitId, offset, 2);
                double value = decode(regs[0], regs[1]);
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    throw new IOException("NaN/Inf at " + address
                            + " - check modbus.word-order / data-type");
                }
                successCount++;
                log.debug("{} {} -> {}", config.getMeterName(), address, value);
                return value;
            } catch (com.ghgande.j2mod.modbus.ModbusSlaveException | IOException ex) {
                // PLC rejected this register (bad address) or bad value: skip it, keep going.
                failCount++;
                if (firstError == null) {
                    firstError = address + ": " + ex.getMessage();
                }
                return null;
            } catch (Exception ex) {
                // Timeout / connection lost: stop reading this meter for this cycle.
                throw new IOException("PLC " + config.getPlc().getIpAddress()
                        + " not reachable (register " + address + "): " + ex.getMessage(), ex);
            }
        }
    }

    double decode(int r0, int r1) {
        int hi = "CDAB".equalsIgnoreCase(wordOrder) ? r1 : r0;
        int lo = "CDAB".equalsIgnoreCase(wordOrder) ? r0 : r1;
        int raw = (hi << 16) | (lo & 0xFFFF);
        switch (dataType.toUpperCase(Locale.ROOT)) {
            case "INT32":  return raw;
            case "UINT32": return raw & 0xFFFFFFFFL;
            default:       return Float.intBitsToFloat(raw);
        }
    }

    private static Double scale(Double v, double factor) {
        return v == null ? null : v * factor;
    }

    private static Double sum(Double... vals) {
        List<Double> ok = present(vals);
        return ok.isEmpty() ? null : ok.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static Double avg(Double... vals) {
        List<Double> ok = present(vals);
        return ok.isEmpty() ? null : ok.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static List<Double> present(Double... vals) {
        List<Double> ok = new ArrayList<>();
        for (Double v : vals) {
            if (v != null) ok.add(v);
        }
        return ok;
    }

    private static void set(Double value, int decimals, java.util.function.Consumer<String> setter) {
        if (value != null) {
            setter.accept(String.format(Locale.ROOT, "%." + decimals + "f", value));
        }
    }
}
