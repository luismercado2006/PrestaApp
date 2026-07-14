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
    private boolean archivado = false;

    // Posición manual dentro del historial de pagos (0 = primero / más arriba).
    // Si es null, el pago no ha sido reordenado manualmente y el orden se
    // calcula automáticamente por fecha (más reciente primero).
    private Integer sortOrder;
}