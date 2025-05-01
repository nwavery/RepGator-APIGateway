package APIGateway.demo.config;

import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct; // Use jakarta package for Spring Boot 3+
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.util.Base64;

@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);
    private static final String PLACEHOLDER_KEY = "placeholder-strong-base64-encoded-secret-key-32-bytes-long";
    private static final int MIN_KEY_LENGTH_BYTES = 32; // For HS256

    @Value("${app.jwt.secret-key}")
    private String secretKeyString;

    private SecretKey secretKey;

    @PostConstruct
    private void init() {
        log.debug("Initializing JWT Configuration...");
        if (secretKeyString == null || secretKeyString.isBlank()) {
            log.error("JWT secret key ('app.jwt.secret-key') is not configured.");
            throw new IllegalArgumentException("JWT secret key ('app.jwt.secret-key') must be configured.");
        }
        if (PLACEHOLDER_KEY.equals(secretKeyString)) {
            log.error("Placeholder JWT secret key is being used. Please replace 'app.jwt.secret-key' in configuration with a strong, unique, base64-encoded key (at least {} bytes).", MIN_KEY_LENGTH_BYTES);
            throw new IllegalArgumentException("Do not use the placeholder JWT secret key in production or development. Generate a secure key.");
        }

        try {
            byte[] decodedKey = Base64.getDecoder().decode(secretKeyString);
            if (decodedKey.length < MIN_KEY_LENGTH_BYTES) {
                log.error("Configured JWT secret key is too short ({} bytes). Minimum required length is {} bytes.", decodedKey.length, MIN_KEY_LENGTH_BYTES);
                throw new IllegalArgumentException("JWT secret key must be at least " + MIN_KEY_LENGTH_BYTES + " bytes long after Base64 decoding.");
            }
            this.secretKey = Keys.hmacShaKeyFor(decodedKey);
            log.info("JWT Secret Key initialized successfully.");
        } catch (IllegalArgumentException e) {
            log.error("Invalid Base64 encoding or key length for JWT secret key ('app.jwt.secret-key'): {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JWT secret key configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Provides the SecretKey bean derived from the configured string.
     * This key should be used for validating JWT signatures.
     *
     * @return The SecretKey instance.
     */
    @Bean
    public SecretKey jwtSecretKey() {
        // The @PostConstruct init() method ensures this.secretKey is initialized before
        // Spring attempts to create this bean.
        return this.secretKey;
    }

    // Optional: Getter for the raw string if needed, but using the SecretKey bean is safer.
    // public String getSecretKeyString() {
    //     return secretKeyString;
    // }
} 