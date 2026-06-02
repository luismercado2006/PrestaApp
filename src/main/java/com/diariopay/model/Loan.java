package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data
@Document(collection = "loans")
public class Loan {
    @Id
    private String id;

    private String userId;
    private String borrower;
    private double amount;
    private double interest;
    private String frequency;   // daily | weekly | monthly
    private String status;      // active | paid | overdue
    private String notes;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime dueDate;
}
