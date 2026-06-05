package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data
@Document(collection = "payments")
public class Payment {
    @Id
    private String id;

    private String userId;
    private String loanId;
    private double amount;
    private String note;
    private String paymentType; // capital | interest | normal
    private LocalDateTime date = LocalDateTime.now();
}
