package APIGateway.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    private String token;
    private Object user; // Can be Map<String, Object> or a specific User DTO if defined

    // Default constructor
    public AuthResponse() {}

    // Constructor with fields
    public AuthResponse(String token, Object user) {
        this.token = token;
        this.user = user;
    }

    // Getters and Setters
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Object getUser() {
        return user;
    }

    public void setUser(Object user) {
        this.user = user;
    }

    @Override
    public String toString() {
        return "AuthResponse{" +
               "token='" + (token != null ? "[PRESENT]" : "null") + '\'' +
               ", user=" + user +
               '}';
    }
} 