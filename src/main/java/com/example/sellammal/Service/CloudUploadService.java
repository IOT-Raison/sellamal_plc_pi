package com.example.sellammal.Service;

import com.example.sellammal.DTO.CloudPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
        this.restTemplate = new RestTemplate();
    }

    /** Max records sent per run, so a big backlog drains in chunks. */
    private static final int MAX_PER_RUN = 500;

    /**
     * Every 5 s: send buffered records oldest-first until the buffer is empty
     * or an upload fails (then stop and retry next run, keeping order).
     */
    @Scheduled(fixedDelay = 5000)
    public synchronized void uploadBufferedRecords() {
        for (int i = 0; i < MAX_PER_RUN; i++) {
            if (!uploadNextRecord()) {
                return;
            }
        }
    }

    /** @return true if a record was uploaded, false if buffer empty or upload failed. */
    private boolean uploadNextRecord() {
        try {
            CloudPayload payload = bufferFileService.peek();

            if (payload == null) {
                return false;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<CloudPayload> request =
                    new HttpEntity<>(payload, headers);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            cloudUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                bufferFileService.removeFirst();

                log.info("Uploaded deviceId={}",
                        payload.getDeviceId());
                return true;
            }

            log.error("Upload rejected with HTTP {}; record retained",
                    response.getStatusCode());
            return false;

        } catch (Exception ex) {
            // Keep the record in the buffer for retry.
            log.error("Upload failed; buffered record retained: {}",
                    ex.getMessage());
            return false;
        }
    }
}

