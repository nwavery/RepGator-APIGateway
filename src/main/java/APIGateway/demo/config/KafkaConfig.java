package APIGateway.demo.config;

import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.beans.factory.annotation.Value; // Keep only if needed by other beans (groupId is needed)
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import APIGateway.demo.kafka.ReplyListener; // Import ReplyListener
import org.springframework.kafka.support.KafkaHeaders; // Import KafkaHeaders
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.core.env.Environment;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// import java.util.HashMap;
// import java.util.Map;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class); // Added logger

    // Inject the ReplyListener bean containing the handler logic
    @Autowired
    private ReplyListener replyListener;

    @Value("${spring.kafka.consumer.group-id}") // Get group ID from properties
    private String consumerGroupId;

    @Value("${app.kafka.topics.news-get-politics-reply}") // Get topic name from properties
    private String newsReplyTopic;

    // @Autowired
    // private Environment env; // Commented out as env is no longer used

    // --- REMOVING Explicit Bean Definitions for auto-configured components ---
    /*
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;
    
    @Bean
    public Map<String, Object> producerConfigs() { ... }
    
    @Bean
    public ProducerFactory<String, Object> producerFactory() { ... }
    
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() { ... }

    @Bean
    public Map<String, Object> consumerConfigs() { ... }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() { ... }

    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, Object>> kafkaListenerContainerFactory() { ... }
    */

    // --- KEEPING Beans specific to ReplyingKafkaTemplate ---

    // Bean for ReplyingKafkaTemplate
    // @Bean
    // public ReplyingKafkaTemplate<String, Object, Object> replyingKafkaTemplate(
    //         // Inject the auto-configured ProducerFactory
    //         ProducerFactory<String, Object> pf,
    //         // Inject the repliesContainer bean defined below
    //         ConcurrentMessageListenerContainer<String, Object> repliesContainer) {
    //     // Set reply timeout from properties (example: 10 seconds)
    //     // You might want to make this configurable via application.yml
    //     // template.setDefaultReplyTimeout(Duration.ofSeconds(10));
    //     return new ReplyingKafkaTemplate<>(pf, repliesContainer);
    // }

    // Bean for the Replies Container
    // @Bean
    // public ConcurrentMessageListenerContainer<String, Object> repliesContainer(
    //         // Inject the auto-configured default container factory
    //         ConcurrentKafkaListenerContainerFactory<String, Object> containerFactory) {
    //     
    //     String replyTopicProperty = env.getProperty("kafka.reply.topic");
    //     if (replyTopicProperty == null || replyTopicProperty.isEmpty()) {
    //         throw new IllegalStateException("Required property 'kafka.reply.topic' is not set in the environment.");
    //     }

    //     ConcurrentMessageListenerContainer<String, Object> repliesContainer =
    //             containerFactory.createContainer(replyTopicProperty); // Use the property fetched from Environment
    //     repliesContainer.getContainerProperties().setGroupId(groupId); // Use the configured group ID
    //     repliesContainer.setAutoStartup(false); // Important: The ReplyingKafkaTemplate manages the lifecycle
    //     return repliesContainer;
    // }

    /**
     * Defines the Kafka Listener Container Factory used by @KafkaListener annotations.
     * Overrides the default auto-configured factory to set a specific error handler.
     *
     * @param consumerFactory The auto-configured Kafka ConsumerFactory.
     * @return A configured ConcurrentKafkaListenerContainerFactory.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) { // Inject auto-configured CF

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Set the CommonLoggingErrorHandler to prevent DLT publishing attempts.
        // Errors during listener processing will be logged instead.
        factory.setCommonErrorHandler(new CommonLoggingErrorHandler());

        // Add any other factory customizations here if needed in the future
        // (e.g., filtering, concurrency settings different from application.yml)

        return factory;
    }

    /**
     * Programmatically configure a listener container specifically for the News reply topic.
     * This bypasses the @KafkaListener annotation processing for this topic.
     */
    @Bean
    public ConcurrentMessageListenerContainer<String, Object> newsReplyListenerContainer(
            ConcurrentKafkaListenerContainerFactory<String, Object> factory) {

        // Create the container for the specific topic using the factory
        ConcurrentMessageListenerContainer<String, Object> container = factory.createContainer(newsReplyTopic);
        container.getContainerProperties().setGroupId(consumerGroupId);
        // Ensure AckMode is set correctly for this container too (might be inherited, but explicit is safer)
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        
        // Manually set the MessageListener - now accepting Acknowledgment
        container.setupMessageListener((AcknowledgingMessageListener<String, Object>) (record, acknowledgment) -> {
            // Extract correlation ID header (assuming it's byte[])
            byte[] correlationIdBytes = record.headers().lastHeader(KafkaHeaders.CORRELATION_ID) != null ?
                                        record.headers().lastHeader(KafkaHeaders.CORRELATION_ID).value() : null;
            
            if (correlationIdBytes == null) {
                log.error("Programmatic Listener: Received reply with no correlation ID on topic {}. Record: {}", record.topic(), record.value()); 
                // Not acknowledging here, as we cannot process without correlation ID.
                // The message might be redelivered if not acknowledged, or error handler might deal with it.
                return; 
            }
            String correlationId = new String(correlationIdBytes, StandardCharsets.UTF_8);
            log.debug("%%% Programmatic Listener received reply on topic {} for correlationId: {}", record.topic(), correlationId);
            
            // Call the public handler method in ReplyListener, passing acknowledgment
            replyListener.handlePublicReply(record, correlationIdBytes, acknowledgment);

        });
        
        // Auto-startup is true by default for containers created this way via factory
        // container.setAutoStartup(true);
        
        return container;
    }

    // Relying on Spring Boot auto-configuration for:
    // - ProducerFactory
    // - ConsumerFactory (injected above)
    // - KafkaTemplate
} 