package com.example.sellammal.Config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PLCConfig {

    private String plcName;
    private String ipAddress;
    private int port;
}
