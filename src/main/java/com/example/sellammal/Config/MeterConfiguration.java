
package com.example.sellammal.Config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MeterConfiguration {

    @Bean
    public List<MFMConfig> mfmConfigs() {

PLCConfig powerPLC =
        new PLCConfig("Power PLC", "192.168.1.10", 502);

        PLCConfig dryerPLC =
                new PLCConfig("Dryer PLC", "192.168.1.11", 502);

        PLCConfig hullerPLC =
                new PLCConfig("Huller PLC", "192.168.1.1", 502);

        return List.of(

                new MFMConfig(
                        dryerPLC, 1, 11, "Dryer 1,2,3,4",
                        40012, 40016, 40020,
                        40024, 40028, 40032,
                        40036, 40038, 40040,
                        40048, 40052, 40056,
                        40908, 40920, 40932,
                        0, 0, 0, 0, 0, 0
                ),

                new MFMConfig(
                        dryerPLC, 2,12,  "Dryer 4",
                        40112, 40116, 40120,
                        40124, 40128, 40132,
                        40136, 40138, 40140,
                        40148, 40152, 40156,
                        40944, 40956, 40968,
                        0, 0, 0, 0, 0, 0
                ),

                new MFMConfig(
                        dryerPLC, 3, 13, "Silo",
                        40212, 40216, 40220,
                        40224, 40228, 40232,
                        40236, 40238, 40240,
                        40248, 40252, 40256,
                        40980, 40992, 41004,
                        0, 0, 0, 0, 0, 0
                ),

                new MFMConfig(
                        dryerPLC, 4, 14, "Cutting",
                        40312, 40316, 40320,
                        40324, 40328, 40332,
                        40336, 40338, 40340,
                        40348, 40352, 40356,
                        41016, 41028, 41040,
                        0, 0, 0, 0, 0, 0
                ),

                new MFMConfig(
                        dryerPLC, 5, 15, "Boiler",
                        40412, 40416, 40420,
                        40424, 40428, 40432,
                        40436, 40438, 40440,
                        40448, 40452, 40456,
                        41052, 41064, 41076,
                        0, 0, 0, 0, 0, 0
                ),

                new MFMConfig(
                        dryerPLC, 6,16,  "Compressor 1",
                        40512, 40516, 40520,
                        40524, 40528, 40532,
                        40536, 40538, 40540,
                        40548, 40552, 40556,
                        41088, 41100, 41112,
                        0, 0, 0, 0, 0, 0
                ),

                new MFMConfig(
                        dryerPLC, 7, 17, "Compressor 2",
                        40612, 40616, 40620,
                        40624, 40628, 40632,
                        40636, 40638, 40640,
                        40648, 40652, 40656,
                        41200, 41240, 41280,
                        0, 0, 0, 0, 0, 0
                ),




// Power House EB - Slave ID 1
new MFMConfig(
        powerPLC, 1, 18, "Power House EB",
        // Addresses verified with /api/plc/raw on 12-Nov-2026 (base 40000, CDAB floats)
        40712, 40714, 40716,  // Voltage L1, L2, L3
        40718, 40720, 40722,  // Current L1, L2, L3
        40762, 40764, 40766,  // PF L1, L2, L3
        40750, 40752, 40754,  // kW L1, L2, L3 (in W, see meter.18.kw-scale)
        41300, 0, 0,          // kWh (not yet verified); kVAh, kVARh not provided
        40770,                // Total kW (in W)
        40726,                // Frequency
        0, 0,                 // Avg VLN (calculated), Avg VLL (not available)
        40724,                // Avg current
        40768                 // Avg PF
),

// Power House Solar - Slave ID 2
new MFMConfig(
        powerPLC, 2, 19, "Power House Solar",
        40012, 40016, 40020,  // Voltage L1, L2, L3
        40024, 40028, 40032,  // Current L1, L2, L3
        40036, 40038, 40040,  // PF L1, L2, L3
        40048, 40052, 40056,  // kW L1, L2, L3
        40908, 40920, 40932,  // kWh, kVAh, kVARh
        0, 0, 0, 0, 0, 0     // Huller-specific fields unused
),


                new MFMConfig(
                        hullerPLC, 1, 20,  "Huller",
                        0, 0, 0,
                        0, 0, 0,
                        0, 0, 0,
                        0, 0, 0,
                        41340, 0, 0,          // kWh (verified)
                        // kW, Freq, Avg VLN, Avg VLL, Avg I, Avg PF
                        // Excel had VLL=41356 / I=41360 swapped; verified on site 12-Nov-2026
                        41344, 41348, 41352, 41360, 41356, 41364
                )
        );
    }
}
