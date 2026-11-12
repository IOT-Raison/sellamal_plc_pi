
package com.example.sellammal.Config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MFMConfig {

    private PLCConfig plc;
    private int slaveId;
    private int deviceId;
    private String meterName;

    // Voltage
    private int voltageL1;
    private int voltageL2;
    private int voltageL3;

    // Current
    private int currentL1;
    private int currentL2;
    private int currentL3;

    // Power Factor
    private int pfL1;
    private int pfL2;
    private int pfL3;

    // Phase-wise kW
    private int kwL1;
    private int kwL2;
    private int kwL3;

    // Energy
    private int totalKwh;
    private int totalKvah;
    private int totalKvarh;

    // Huller-specific registers
    private int totalKw;
    private int frequency;
    private int averageVln;
    private int averageVll;
    private int averageCurrent;
    private int averagePf;
}
