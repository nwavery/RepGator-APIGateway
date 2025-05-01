package APIGateway.demo.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Component containing Kafka listeners for each individual reply topic,
 * as mandated by the architecture. Received replies are forwarded to the
 * ReplyCorrelationService to complete the corresponding pending request.
 */
@Component
public class ReplyListener {

    private static final Logger log = LoggerFactory.getLogger(ReplyListener.class);

    private final ReplyCorrelationService replyCorrelationService;

    // Using constructor injection
    @Autowired
    public ReplyListener(ReplyCorrelationService replyCorrelationService) {
        this.replyCorrelationService = replyCorrelationService;
    }

    // Define CONSUMER_GROUP_ID - Should likely match application.yml or be configurable
    private static final String CONSUMER_GROUP_ID = "api-gateway-group"; // TODO: Make configurable?

    // --- Listeners for each Reply Topic ---

    
    @KafkaListener(topics = "res.user-service.api-gateway.login", groupId = CONSUMER_GROUP_ID)
    public void listenLoginReply(ConsumerRecord<String, Object> record,
                                 @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.user-service.api-gateway.register", groupId = CONSUMER_GROUP_ID)
    public void listenRegisterReply(ConsumerRecord<String, Object> record,
                                    @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.user-service.api-gateway.get-profile", groupId = CONSUMER_GROUP_ID)
    public void listenGetUserProfileReply(ConsumerRecord<String, Object> record,
                                          @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.user-service.api-gateway.update-profile", groupId = CONSUMER_GROUP_ID)
    public void listenUpdateUserProfileReply(ConsumerRecord<String, Object> record,
                                             @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.user-service.api-gateway.get-dashboard-summary", groupId = CONSUMER_GROUP_ID)
    public void listenGetDashboardSummaryReply(ConsumerRecord<String, Object> record,
                                               @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.message-service.api-gateway.get-user-conversations", groupId = CONSUMER_GROUP_ID)
    public void listenGetUserConversationsReply(ConsumerRecord<String, Object> record,
                                                @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.message-service.api-gateway.get-conversation-messages", groupId = CONSUMER_GROUP_ID)
    public void listenGetConversationMessagesReply(ConsumerRecord<String, Object> record,
                                                   @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.message-service.api-gateway.post-direct-message", groupId = CONSUMER_GROUP_ID)
    public void listenPostDirectMessageReply(ConsumerRecord<String, Object> record,
                                             @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.message-service.api-gateway.get-district-messages", groupId = CONSUMER_GROUP_ID)
    public void listenGetDistrictMessagesReply(ConsumerRecord<String, Object> record,
                                               @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "res.message-service.api-gateway.post-district-message", groupId = CONSUMER_GROUP_ID)
    public void listenPostDistrictMessageReply(ConsumerRecord<String, Object> record,
                                               @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    // --- Listeners for Representative Service Replies ---

    @KafkaListener(topics = "${app.kafka.topics.rep-get-by-district-reply}", groupId = CONSUMER_GROUP_ID)
    public void listenGetRepsByDistrictReply(ConsumerRecord<String, Object> record,
                                             @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    @KafkaListener(topics = "${app.kafka.topics.rep-get-by-id-reply}", groupId = CONSUMER_GROUP_ID)
    public void listenGetRepByIdReply(ConsumerRecord<String, Object> record,
                                      @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        handleReply(record, correlationIdBytes);
    }

    // --- Listener for News Service Replies ---

    // @KafkaListener(topics = "${app.kafka.topics.news-get-politics-reply}", groupId = CONSUMER_GROUP_ID) // Using hardcoded topic below
    @KafkaListener(topics = "res.district-service.api-gateway.get-politics-news", groupId = CONSUMER_GROUP_ID)
    // Restoring original signature that works for other listeners
    public void listenGetPoliticsNewsReply(ConsumerRecord<String, Object> record,
                                         @Header(KafkaHeaders.CORRELATION_ID) byte[] correlationIdBytes) {
        // log.warn("%%% MINIMAL LISTENER INVOKED ... "); // Removed diagnostic log
        // Call original handler
        handleReply(record, correlationIdBytes);
    }

    // Centralized handling logic (original - accepts byte[])
    private void handleReply(ConsumerRecord<String, Object> record, byte[] correlationIdBytes) {
        if (correlationIdBytes == null) {
            log.error("Received reply message with no correlation ID header (byte[]) on topic {}: {}", record.topic(), record.value());
            return;
        }
        String correlationId = new String(correlationIdBytes, StandardCharsets.UTF_8);
        log.debug("Received reply (byte[] handler) on topic {} for correlationId: {}", record.topic(), correlationId);
        replyCorrelationService.completeRequest(correlationId, record);
    }

    // Public wrapper method to be called by programmatic listener configuration
    public void handlePublicReply(ConsumerRecord<String, Object> record, byte[] correlationIdBytes) {
        // Simply call the existing private handler logic
        handleReply(record, correlationIdBytes);
    }
    
    // Overload or new method to handle String correlation ID (No longer needed/used)
    /*
    private void handleReplyFromStringHeader(ConsumerRecord<String, Object> record, String correlationId) {
        // ... implementation removed ...
    }
    */
    
} 