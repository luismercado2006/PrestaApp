package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data
@Document(collection = "users")
public class User {
    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    private String password;
    private String name;

    @Indexed(unique = true, sparse = true)
    private String email;

    private String role = "USER";
    private LocalDateTime createdAt = LocalDateTime.now();
}
