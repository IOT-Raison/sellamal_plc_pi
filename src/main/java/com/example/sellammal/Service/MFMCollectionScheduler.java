package com.example.sellammal.Service;

import com.example.sellammal.Config.MFMConfig;
import com.example.sellammal.DTO.CloudPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class MFMCollectionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(MFMCollectionScheduler.class);

    private final List<MFMConfig> mfmConfigs;
    private final BufferFileService bufferFileService;
    private final MFMReadingService mfmReadingService;

    /** One thread per PLC so all 3 PLCs are polled at the same time. */
    private final ExecutorService plcExecutor;

    public MFMCollectionScheduler(
            List<MFMConfig> mfmConfigs,
            BufferFileService bufferFileService,
            MFMReadingService mfmReadingService) {

        this.mfmConfigs = mfmConfigs;
        this.bufferFileService = bufferFileService;
        this.mfmReadingService = mfmReadingService;

        long plcCount = mfmConfigs.stream()
                .map(c -> c.getPlc().getIpAddress()).distinct().count();
        this.plcExecutor = Executors.newFixedThreadPool((int) Math.max(1, plcCount));
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        plcExecutor.shutdownNow();
    }

    @Scheduled(fixedRate = 60000)
    public void collectEveryMinute() {

        Map<String, List<MFMConfig>> plcGroups =
                mfmConfigs.stream().collect(
                        Collectors.groupingBy(
                                config -> config.getPlc().getIpAddress()));

        List<CompletableFuture<Void>> tasks =
                plcGroups.values().stream()
                        .map(meters -> CompletableFuture.runAsync(
                                () -> collectPLCGroup(meters), plcExecutor))
                        .toList();

        // Wait for all PLC groups to finish this polling cycle.
        CompletableFuture.allOf(
                tasks.toArray(new CompletableFuture[0])
        ).join();
    }

    private void collectPLCGroup(List<MFMConfig> meters) {

        for (MFMConfig config : meters) {
            try {
                CloudPayload payload =
                        mfmReadingService.readMeter(config);

                if (payload != null) {
                    bufferFileService.append(payload);

                    log.info(
                            "Buffered reading for deviceId={}, meter={}",
                            payload.getDeviceId(),
                            config.getMeterName());
                }

            } catch (Exception ex) {
                log.error(
                        "Failed to read meter {} from PLC {}: {}",
                        config.getMeterName(),
                        config.getPlc().getIpAddress(),
                        ex.getMessage());
            }
        }
    }

    
}
