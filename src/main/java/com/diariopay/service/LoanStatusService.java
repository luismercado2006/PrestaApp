package com.diariopay.service;

import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Calcula cuál debería ser el estado REAL de un préstamo (active | overdue | paid)
 * comparando las cuotas que ya se pagaron contra la fecha de la próxima cuota.
 *
 * Antes, el sistema solo sabía pasar un préstamo de "active" a "overdue"
 * (comparando contra el endDate final del crédito), pero nunca lo regresaba
 * a "active" cuando el cliente se ponía al día. Este servicio es el único
 * lugar que decide el estado, y se usa tanto al registrar un pago como en
 * las revisiones automáticas (dashboard y tarea programada), para que el
 * préstamo pueda volver a "active" si el cliente se pone al día.
 */
@Service
public class LoanStatusService {

    /**
     * Uso normal: revisiones periódicas (carga del dashboard, tarea programada).
     * Aquí sí se detecta mora para créditos "grande" comparando contra endDate.
     */
    public String calcularEstadoActual(Loan loan, List<Payment> payments) {
        return calcular(loan, payments, true);
    }

    /**
     * Uso específico: justo después de registrar un pago (endpoint /api/payments).
     * Para créditos "grande" NO se detecta mora aquí a propósito: en ese mismo
     * flujo de abono, el frontend puede extender el endDate y reactivar el
     * crédito un instante después (PUT /api/loans/{id}) — si detectáramos mora
     * en este punto, quedaría un estado "overdue" incorrecto y transitorio justo
     * cuando el cliente está pagando a tiempo. Esa detección para "grande" queda
     * exclusivamente a cargo de las revisiones periódicas.
     */
    public String calcularEstadoTrasPago(Loan loan, List<Payment> payments) {
        return calcular(loan, payments, false);
    }

    private String calcular(Loan loan, List<Payment> payments, boolean detectarMoraGrande) {
        if (loan == null) return "active";
        if ("paid".equals(loan.getStatus())) return "paid"; // una vez pagado, no se reabre solo

        double paidCapital = payments.stream()
                .filter(p -> "capital".equals(p.getPaymentType()) || "normal".equals(p.getPaymentType()))
                .filter(p -> p.getAmount() > 0)
                .mapToDouble(Payment::getAmount).sum();

        double totalAPagar;
        if ("grande".equals(loan.getLoanType()) || "metodo".equals(loan.getLoanType())) {
            totalAPagar = loan.getAmount(); // solo capital
        } else if ("extra".equals(loan.getLoanType())) {
            // Extra: interés fijo total = monto * interés% * meses, ya repartido
            // en installmentAmount * totalInstallments al crear/renovar el préstamo.
            totalAPagar = loan.getInstallmentAmount() * (loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1);
        } else {
            totalAPagar = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100);
        }
        if (paidCapital >= totalAPagar) return "paid";

        // Los créditos "grande" (revolventes, abono de interés mensual) manejan
        // su reactivación manualmente desde el frontend (al abonar el interés
        // completo se extiende el endDate y se reactiva). Aquí solo detectamos
        // cuándo CAE en mora por vencimiento (y solo en revisiones periódicas,
        // ver detectarMoraGrande); nunca lo sacamos de mora nosotros, para no
        // pisar esa lógica de renovación mensual del frontend.
        if ("grande".equals(loan.getLoanType())) {
            if (detectarMoraGrande
                    && "active".equals(loan.getStatus())
                    && loan.getEndDate() != null
                    && !LocalDate.now().isBefore(loan.getEndDate())) {
                return "overdue";
            }
            return loan.getStatus();
        }

        int totalInstallments = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
        int cuotasPagadas;
        if ("metodo".equals(loan.getLoanType())) {
            cuotasPagadas = (int) payments.stream()
                    .filter(p -> "capital".equals(p.getPaymentType()) && p.getAmount() > 0)
                    .count();
        } else {
            double cuota = loan.getInstallmentAmount() > 0 ? loan.getInstallmentAmount() : 1;
            cuotasPagadas = (int) Math.floor(paidCapital / cuota);
        }
        if (cuotasPagadas >= totalInstallments) return "paid";

        LocalDate start = loan.getStartDate() != null ? loan.getStartDate() : LocalDate.now();
        LocalDate proximaFecha = calcularFechaCuota(start, loan.getFrequency(), cuotasPagadas + 1);
        LocalDate hoy = LocalDate.now();

        return hoy.isAfter(proximaFecha) ? "overdue" : "active";
    }

    private LocalDate calcularFechaCuota(LocalDate start, String freq, int numeroCuota) {
        String f = freq != null ? freq : "daily";
        return switch (f) {
            case "monthly"  -> start.plusMonths(numeroCuota);
            case "weekly"   -> start.plusWeeks(numeroCuota);
            case "biweekly" -> start.plusDays(numeroCuota * 15L);
            default         -> start.plusDays(numeroCuota);
        };
    }
}