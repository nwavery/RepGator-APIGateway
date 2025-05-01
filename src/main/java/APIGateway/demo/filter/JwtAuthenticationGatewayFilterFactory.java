package APIGateway.demo.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JwtAuthenticationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthenticationGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationGatewayFilterFactory.class);
    private static final String BEARER_PREFIX = "Bearer ";
    
    private final SecretKey secretKey;

    public JwtAuthenticationGatewayFilterFactory(SecretKey secretKey) {
        super(Config.class);
        this.secretKey = secretKey;
        log.info("JwtAuthenticationGatewayFilterFactory initialized with provided SecretKey.");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            log.debug("Applying JWT Authentication Filter for path: {}", request.getPath());

            if (!isAuthenticationRequired(request, config)) {
                 log.debug("Authentication not required for path '{}', skipping JWT check.", request.getPath().value());
                 return chain.filter(exchange); 
            }

            String authHeader = getAuthorizationHeader(request);

            if (authHeader == null) {
                log.warn("Missing Authorization header for protected path: {}", request.getPath().value());
                return unauthorizedResponse(exchange, "Missing Authorization header");
            }

            if (!authHeader.startsWith(BEARER_PREFIX)) {
                log.warn("Authorization header does not start with Bearer prefix for path: {}", request.getPath().value());
                return unauthorizedResponse(exchange, "Invalid Authorization header format");
            }

            String token = authHeader.substring(BEARER_PREFIX.length());

            if (token.isBlank()) {
                 log.warn("Blank JWT token found for path: {}", request.getPath().value());
                 return unauthorizedResponse(exchange, "Blank JWT token");
            }

            // --- Full JWT Validation ---
            try {
                // Parse and validate the token using the injected secret key
                Jws<Claims> claimsJws = Jwts.parser()
                                            .verifyWith(secretKey)
                                            .build()
                                            .parseSignedClaims(token);

                Claims claims = claimsJws.getPayload();
                String userId = claims.getSubject(); // Assuming subject contains the user ID

                if (userId == null || userId.isBlank()) {
                     log.warn("JWT token is valid but missing subject (user ID) claim.");
                     // Decide if this is an error - depends on application requirements.
                     // Could return unauthorized or proceed without user ID header.
                     // For now, let's treat it as unauthorized if ID is expected.
                     return unauthorizedResponse(exchange, "Valid token but missing user identifier.");
                }

                log.debug("JWT validation successful for path: {}, User ID: {}", request.getPath().value(), userId);

                // Add extracted User ID (and potentially other claims) as headers
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .header("X-User-Id", userId)
                        // Example: Add roles if present in claims
                        // .header("X-User-Roles", claims.get("roles", String.class)) 
                        .build();
                
                ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
                
                return chain.filter(mutatedExchange); // Proceed with mutated request

            } catch (ExpiredJwtException e) {
                log.warn("JWT validation failed for path {}: Token expired - {}", request.getPath().value(), e.getMessage());
                return unauthorizedResponse(exchange, "Token expired");
            } catch (UnsupportedJwtException e) {
                log.warn("JWT validation failed for path {}: Unsupported token format - {}", request.getPath().value(), e.getMessage());
                return unauthorizedResponse(exchange, "Unsupported token format");
            } catch (MalformedJwtException e) {
                log.warn("JWT validation failed for path {}: Malformed token - {}", request.getPath().value(), e.getMessage());
                return unauthorizedResponse(exchange, "Malformed token");
            } catch (SignatureException e) {
                log.warn("JWT validation failed for path {}: Invalid signature - {}", request.getPath().value(), e.getMessage());
                return unauthorizedResponse(exchange, "Invalid token signature");
            } catch (IllegalArgumentException e) { // Handles other parsing issues
                log.warn("JWT validation failed for path {}: Invalid token - {}", request.getPath().value(), e.getMessage());
                return unauthorizedResponse(exchange, "Invalid token");
            } catch (JwtException e) { // Catch-all for other JWT errors
                 log.error("Unexpected JWT validation error for path {}: {}", request.getPath().value(), e.getMessage(), e);
                 return unauthorizedResponse(exchange, "Invalid token processing error");
            }
        };
    }

    private boolean isAuthenticationRequired(ServerHttpRequest request, Config config) {
        if (config.getIgnoredPaths() != null) {
            String requestPath = request.getPath().pathWithinApplication().value();
            // Ensure ignored paths start with / for consistent matching
            return config.getIgnoredPaths().stream()
                .map(p -> p.startsWith("/") ? p : "/" + p) // Normalize ignored path
                .noneMatch(requestPath::startsWith);
        }
        return true; // Require authentication by default if no ignored paths are configured
    }

    private String getAuthorizationHeader(ServerHttpRequest request) {
        List<String> headers = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return headers.get(0); // Return the first Authorization header found
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8");
        String jsonPayload = String.format("{\"error\":\"Unauthorized\", \"message\":\"%s\"}", message);
        byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    public static class Config {
        private List<String> ignoredPaths;

        public List<String> getIgnoredPaths() { return ignoredPaths; }
        public void setIgnoredPaths(List<String> ignoredPaths) { this.ignoredPaths = ignoredPaths; }
    }
    
    @Override
    public List<String> shortcutFieldOrder() {
        return List.of(); 
    }
} 