package o.e;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

interface RateLimiter {
    void acquire();
}

class TockenBucketRateLimiter implements RateLimiter {
    private final int maxStoredTokens;
    private final ScheduledExecutorService refillingService;
    private int storedTokens;

    public TockenBucketRateLimiter(TimeUnit tokenRefillPeriod, int tokensRefillCount) {
        this.maxStoredTokens = tokensRefillCount;

        this.refillingService = Executors.newScheduledThreadPool(1);

        refillingService.scheduleAtFixedRate(() -> refill(maxStoredTokens), 0, 1, tokenRefillPeriod);
    }

    @Override
    public void acquire() {
        try {
            waitAndAcquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void waitAndAcquire() throws InterruptedException {
        synchronized (this) {
            while (storedTokens == 0) {
                wait();
            }
            storedTokens--;
            if (storedTokens > 0) {
                notify();
            }
        }
    }

    private void refill(int refillCount) {
        synchronized (this) {
            storedTokens = Math.min(maxStoredTokens, storedTokens + refillCount);
            notify();
        }
    }


}

public class CrptApi {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String API_HOST = "https://ismp.crpt.ru";
    private static final URI createDocumentEndpoint;

    private final TockenBucketRateLimiter limiter;
    private final HttpClient client = HttpClient.newBuilder().build();

    static {
       createDocumentEndpoint = URI.create(API_HOST + "/api/v3/lk/documents/create");
    }

    @Jacksonized
    @Builder
    @RequiredArgsConstructor
    public static class Document {
        private final Description description;
        private final String doc_id;
        private final String doc_status;
        private final String doc_type;
        private final boolean importRequest;
        private final String owner_inn;
        private final String participant_inn;
        private final String producer_inn;
        private final Date production_date;
        private final String production_type;
        private final ArrayList<Product> products;
        private final Date reg_date;
        private final String reg_number;


    }
    @Jacksonized
    @RequiredArgsConstructor
    @Builder
    static class Description {
        private final String participantInn;

    }
    @Jacksonized
    @RequiredArgsConstructor
    @Builder
    static class Product {
        private final String certificate_document;
        private final Date certificate_document_date;
        private final String certificate_document_number;
        private final String owner_inn;
        private final String producer_inn;
        private final Date production_date;
        private final String tnved_code;
        private final String uit_code;
        private final String uitu_code;
    }

    public CrptApi(TimeUnit timeUnit, int replenishmentCount) {
        this.limiter = new TockenBucketRateLimiter(timeUnit, replenishmentCount);
    }

    public void createDocument(Document document, String sign) {
        limiter.acquire();
        var body = HttpRequest.BodyPublishers.ofString(prepareContentString(document, sign));
        try {
            var request = HttpRequest.newBuilder()
                    .header("Authorization", String.format("Bearer %s", sign))
                    .POST(body)
                    .uri(createDocumentEndpoint)
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private String prepareContentString(Document document, String sign) {
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
