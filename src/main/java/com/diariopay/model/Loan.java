package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Data
@Document(collection = "loans")
@CompoundIndexes({
        // Cubre GET /api/loans/rutas/{nombre}: filtra por userId + ruta y
        // ordena por createdAt, todo en un solo índice.
        @CompoundIndex(name = "userId_ruta_createdAt", def = "{'userId': 1, 'ruta': 1, 'createdAt': -1}"),
        // Cubre GET /api/loans (listado general de un usuario, ordenado).
        @CompoundIndex(name = "userId_createdAt", def = "{'userId': 1, 'createdAt': -1}")
})
public class Loan {
    @Id
    private String id;

    // Índices: sin esto Mongo hace un escaneo completo de la colección en
    // cada consulta (findByUserId..., findByPhoneIn, ruta), y en una base
    // en la nube eso se nota mucho más por la latencia de red por consulta.
    @Indexed
    private String phone;  // número WhatsApp del prestatario, ej: "573001234567"
    @Indexed
    private String userId;
    private String borrower;
    private double amount;
    private double interest;
    private String frequency;   // daily | weekly | biweekly | monthly
    private String loanType;     // normal | grande | metodo | extra
    private String status;      // active | paid | overdue
    private int months;         // plazo en meses (solo usado por loanType = "extra")
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime dueDate;

    // Hora REAL en la que se registró el préstamo en el sistema (no depende de
    // startDate, que puede ser una fecha pasada, futura o de medianoche).
    // Se usa para saber si el préstamo se otorgó mientras una caja estaba
    // abierta, y así descontar correctamente el capital prestado del efectivo
    // en caja. Puede ser null en préstamos creados antes de este cambio.
    private LocalDateTime fechaRegistro;
    private LocalDate startDate;
    private LocalDate endDate;
    private int totalInstallments;
    private double installmentAmount;
    private boolean moraNotificada = false;
    @Indexed
    private String ruta;  // nombre de la ruta (opcional), ej: "Santa Rosa"

    // Solo aplica cuando frequency = "weekly": cuántos días hay entre una cuota
    // y la siguiente. Si el usuario elige "5 cuotas en el mes" en vez de las
    // clásicas 4, el intervalo se acorta (ej: 6 días) para que las 5 quepan
    // dentro del mes sin pasarse de la fecha de fin. Si es null, se usa el
    // comportamiento clásico de 7 días (préstamos creados antes de este cambio).
    private Integer weeklyIntervalDays;

    // ─── RENOVACIÓN DE CRÉDITO ──────────────────────────────────
    private boolean renovado = false;
    private RenovacionSnapshot snapshotAnterior;

    public int getWeeklyIntervalDaysOrDefault() {
        return (weeklyIntervalDays != null && weeklyIntervalDays > 0) ? weeklyIntervalDays : 7;
    }

    @lombok.Data
    public static class RenovacionSnapshot {
        private double amount;
        private double interest;
        private String frequency;
        private String loanType;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime dueDate;
        private LocalDate startDate;
        private LocalDate endDate;
        private int totalInstallments;
        private double installmentAmount;
        private boolean moraNotificada;
        private int months;
        private Integer weeklyIntervalDays;
    }
}