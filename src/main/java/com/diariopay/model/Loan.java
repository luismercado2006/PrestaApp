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
    private String phone;  // número WhatsApp del prestatario, ej: "573001234567"
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
    private LocalDate startDate;
    private LocalDate endDate;
    private int totalInstallments;
    private double installmentAmount;
    private boolean moraNotificada = false;
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