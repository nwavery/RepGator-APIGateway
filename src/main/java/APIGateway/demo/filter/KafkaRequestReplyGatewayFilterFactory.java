package APIGateway.demo.filter;

import APIGateway.demo.dto.LoginRequest;
import APIGateway.demo.dto.RegisterRequest;
import APIGateway.demo.kafka.ReplyCorrelationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaRequestReplyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<KafkaRequestReplyGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(KafkaRequestReplyGatewayFilterFactory.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ReplyCorrelationService replyCorrelationService;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.request-timeout-ms:10000}")
    private long defaultRequestTimeoutMs;

    @Autowired
    public KafkaRequestReplyGatewayFilterFactory(KafkaTemplate<String, Object> kafkaTemplate,
                                               ReplyCorrelationService replyCorrelationService,
                                               ObjectMapper objectMapper) {
        super(Config.class);
        this.kafkaTemplate = kafkaTemplate;
        this.replyCorrelationService = replyCorrelationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("name", "requestTopic", "replyTopic");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            long filterStartTime = System.currentTimeMillis();
            String correlationId = UUID.randomUUID().toString();
            byte[] correlationIdBytes = correlationId.getBytes(StandardCharsets.UTF_8);

            ServerHttpRequest request = exchange.getRequest();
            String routeName = config.getName();
            String requestTopic = config.getRequestTopic();
            String specificReplyTopic = config.getReplyTopic();
            HttpMethod method = request.getMethod();

            if (specificReplyTopic == null || specificReplyTopic.isBlank()) {
                 log.error("!!! Configuration Error for route '{}': The 'replyTopic' must be specified in the filter configuration.", routeName);
                 return handleProcessingError(exchange,
                         new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API Gateway configuration error: Missing reply topic."),
                         correlationId, routeName);
            }

            log.info(">>> [CorrId: {}] Manual Kafka Filter Start. Route: {}, Method: {}, Request Topic: {}, Reply Topic: {}",
                     correlationId, routeName, method, requestTopic, specificReplyTopic);

            String userId = request.getHeaders().getFirst("X-User-Id");

            Map<String, String> pathVariables = exchange.getAttributeOrDefault(
                ServerWebExchangeUtils.URI_TEMPLATE_VARIABLES_ATTRIBUTE, new HashMap<>());
            MultiValueMap<String, String> queryParams = request.getQueryParams();

            Mono<Object> kafkaPayloadMono = prepareKafkaPayload(exchange, routeName, method, userId, pathVariables, queryParams);

            return kafkaPayloadMono.flatMap((Object kafkaPayload) -> {

                log.debug("[CorrId: {}] Prepared Kafka payload for route {}: {}", correlationId, routeName, kafkaPayload);

                String kafkaKey = determineKafkaKey(routeName, userId, correlationId);
                if (kafkaKey == null && isUserKeyRequired(routeName)) {
                    return handleProcessingError(exchange,
                             new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User identifier (X-User-Id header) is missing or invalid."),
                             correlationId, routeName);
                }

                ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(requestTopic, kafkaKey, kafkaPayload);
                producerRecord.headers().add(new RecordHeader(KafkaHeaders.CORRELATION_ID, correlationIdBytes));
                producerRecord.headers().add(new RecordHeader(KafkaHeaders.REPLY_TOPIC, specificReplyTopic.getBytes(StandardCharsets.UTF_8)));
                producerRecord.headers().add(new RecordHeader("sourceService", "api-gateway".getBytes(StandardCharsets.UTF_8)));
                
                log.debug("[CorrId: {}] Sending Kafka message. Topic: {}, Key: {}, Headers: [CID={}, RT={}, Source=api-gateway]", 
                        correlationId, requestTopic, kafkaKey, correlationId, specificReplyTopic);

                CompletableFuture<ConsumerRecord<String, Object>> replyFuture = 
                    replyCorrelationService.registerPendingRequest(correlationId, defaultRequestTimeoutMs);
                
                kafkaTemplate.send(producerRecord).whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("!!! [CorrId: {}] Failed to send Kafka request for route {}: {}", correlationId, routeName, ex.getMessage(), ex); 
                    } else {
                        log.debug("[CorrId: {}] Kafka request sent successfully to topic {}. Offset: {}", correlationId, requestTopic, result.getRecordMetadata().offset());
                    }
                });

                return Mono.fromFuture(replyFuture)
                    .flatMap(replyRecord -> processKafkaReply(exchange, replyRecord, correlationId, routeName))
                    .doOnTerminate(() -> {
                        long duration = System.currentTimeMillis() - filterStartTime;
                        log.info("<<< [CorrId: {}] Manual Kafka Filter End. Route: {}. Duration: {}ms", correlationId, routeName, duration);
                    })
                    .onErrorResume(ex -> handleProcessingError(exchange, ex, correlationId, routeName));

            }).onErrorResume(ex -> handleProcessingError(exchange, ex, correlationId, routeName));
        };
    }

    private boolean isUserKeyRequired(String routeName) {
        return "GetUserProfileRoute".equalsIgnoreCase(routeName) || "UpdateUserProfileRoute".equalsIgnoreCase(routeName);
    }

    private String determineKafkaKey(String routeName, String userId, String correlationId) {
        if (isUserKeyRequired(routeName)) {
            if (userId != null && !userId.isBlank()) {
                log.debug("[CorrId: {}] Setting Kafka key for route {} to userId: {}", correlationId, routeName, userId);
                return userId;
            } else {
                log.warn("[CorrId: {}] userId (X-User-Id header) is missing for route {}, cannot set required Kafka key.", correlationId, routeName);
                return null;
            }
        }
        return null;
    }

    private Mono<Object> prepareKafkaPayload(ServerWebExchange exchange, String routeName, HttpMethod method, String userId, Map<String, String> pathVariables, MultiValueMap<String, String> queryParams) {
        ServerHttpRequest request = exchange.getRequest();
        Map<String, Object> payloadMap = new HashMap<>();
        if (userId != null && !userId.isBlank()) {
            payloadMap.put("userId", userId);
        }
        if (queryParams != null) queryParams.forEach((key, value) -> payloadMap.put(key, value.size() == 1 ? value.get(0) : value));
        if (pathVariables != null) payloadMap.putAll(pathVariables);

        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    String requestBodyStr = new String(bytes, StandardCharsets.UTF_8);
                    log.trace("[CorrId: {}] Received request body for route {}: {}", exchange.getRequest().getId(), routeName, requestBodyStr);

                    try {
                        Object requestBodyDto = parseRequestBody(bytes, routeName);
                        if ("RegisterRoute".equalsIgnoreCase(routeName) || "LoginRoute".equalsIgnoreCase(routeName)) {
                            return Mono.just(requestBodyDto);
                        } else {
                            Map<String, Object> bodyMap = convertToMap(requestBodyDto);
                            payloadMap.putAll(bodyMap);
                            return Mono.just(payloadMap);
                        }
                    } catch (IOException e) {
                         String errorMsg = "Invalid request body format: " + e.getMessage();
                         log.error("!!! [CorrId: {}] {} for route {}", exchange.getRequest().getId(), errorMsg, routeName, e);
                         return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMsg, e));
                    } catch (IllegalArgumentException e) {
                         String errorMsg = "Cannot process request body: " + e.getMessage();
                         log.error("!!! [CorrId: {}] {} for route {}", exchange.getRequest().getId(), errorMsg, routeName, e);
                         return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMsg, e));
                    }
                });
        } else {
             return Mono.just(payloadMap);
        }
    }

    private Object parseRequestBody(byte[] bytes, String routeName) throws IOException {
         if (bytes == null || bytes.length == 0) {
             if ("RegisterRoute".equalsIgnoreCase(routeName)) return new RegisterRequest();
             if ("LoginRoute".equalsIgnoreCase(routeName)) return new LoginRequest();
             return new HashMap<>();
         }
         if ("RegisterRoute".equalsIgnoreCase(routeName)) {
             return objectMapper.readValue(bytes, RegisterRequest.class);
         } else if ("LoginRoute".equalsIgnoreCase(routeName)) {
             return objectMapper.readValue(bytes, LoginRequest.class);
         } else {
             return objectMapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});
         }
    }

     @SuppressWarnings("unchecked") // Suppress warning for intentional cast after instanceof check
     private Map<String, Object> convertToMap(Object dto) {
         if (dto == null) return new HashMap<>();
         if (dto instanceof Map) {
            try {
                 return (Map<String, Object>) dto;
            } catch (ClassCastException e) {
                log.warn("Failed to cast Object identified as Map to Map<String, Object>. Attempting ObjectMapper conversion.", e);
            }
         }
         try {
            return objectMapper.convertValue(dto, new TypeReference<Map<String, Object>>() {});
         } catch (IllegalArgumentException e) {
             log.warn("Could not convert DTO {} to Map, returning empty map. Error: {}", dto.getClass().getSimpleName(), e.getMessage());
             return new HashMap<>();
         }
     }

     @SuppressWarnings("unchecked")
    private Mono<Void> processKafkaReply(ServerWebExchange exchange, ConsumerRecord<String, Object> replyRecord, String originalCorrelationId, String routeName) {
        ServerHttpResponse response = exchange.getResponse();
        Object kafkaReply = replyRecord.value();
        log.debug("[CorrId: {}] Processing Kafka reply record for route {}: {}", originalCorrelationId, routeName, kafkaReply);

        Map<String, Object> replyMap;
        try {
            if (kafkaReply instanceof Map) {
                replyMap = (Map<String, Object>) kafkaReply;
            } else if (kafkaReply instanceof String) {
                 if (((String) kafkaReply).isBlank() || ((String) kafkaReply).equals("{}")) {
                     replyMap = new HashMap<>();
                 } else {
                    replyMap = objectMapper.readValue((String)kafkaReply, new TypeReference<Map<String, Object>>() {});
                 }
            } else if (kafkaReply == null) {
                log.warn("[CorrId: {}] Received null Kafka reply value for route {}. Treating as empty map.", originalCorrelationId, routeName);
                replyMap = new HashMap<>();
            } else {
                 throw new IllegalArgumentException("Reply value is not a Map or parsable String: " + kafkaReply.getClass().getName());
            }
        } catch (IOException | IllegalArgumentException | ClassCastException e) {
             log.error("!!! [CorrId: {}] Failed to parse Kafka reply map for route {}: {} - Reply Value: {}", originalCorrelationId, routeName, e.getMessage(), kafkaReply, e);
             return handleProcessingError(exchange, new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid reply format received from backend service."), originalCorrelationId, routeName);
        }

        // Determine status first, using status field primarily, falling back to success field
        boolean successFieldPresent = Boolean.TRUE.equals(replyMap.get("success"));
        HttpStatus status = determineHttpStatus(replyMap, successFieldPresent, routeName);
        boolean isConsideredSuccess = status.is2xxSuccessful(); // Success is determined by resolved HTTP status
        
        // Extract the correct payload based on route and success status
        Object bodyToSend;
        if (isConsideredSuccess) {
            if ("GetRepsByDistrictRoute".equalsIgnoreCase(routeName)) {
                bodyToSend = replyMap.getOrDefault("representatives", new java.util.ArrayList<>()); // Use List
            } else if ("GetPoliticsNewsRoute".equalsIgnoreCase(routeName)) {
                bodyToSend = replyMap.getOrDefault("articles", new java.util.ArrayList<>()); // Use List and correct key
            } else {
                // Default behaviour for other routes
                bodyToSend = replyMap.getOrDefault("payload", replyMap.getOrDefault("data", new HashMap<>()));
            }
        } else {
             // Use "error" field, or fallback to "message" field for error details
             bodyToSend = replyMap.getOrDefault("error", 
                         replyMap.getOrDefault("message", "Error processing request")); 
        }

        // Set actual HTTP status code on the response
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        // Log the body object before serialization
        log.debug("[CorrId: {}] Preparing HTTP response body for route {}. Status: {}. Body: {}", 
                  originalCorrelationId, routeName, status, bodyToSend);

        // Serialize the determined body (payload or error)
        byte[] responseBytes = serializeReplyPayload(bodyToSend, originalCorrelationId);
        
        // Log the exact serialized bytes as a String
        log.debug("[CorrId: {}] Sending exact HTTP response body ({} bytes): {}", 
                  originalCorrelationId, responseBytes.length, new String(responseBytes, StandardCharsets.UTF_8));

        // Set content length header
        response.getHeaders().setContentLength(responseBytes.length);
        
        // Wrap bytes in DataBuffer and write to response
        DataBuffer buffer = response.bufferFactory().wrap(responseBytes);
        return response.writeWith(Mono.just(buffer));
    }

    private HttpStatus determineHttpStatus(Map<String, Object> replyMap, boolean success, String routeName) {
        Object statusObj = replyMap.get("status");
        HttpStatus resolvedStatus = null;

        // Try to resolve status from the reply map first
        if (statusObj instanceof Integer) {
            try {
                resolvedStatus = HttpStatus.valueOf((Integer) statusObj);
            } catch (IllegalArgumentException e) {
                log.warn("Received invalid integer HTTP status code {} in reply, falling back.", statusObj);
            }
        } else if (statusObj instanceof String) {
             String statusStr = ((String) statusObj).trim(); // Trim whitespace
             try {
                 // Attempt to parse the string as an integer first
                 resolvedStatus = HttpStatus.valueOf(Integer.parseInt(statusStr));
             } catch (NumberFormatException nfe) {
                 // Not a number, check for known string values (case-insensitive)
                 if ("SUCCESS".equalsIgnoreCase(statusStr)) {
                     resolvedStatus = HttpStatus.OK;
                 } else if ("ERROR".equalsIgnoreCase(statusStr) || "FAILURE".equalsIgnoreCase(statusStr)) {
                     resolvedStatus = HttpStatus.INTERNAL_SERVER_ERROR;
                 } else if ("NOT_FOUND".equalsIgnoreCase(statusStr)) {
                     resolvedStatus = HttpStatus.NOT_FOUND;
                 } else {
                     // Log the unrecognized string and fall through to default logic
                     log.warn("Received unrecognized string HTTP status code '{}' in reply, falling back.", statusStr);
                 }
             } catch (IllegalArgumentException iae) {
                 // Parsed as integer, but not a valid HTTP status code
                 log.warn("Received integer HTTP status code {} (from string '{}') which is not a valid HttpStatus, falling back.", statusStr, statusStr);
             }
        }

        // If status was resolved from the map (either integer or recognized string), return it
        if (resolvedStatus != null) {
            return resolvedStatus;
        }
        
        // Otherwise, fall back to default logic based on success flag or route type
        if (success) {
            if ("RegisterRoute".equalsIgnoreCase(routeName)) return HttpStatus.CREATED;
            return HttpStatus.OK;
        } else {
             // Default error status if not provided or invalid in reply
             return HttpStatus.INTERNAL_SERVER_ERROR; 
        }
    }

    private Mono<Void> handleProcessingError(ServerWebExchange exchange, Throwable ex, String correlationId, String routeName) {
        ServerHttpResponse response = exchange.getResponse();
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "An internal error occurred.";

        if (ex instanceof ResponseStatusException) {
            ResponseStatusException rse = (ResponseStatusException) ex;
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            message = rse.getReason() != null ? rse.getReason() : "Gateway processing error.";
        } else if (ex instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "Response from backend service timed out.";
        } else if (ex.getCause() instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "Response from backend service timed out.";
        } else {
             log.error("!!! [CorrId: {}] Unexpected error during Kafka filter processing for route {}: {}", correlationId, routeName, ex.getMessage(), ex);
             message = "Unexpected error processing request.";
        }

        log.warn("<<< [CorrId: {}] Error response for route {}. Status: {}, Message: {}", correlationId, routeName, status, message);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Ensure message is not null before replacing quotes for JSON safety
        String safeMessage = (message != null) ? message.replace("\"", "\\\"") : "Unknown error";

        String errorPayload = String.format("{\"success\": false, \"status\": %d, \"error\": \"%s\", \"message\": \"%s\"}",
                status.value(), status.getReasonPhrase(), safeMessage);
        byte[] bytes = errorPayload.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

     private byte[] serializeReplyPayload(Object payload, String correlationId) {
         try {
             if (payload == null) return "{}".getBytes(StandardCharsets.UTF_8);
             return objectMapper.writeValueAsBytes(payload);
         } catch (JsonProcessingException e) {
             log.error("!!! [CorrId: {}] Failed to serialize reply payload: {}", correlationId, e.getMessage(), e);
             String errorJson = String.format("{\"success\": false, \"status\": 500, \"error\": \"Internal Server Error\", \"message\": \"Failed to serialize response: %s\"}", e.getMessage().replace("\"", "\\\""));
             return errorJson.getBytes(StandardCharsets.UTF_8);
         }
     }

    public static class Config {
        private String name;
        private String requestTopic;
        private String replyTopic;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRequestTopic() { return requestTopic; }
        public void setRequestTopic(String requestTopic) { this.requestTopic = requestTopic; }
        public String getReplyTopic() { return replyTopic; }
        public void setReplyTopic(String replyTopic) { this.replyTopic = replyTopic; }
    }
} 