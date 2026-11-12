package com.example.sellammal.DTO;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonPropertyOrder({
        "kwh", "export_kwh", "total_kw", "max_kw", "sys_freq",
        "v_l1_n", "v_l2_n", "v_l3_n", "avg_vln",
        "v_l1_l2", "v_l2_l3", "v_l3_l1", "avg_vll",
        "i_l1", "i_l2", "i_l3", "avg_i",
        "pf_l1", "pf_l2", "pf_l3", "avg_pf"
})
public class MeterData {

    private String kwh = "0.000";
    private String export_kwh = "0.000";
    private String total_kw = "0.000";
    private String max_kw = "0.000";
    private String sys_freq = "0.00";

    private String v_l1_n = "0.00";
    private String v_l2_n = "0.00";
    private String v_l3_n = "0.00";
    private String avg_vln = "0.00";

    private String v_l1_l2 = "0.00";
    private String v_l2_l3 = "0.00";
    private String v_l3_l1 = "0.00";
    private String avg_vll = "0.00";

    private String i_l1 = "0.00";
    private String i_l2 = "0.00";
    private String i_l3 = "0.00";
    private String avg_i = "0.00";

    private String pf_l1 = "0.000";
    private String pf_l2 = "0.000";
    private String pf_l3 = "0.000";
    private String avg_pf = "0.000";
}
