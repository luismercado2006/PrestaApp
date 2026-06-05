package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.time.LocalDate;

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
    private String loanType;     // normal | grande
    private String status;      // active | paid | overdue
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime dueDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private int totalInstallments;
    private double installmentAmount;
}
