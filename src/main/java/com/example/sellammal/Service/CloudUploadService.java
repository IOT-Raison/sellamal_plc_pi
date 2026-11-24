package com.example.sellammal.Service;

import com.example.sellammal.DTO.CloudPayload;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class CloudUploadService {

    private static final Logger log =
            LoggerFactory.getLogger(CloudUploadService.class);

    private final BufferFileService bufferFileService;
    private final RestTemplate restTemplate;

    @Value("${cloud.api.url}")
    private String cloudUrl;

    public CloudUploadService(BufferFileService bufferFileService) {
        this.bufferFileService = bufferFileService;
        // Timeouts so a stalled cloud/network can never hang the uploader forever.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** Max records sent per run, so a big backlog drains in chunks. */
    /** Records sent per file rewrite, and batches per run (5 s). */
    private static final int BATCH = 50;
    private static final int MAX_BATCHES_PER_RUN = 40;

    /**
     * Every 5 s: send buffered records oldest-first until the buffer is empty
     * or an upload fails (then stop and retry next run, keeping order).
     * Sent records are removed from the buffer file once per batch.
     */
    @Scheduled(fixedDelay = 5000)
    public synchronized void uploadBufferedRecords() {
        for (int b = 0; b < MAX_BATCHES_PER_RUN; b++) {
            List<CloudPayload> batch;
            try {
                batch = bufferFileService.peekBatch(BATCH);
            } catch (Exception ex) {
                log.error("Could not read upload buffer: {}", ex.getMessage());
                return;
            }
            if (batch.isEmpty()) {
                return;
            }

            int done = 0;
            boolean failed = false;
            for (CloudPayload payload : batch) {
                Boolean ok = send(payload);
                if (ok == null) {        // network / 5xx: stop, retry later
                    failed = true;
                    break;
                }
                done++;                  // uploaded (true) or permanently rejected (false)
            }

            try {
                bufferFileService.removeFirst(done);
            } catch (Exception ex) {
                log.error("Could not remove {} sent record(s) from buffer: {}", done, ex.getMessage());
                return;
            }
            if (b == 0 && done == BATCH) {
                try {
                    log.info("Upload backlog: {} record(s) still buffered", bufferFileService.size());
                } catch (Exception ignored) {
                }
            }
            if (failed || done < batch.size()) {
                return;
            }
        }
    }

    /** @return true uploaded, false permanently rejected (drop it), null = retry later. */
    private Boolean send(CloudPayload payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response =
                    restTemplate.postForEntity(cloudUrl, new HttpEntity<>(payload, headers), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Uploaded deviceId={}", payload.getDeviceId());
                return true;
            }
            log.error("Upload rejected with HTTP {}; record retained", response.getStatusCode());
            return null;
        } catch (HttpClientErrorException ex) {
            // 4xx = the cloud will never accept this record (e.g. unknown deviceId): drop it
            log.error("Cloud rejected record permanently (HTTP {}), dropping it: {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            return false;
        } catch (Exception ex) {
            log.error("Upload failed; buffered record retained: {}", ex.getMessage());
            return null;
        }
    }
}

