package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

/**
 * Comprobante de pago subido por el prestatario desde mi-prestamo.html.
 * Queda pendiente de verificación manual por parte del prestamista (dueño del préstamo).
 */
@Data
@Document(collection = "payment_proofs")
public class PaymentProof {
    @Id
    private String id;

    private String userId;     // dueño del préstamo (prestamista) -> a quien le llega la notificación
    private String loanId;
    private String borrower;   // nombre del prestatario
    private String phone;      // teléfono del prestatario
    private double amount;     // monto que dice haber pagado
    private String note;       // nota opcional del prestatario
    private String imageBase64; // foto del comprobante (data URL)

    private String estado = "pendiente"; // pendiente | aprobado | rechazado
    private boolean leido = false;       // si el prestamista ya vio la notificación

    private LocalDateTime fecha = LocalDateTime.now();
}