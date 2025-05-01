package APIGateway.demo.filter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.ratelimiter.RequestNotPermitted; // Import RequestNotPermitted
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

@Component
public class Resilience4jRateLimiterFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jRateLimiterFilter.class);
    private static final String DEFAULT_LIMITER_NAME = "defaultLimiter"; // Matches name in application.yml
    // Run relatively early, but after potential tracing/auth filters
    private static final int FILTER_ORDER = 5; 

    private final RateLimiterRegistry rateLimiterRegistry;

    public Resilience4jRateLimiterFilter(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ipAddress = resolveIpAddress(exchange);
        // Get the RateLimiter instance configured in application.yml
        // Note: Resilience4j RateLimiter is typically applied per *backend instance*, not per *user key*.
        // To limit per IP, we would need a dynamic way to manage RateLimiter instances,
        // perhaps using a cache (like Caffeine or Redis) mapping IP -> RateLimiter instance.
        // The default RateLimiterRegistry doesn't create instances dynamically per key.
        
        // --- Simplified Approach: Apply a SINGLE global limiter ---
        // This limits the overall request rate to the gateway, not per-IP.
        // For per-IP limiting, a more complex setup is needed (see notes below).
        RateLimiter globalRateLimiter = rateLimiterRegistry.rateLimiter(DEFAULT_LIMITER_NAME);
        log.trace("Applying global rate limiter '{}' to request from IP {}", DEFAULT_LIMITER_NAME, ipAddress);

        // Use RateLimiterOperator to apply the limit reactively
        return chain.filter(exchange)
                .transformDeferred(RateLimiterOperator.of(globalRateLimiter))
                .onErrorResume(RequestNotPermitted.class, ex -> { // Use specific exception type
                    // Handle the rate limit exceeded exception
                    log.warn("Rate limit exceeded for global limiter '{}' by IP {}: {}", DEFAULT_LIMITER_NAME, ipAddress, ex.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS); // 429
                    // Optionally add Retry-After header (Resilience4j might provide wait time)
                    // exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(ex.getRateLimiter().getRateLimiterConfig().getTimeoutDuration().getSeconds())); 
                    return exchange.getResponse().setComplete();
                });

        // --- Notes for Per-IP Limiting (More Complex) ---
        // 1. Need a Map or Cache: ConcurrentHashMap<String, RateLimiter> ipLimiterCache;
        // 2. Get or Create Limiter:
        //    RateLimiter limiter = ipLimiterCache.computeIfAbsent(ipAddress, key -> {
        //        io.github.resilience4j.ratelimiter.RateLimiterConfig config = rateLimiterRegistry.getConfiguration(DEFAULT_LIMITER_NAME)
        //                                   .orElseThrow(() -> new IllegalStateException("Default limiter config not found"));
        //        return RateLimiter.of(key + "_" + DEFAULT_LIMITER_NAME, config);
        //    });
        // 3. Apply dynamically created limiter: chain.filter(exchange).transformDeferred(RateLimiterOperator.of(limiter))...
        // 4. Cache eviction strategy needed to prevent memory leaks.
    }

    private String resolveIpAddress(ServerWebExchange exchange) {
        // Same logic as used in the previous KeyResolver attempt
        List<String> xffHeaders = exchange.getRequest().getHeaders().get("X-Forwarded-For");
        if (xffHeaders != null && !xffHeaders.isEmpty()) {
            return xffHeaders.get(0).split(",")[0].trim();
        }
        return Objects.requireNonNull(exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
} 