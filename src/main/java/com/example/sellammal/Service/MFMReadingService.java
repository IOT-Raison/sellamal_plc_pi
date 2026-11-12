package com.example.sellammal.Service;

import com.example.sellammal.Config.MFMConfig;
import com.example.sellammal.DTO.CloudPayload;

public interface MFMReadingService {

    CloudPayload readMeter(MFMConfig config) throws Exception;
}

