package APIGateway.demo.config;

import org.apache.kafka.clients.admin.NewTopic;
// import org.springframework.beans.factory.annotation.Value; // No longer injecting shared reply topic
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    private static final int DEFAULT_PARTITIONS = 3; // Use 3 partitions as a default

    // --- REMOVING Injected shared reply topic ---
    // @Value("${kafka.reply.topic}")
    // private String apiGatewayReplyTopic;

    // --- Request Topics Produced by API Gateway (Verified Existing) ---
    @Bean public NewTopic loginRequestTopic() { return buildTopic("req.api-gateway.user-service.login"); }
    @Bean public NewTopic registerRequestTopic() { return buildTopic("req.api-gateway.user-service.register"); }
    @Bean public NewTopic getUserProfileRequestTopic() { return buildTopic("req.api-gateway.user-service.get-profile"); }
    @Bean public NewTopic updateUserProfileRequestTopic() { return buildTopic("req.api-gateway.user-service.update-profile"); }
    @Bean public NewTopic getDashboardSummaryRequestTopic() { return buildTopic("req.api-gateway.user-service.get-dashboard-summary"); }
    @Bean public NewTopic getUserConversationsRequestTopic() { return buildTopic("req.api-gateway.message-service.get-user-conversations"); }
    @Bean public NewTopic getConversationMessagesRequestTopic() { return buildTopic("req.api-gateway.message-service.get-conversation-messages"); }
    @Bean public NewTopic postDirectMessageRequestTopic() { return buildTopic("req.api-gateway.message-service.post-direct-message"); }
    @Bean public NewTopic getDistrictMessagesRequestTopic() { return buildTopic("req.api-gateway.message-service.get-district-messages"); }
    @Bean public NewTopic postDistrictMessageRequestTopic() { return buildTopic("req.api-gateway.message-service.post-district-message"); }

    // --- NEW Representative Service Request Topics ---
    @Bean public NewTopic repGetByDistrictRequestTopic() { return buildTopic("req.api-gateway.representative-service.get-by-district"); }
    @Bean public NewTopic repGetByIdRequestTopic() { return buildTopic("req.api-gateway.representative-service.get-by-id"); }

    // --- Individual Reply Topics Consumed by API Gateway (Mandated by Architect) ---
    @Bean public NewTopic loginReplyTopic() { return buildTopic("res.user-service.api-gateway.login"); }
    @Bean public NewTopic registerReplyTopic() { return buildTopic("res.user-service.api-gateway.register"); }
    @Bean public NewTopic getUserProfileReplyTopic() { return buildTopic("res.user-service.api-gateway.get-profile"); }
    @Bean public NewTopic updateUserProfileReplyTopic() { return buildTopic("res.user-service.api-gateway.update-profile"); }
    @Bean public NewTopic getDashboardSummaryReplyTopic() { return buildTopic("res.user-service.api-gateway.get-dashboard-summary"); }
    @Bean public NewTopic getUserConversationsReplyTopic() { return buildTopic("res.message-service.api-gateway.get-user-conversations"); }
    @Bean public NewTopic getConversationMessagesReplyTopic() { return buildTopic("res.message-service.api-gateway.get-conversation-messages"); }
    @Bean public NewTopic postDirectMessageReplyTopic() { return buildTopic("res.message-service.api-gateway.post-direct-message"); }
    @Bean public NewTopic getDistrictMessagesReplyTopic() { return buildTopic("res.message-service.api-gateway.get-district-messages"); }
    @Bean public NewTopic postDistrictMessageReplyTopic() { return buildTopic("res.message-service.api-gateway.post-district-message"); }
    
    // --- NEW Representative Service Reply Topics (Consumed by API GW) ---
    // Note: Corresponding request topics produced by API GW are defined separately
    @Bean public NewTopic repGetByDistrictReplyTopic() { return buildTopic("res.representative-service.api-gateway.get-by-district"); }
    @Bean public NewTopic repGetByIdReplyTopic() { return buildTopic("res.representative-service.api-gateway.get-by-id"); }
    
    // --- NEW News Service Reply Topic (Consumed by API GW) ---
    @Bean public NewTopic newsGetPoliticsReplyTopic() { return buildTopic("res.district-service.api-gateway.get-politics-news"); }
    
    // --- REMOVING Shared Reply Topic Bean ---
    /*
    @Bean
    public NewTopic apiGatewayRepliesTopicBean() {
        return TopicBuilder.name(apiGatewayReplyTopic) // Use injected property value
                .partitions(DEFAULT_PARTITIONS)
                // Consider adding retention policies or other configs if needed
                .build();
    }
    */

    // Helper method to build topics
    private NewTopic buildTopic(String topicName) {
        return TopicBuilder.name(topicName)
                .partitions(DEFAULT_PARTITIONS)
                .build();
    }
} 