package APIGateway.demo.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service responsible for correlating Kafka replies back to their original requests
 * using a correlation ID. This replaces the functionality of ReplyingKafkaTemplate
 * when individual reply topics are used per the architectural requirement.
 */
@Service
public class ReplyCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(ReplyCorrelationService.class);

    // Stores pending requests: CorrelationID -> Future that will hold the reply record
    private final ConcurrentMap<String, CompletableFuture<ConsumerRecord<String, Object>>> pendingRequests =
            new ConcurrentHashMap<>();

    /**
     * Registers a new pending request and returns a Future that will eventually
     * contain the reply.
     *
     * @param correlationId The unique ID for the request.
     * @param timeoutMillis The maximum time to wait for a reply.
     * @return A CompletableFuture that will be completed when the reply arrives or times out.
     */
    public CompletableFuture<ConsumerRecord<String, Object>> registerPendingRequest(String correlationId, long timeoutMillis) {
        CompletableFuture<ConsumerRecord<String, Object>> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);
        log.debug("Registered pending request with correlationId: {}", correlationId);

        // Schedule a task to remove the future and complete it exceptionally if it times out
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
            CompletableFuture<ConsumerRecord<String, Object>> removedFuture = pendingRequests.remove(correlationId);
            if (removedFuture != null && !removedFuture.isDone()) {
                 log.warn("Request timed out for correlationId: {}", correlationId);
                removedFuture.completeExceptionally(new TimeoutException("Kafka reply timed out for correlationId: " + correlationId));
            }
        });

        return future;
    }

    /**
     * Completes a pending request when its reply is received by a listener.
     *
     * @param correlationId The correlation ID extracted from the reply message headers.
     * @param replyRecord   The received Kafka ConsumerRecord containing the reply.
     */
    public void completeRequest(String correlationId, ConsumerRecord<String, Object> replyRecord) {
        log.debug("[RCS] Attempting to complete request for correlationId: {}. Reply record received: {}", correlationId, replyRecord != null);
        CompletableFuture<ConsumerRecord<String, Object>> future = pendingRequests.remove(correlationId);
        if (future != null) {
            log.debug("Completing request for correlationId: {}", correlationId);
            future.complete(replyRecord);
        } else {
            // This can happen if the request timed out before the reply arrived,
            // or if the correlation ID is invalid.
            log.warn("Received reply for unknown or timed-out correlationId: {}", correlationId);
            // Depending on requirements, might need dead-letter queue logic here.
        }
    }
} 