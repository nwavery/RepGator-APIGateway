# RepGator API Gateway

## Overview

This service acts as the central API Gateway for the RepGator application ecosystem. Built using Spring Cloud Gateway, it serves as the primary entry point for all client requests (e.g., from the mobile app). Its main responsibilities include:

*   **Request Routing:** Directing incoming HTTP requests to the appropriate downstream microservices via Kafka.
*   **Authentication & Authorization:** Validating JWT tokens for protected endpoints using a custom `JwtAuthentication` filter.
*   **Kafka Integration:** Implementing a request/reply pattern over Kafka using a custom `KafkaRequestReply` filter, abstracting the asynchronous nature of Kafka from the client.
*   **Centralized Configuration:** Managing routes, filters, and service communication details in `application.yml`.

## Key Features

*   **Spring Cloud Gateway:** Leverages the robust features of Spring Cloud Gateway for routing and filtering.
*   **JWT Authentication:** Secures endpoints using JSON Web Tokens. User ID is extracted and passed downstream.
*   **Kafka Request/Reply Filter:** Handles asynchronous communication with backend microservices. It sends requests to specific Kafka topics and correlates replies using a unique ID.
*   **Centralized Routing:** All API routes are defined declaratively in `src/main/resources/application.yml`.
*   **Kafka Topic Management:** Request and Reply topics are defined in `application.yml` and beans for auto-creation (where appropriate) are managed in `src/main/java/APIGateway/demo/config/KafkaTopicConfig.java`.
*   **Rate Limiting:** (Currently commented out in `application.yml`) Configuration exists for Redis-based rate limiting.

## Endpoints Handled

The gateway proxies requests for various backend services:

*   **Authentication (`/auth/**`)**: User registration and login (via User Service).
*   **User Profile (`/user/profile`)**: Get and update user details (via User Service).
*   **Representatives (`/representatives`, `/representatives/{repId}`)**: Get representative/senator lists by district or ID (via Representative Service).
*   **Messaging (`/conversations`, `/conversations/{id}/messages`, `/messages`)**: Manage direct messages and conversations (via Message Service).
*   **Dashboard (`/dashboard/summary`)**: Get user dashboard summary (via User Service).
*   **Discussions (`/discussions`)**: Get and post messages in district discussion forums (via Message Service).
*   **News (`/news/politics`)**: Get political news articles (via District Service).

*Note: All endpoints except `/auth/**` require JWT authentication.*

## Configuration (`src/main/resources/application.yml`)

This file is central to the gateway's operation. Key sections include:

*   `spring.cloud.gateway.routes`: Defines all HTTP routes, predicates (path, method, query params), and the filters applied to each (e.g., `JwtAuthentication`, `KafkaRequestReply`).
*   `spring.kafka`: Configuration for connecting to the Kafka cluster (bootstrap servers, security credentials, serializers/deserializers).
*   `app.kafka.topics`: Defines the specific Kafka request and reply topic names used by the routes and listeners.
*   `app.jwt.secret-key`: **IMPORTANT:** Contains the secret key for validating JWT signatures. **Never commit real secrets directly.** Use environment variables or a secrets management system in production.
*   `logging.level`: Configures logging verbosity for different packages.

## Running the Service

### Prerequisites

*   Java Development Kit (JDK) (Version specified in `pom.xml`)
*   Maven (for building)
*   Access to the configured Kafka cluster (Confluent Cloud) with appropriate credentials.
*   Access to required backend microservices (User Service, Representative Service, District Service, Message Service) running and connected to Kafka.
*   (Optional) Redis instance running if rate limiting is enabled.

### Build

```bash
# Navigate to the project root directory
mvn clean install
```

### Run

```bash
# Navigate to the target directory
cd target
# Run the JAR file
java -jar RepGator-APIGateway-*.jar 
```
Alternatively, run directly from your IDE (e.g., IntelliJ, VS Code with Java extensions).

## Key Code Components

*   **`filter/JwtAuthenticationGatewayFilterFactory.java`**: Custom filter for validating JWTs and extracting user information.
*   **`filter/KafkaRequestReplyGatewayFilterFactory.java`**: Custom filter implementing the Kafka request/reply logic, handling payload preparation, sending requests, and processing replies.
*   **`kafka/ReplyCorrelationService.java`**: Manages the correlation of Kafka replies back to pending HTTP requests using correlation IDs.
*   **`kafka/ReplyListener.java`**: Contains `@KafkaListener` annotations (or is targeted by programmatic configuration) to consume messages from specific reply topics.
*   **`config/KafkaConfig.java`**: Configures Kafka components, including listener container factories and the programmatic listener for the news topic.
*   **`config/KafkaTopicConfig.java`**: Defines `NewTopic` beans for topic auto-creation (primarily for topics the gateway produces to or consumes replies from).
*   **`resources/application.yml`**: Central configuration file for routes, Kafka, JWT, etc.

## Kafka Interaction Flow

1.  Gateway receives an HTTP request.
2.  Relevant filters run (e.g., `JwtAuthentication`).
3.  `KafkaRequestReplyGatewayFilterFactory` applies:
    *   Extracts necessary data (path variables, query params, user ID, request body).
    *   Constructs Kafka message payload.
    *   Generates a unique `correlationId`.
    *   Determines request and reply topics from configuration.
    *   Sends the message to the request topic via `KafkaTemplate`.
    *   Registers the `correlationId` and a `CompletableFuture` with `ReplyCorrelationService`, waiting for the reply.
4.  Backend service processes the request and sends a reply message to the specified reply topic, including the original `correlationId` in the headers.
5.  `ReplyListener` (or the programmatic listener in `KafkaConfig`) consumes the message from the reply topic.
6.  The listener extracts the `correlationId` and calls `ReplyCorrelationService.completeRequest`.
7.  `ReplyCorrelationService` finds the pending `CompletableFuture` and completes it with the received Kafka reply record.
8.  The `flatMap` in `KafkaRequestReplyGatewayFilterFactory` resumes execution.
9.  The `processKafkaReply` method parses the reply record, determines success/failure, extracts the relevant payload or error message, determines the HTTP status, and formats the final HTTP response.
10. The HTTP response is sent back to the original client.

## Notes

*   The listener for the `/news/politics` reply topic (`res.district-service.api-gateway.get-politics-news`) is configured programmatically in `KafkaConfig.java` due to previous issues encountered with the `@KafkaListener` annotation for that specific topic. All other reply listeners use the annotation-based approach in `ReplyListener.java`.
*   Ensure Kafka credentials and JWT secrets are handled securely and are not hardcoded or committed to version control in production environments. 