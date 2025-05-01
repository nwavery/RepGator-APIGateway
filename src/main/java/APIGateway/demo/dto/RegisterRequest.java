package APIGateway.demo.dto;

// Add validation annotations later if needed (e.g., @NotBlank, @Email)
public class RegisterRequest {

    private String email;
    private String password;
    private String name;
    private String address;

    // Default constructor (needed for Jackson deserialization)
    public RegisterRequest() {
    }

    // Constructor with fields
    public RegisterRequest(String email, String password, String name, String address) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.address = address;
    }

    // Getters and Setters
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return "RegisterRequest{" +
                "email='" + email + '\'' +
                ", name='" + name + '\'' +
                ", address='" + address + '\'' +
                // Avoid logging password in production
                ", password='********'" +
                '}';
    }
} 