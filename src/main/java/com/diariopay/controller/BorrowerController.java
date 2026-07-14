package com.diariopay.controller;

import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import com.diariopay.model.PaymentProof;
import com.diariopay.model.User;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.PaymentProofRepository;
import com.diariopay.repository.PaymentRepository;
import com.diariopay.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Endpoint PÚBLICO — no requiere sesión.
 * Permite a los prestatarios consultar sus préstamos ingresando su número de teléfono.
 */
@RestController
@RequestMapping("/public/borrower")
public class BorrowerController {

    @Autowired private LoanRepository    loanRepo;
    @Autowired private PaymentRepository paymentRepo;
    @Autowired private PaymentProofRepository proofRepo;
    @Autowired private UserRepository    userRepo;

    /** Nombre de la empresa/prestamista dueño de la cuenta que creó el préstamo. */
    private String obtenerNombrePrestamista(String userId) {
        if (userId == null) return "DiarioPay";
        return userRepo.findById(userId)
                .map(User::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("DiarioPay");
    }

    /**
     * GET /public/borrower/{phone}
     * Devuelve todos los préstamos activos/en mora/pagados asociados al teléfono.
     */
    @GetMapping("/{phone}")
    public ResponseEntity<?> getLoansByPhone(@PathVariable String phone) {
        // Normalizar: quitar espacios, guiones, +
        String normalizedPhone = phone.replaceAll("[\\s\\-+]", "");

        // Buscar por phone exacto o con prefijo 57
        List<Loan> loans = loanRepo.findAll().stream()
                .filter(l -> {
                    if (l.getPhone() == null || l.getPhone().isBlank()) return false;
                    String lp = l.getPhone().replaceAll("[\\s\\-+]", "");
                    return lp.equals(normalizedPhone)
                            || lp.equals("57" + normalizedPhone)
                            || normalizedPhone.equals("57" + lp);
                })
                .sorted(Comparator.comparing(
                        l -> l.getCreatedAt() != null ? l.getCreatedAt() : java.time.LocalDateTime.MIN,
                        Comparator.reverseOrder()))
                .toList();

        if (loans.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "No se encontraron préstamos con ese número de teléfono."
            ));
        }

        LocalDate hoy = LocalDate.now();
        List<Map<String, Object>> resultado = new ArrayList<>();

        for (Loan loan : loans) {
            List<Payment> payments = paymentRepo.findByLoanIdAndArchivadoFalse(loan.getId());
            boolean isGrande = "grande".equals(loan.getLoanType());

            double paidTotal = payments.stream().mapToDouble(Payment::getAmount).sum();

            // Solo abonos a capital/normal (excluye intereses) — para "grande" esto
            // es lo que realmente reduce la deuda; para normal/método coincide con paidTotal
            // salvo que haya pagos de tipo "interest" registrados aparte.
            // Incluye devoluciones (montos negativos) para que reduzcan el
            // capital pagado real, igual que en LoanStatusService.
            double paidCapital = payments.stream()
                    .filter(p -> "capital".equals(p.getPaymentType()) || "normal".equals(p.getPaymentType()))
                    .mapToDouble(Payment::getAmount).sum();

            // Base de progreso: en préstamo grande el avance es sobre CAPITAL (los intereses
            // se cobran aparte y no abonan a la deuda). En normal/método se usa el total pagado.
            double baseProgreso = isGrande ? paidCapital : paidTotal;

            // Total a pagar según tipo de préstamo
            double totalAPagar;
            if (isGrande) {
                // Grande: solo capital, intereses se cobran aparte
                totalAPagar = loan.getAmount();
            } else if ("metodo".equals(loan.getLoanType())) {
                // Metodo (amortización francesa): cuota fija x número de cuotas
                totalAPagar = loan.getInstallmentAmount() * loan.getTotalInstallments();
            } else if ("extra".equals(loan.getLoanType())) {
                // Extra: interés fijo total (monto * interés% * meses) ya repartido
                // entre las cuotas al crear/renovar el préstamo.
                totalAPagar = loan.getInstallmentAmount() * loan.getTotalInstallments();
            } else {
                // Normal: capital + interés simple
                totalAPagar = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100);
            }

            double saldo = Math.max(totalAPagar - baseProgreso, 0);
            double porcentaje = totalAPagar > 0 ? Math.min((baseProgreso / totalAPagar) * 100, 100) : 0;

            // Calcular días para próximo pago
            String proximaCuota = null;
            if ("active".equals(loan.getStatus())) {
                if (isGrande) {
                    // Grande: la "próxima cuota" es simplemente la fecha de vencimiento (endDate).
                    // Cada vez que se paga el interés completo del mes, el dashboard amplía endDate
                    // un mes más — aquí solo reflejamos esa misma fecha, sin recalcularla por cuotas.
                    LocalDate fin = loan.getEndDate();
                    if (fin != null) {
                        long diasRestantes = ChronoUnit.DAYS.between(hoy, fin);
                        String[] meses = {"ene","feb","mar","abr","may","jun","jul","ago","sep","oct","nov","dic"};
                        String fechaStr = fin.getDayOfMonth() + " " + meses[fin.getMonthValue()-1] + " " + fin.getYear();
                        if (diasRestantes <= 0) proximaCuota = "Hoy · " + fechaStr;
                        else if (diasRestantes == 1) proximaCuota = "Mañana · " + fechaStr;
                        else proximaCuota = "En " + diasRestantes + " días · " + fechaStr;
                    }
                } else if (loan.getStartDate() != null) {
                    // Normal/Método: Cuotas pagadas = pagos de capital/normal con monto positivo
                    long cuotasPagadas;
                    if ("metodo".equals(loan.getLoanType())) {
                        cuotasPagadas = payments.stream()
                                .filter(p -> "capital".equals(p.getPaymentType()) && p.getAmount() > 0)
                                .count();
                    } else {
                        // Incluye devoluciones (monto negativo) para que reduzcan
                        // las cuotas contadas como pagadas.
                        double totalPagadoNormal = payments.stream()
                                .filter(p -> "normal".equals(p.getPaymentType()))
                                .mapToDouble(Payment::getAmount).sum();
                        double cuotaFija = loan.getInstallmentAmount();
                        cuotasPagadas = cuotaFija > 0 ? Math.max(0, Math.round(totalPagadoNormal / cuotaFija)) : 0;
                    }
                    // La próxima cuota es startDate + (cuotasPagadas + 1) períodos.
// La cuota #1 vence UN período después del inicio (no el mismo día de creación).
                    long proximoPeriodo = cuotasPagadas + 1;
                    int totalInstallments = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
                    String freq = loan.getFrequency() != null ? loan.getFrequency() : "daily";
                    LocalDate endDate = loan.getEndDate();
                    LocalDate proxFecha;
                    if (proximoPeriodo >= totalInstallments && endDate != null) {
                        // La última cuota siempre vence el mismo día que "Vence" (endDate),
                        // sin importar el intervalo usado para las cuotas anteriores.
                        proxFecha = endDate;
                    } else {
                        proxFecha = switch (freq) {
                            case "weekly"   -> loan.getStartDate().plusDays(proximoPeriodo * (long) loan.getWeeklyIntervalDaysOrDefault());
                            case "biweekly" -> loan.getStartDate().plusDays(proximoPeriodo * 15L);
                            case "monthly"  -> loan.getStartDate().plusMonths(proximoPeriodo);
                            default         -> loan.getStartDate().plusDays(proximoPeriodo);
                        };
                    }
                    if (endDate == null || !proxFecha.isAfter(endDate)) {
                        long diasRestantes = ChronoUnit.DAYS.between(hoy, proxFecha);
                        String[] meses = {"ene","feb","mar","abr","may","jun","jul","ago","sep","oct","nov","dic"};
                        String fechaStr = proxFecha.getDayOfMonth() + " " + meses[proxFecha.getMonthValue()-1] + " " + proxFecha.getYear();
                        if (diasRestantes <= 0) proximaCuota = "Hoy · " + fechaStr;
                        else if (diasRestantes == 1) proximaCuota = "Mañana · " + fechaStr;
                        else proximaCuota = "En " + diasRestantes + " días · " + fechaStr;
                    }
                }
            }

            // Todos los pagos (más recientes primero). Si el prestamista
            // reordenó manualmente el historial desde el panel, respetamos
            // ese orden; si no, se ordena automáticamente por fecha.
            boolean ordenManual = payments.stream().anyMatch(p -> p.getSortOrder() != null);
            List<Map<String, Object>> ultimosPagos = payments.stream()
                    .sorted(ordenManual
                            ? Comparator.comparing(
                            (Payment p) -> p.getSortOrder() != null ? p.getSortOrder() : Integer.MAX_VALUE)
                            : Comparator.comparing(
                            (Payment p) -> p.getDate() != null ? p.getDate() : java.time.LocalDateTime.MIN,
                            Comparator.reverseOrder()))
                    .map(p -> {
                        Map<String, Object> pm = new LinkedHashMap<>();
                        pm.put("amount",      p.getAmount());
                        pm.put("date",        p.getDate());
                        pm.put("paymentType", p.getPaymentType());
                        pm.put("note",        p.getNote());
                        return pm;
                    })
                    .toList();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",                 loan.getId());
            item.put("borrower",           loan.getBorrower());
            item.put("empresa",            obtenerNombrePrestamista(loan.getUserId()));
            item.put("amount",             loan.getAmount());
            item.put("interest",           loan.getInterest());
            item.put("frequency",          loan.getFrequency());
            item.put("loanType",           loan.getLoanType() != null ? loan.getLoanType() : "normal");
            item.put("status",             loan.getStatus());
            item.put("startDate",          loan.getStartDate());
            item.put("endDate",            loan.getEndDate());
            item.put("totalInstallments",  loan.getTotalInstallments());
            double cuotaMensual;
            if (isGrande) {
                double capitalPendiente = Math.max(loan.getAmount() - paidCapital, 0);
                cuotaMensual = capitalPendiente * (1 + loan.getInterest() / 100.0);
            } else {
                cuotaMensual = loan.getInstallmentAmount();
            }
            item.put("installmentAmount", cuotaMensual);
            item.put("totalAPagar", totalAPagar);
            item.put("paidTotal", paidTotal);
            item.put("paidCapital", baseProgreso);
            item.put("saldo", saldo);
            item.put("porcentaje", Math.round(porcentaje));
            // Contar igual que el dashboard: registros con paymentType=capital (método/grande)
            // o paymentType=normal (préstamo normal) y monto positivo
            int cuotasPagadasInt;
            if (isGrande) {
                cuotasPagadasInt = (int) payments.stream()
                        .filter(p -> "capital".equals(p.getPaymentType()) && p.getAmount() > 0)
                        .count();
            } else if ("metodo".equals(loan.getLoanType())) {
                cuotasPagadasInt = (int) payments.stream()
                        .filter(p -> "capital".equals(p.getPaymentType()) && p.getAmount() > 0)
                        .count();
            } else {
                // Incluye devoluciones (monto negativo) para que reduzcan
                // las cuotas contadas como pagadas.
                double totalPagadoNormal = payments.stream()
                        .filter(p -> "normal".equals(p.getPaymentType()))
                        .mapToDouble(Payment::getAmount).sum();
                double cuotaFija = loan.getInstallmentAmount();
                cuotasPagadasInt = cuotaFija > 0 ? (int) Math.max(0, Math.round(totalPagadoNormal / cuotaFija)) : 0;
            }
            item.put("cuotasPagadas", cuotasPagadasInt);
            item.put("proximaCuota",       proximaCuota);
            item.put("ultimosPagos",       ultimosPagos);
            item.put("renovado",           loan.isRenovado());
            resultado.add(item);
        }

        // Nombre del prestatario del primer préstamo
        String nombre = loans.get(0).getBorrower();

        return ResponseEntity.ok(Map.of(
                "borrower", nombre,
                "phone",    normalizedPhone,
                "loans",    resultado
        ));
    }

    /**
     * POST /public/borrower/comprobante
     * El prestatario sube la foto de su comprobante de pago (Nequi, Daviplata, transferencia, etc.)
     * Queda guardado como "pendiente de verificación" y genera una notificación para el prestamista.
     * Body: { loanId, amount, note, imageBase64 }
     */
    @PostMapping("/comprobante")
    public ResponseEntity<?> subirComprobante(@RequestBody Map<String, Object> body) {
        String loanId = String.valueOf(body.get("loanId"));
        Optional<Loan> opt = loanRepo.findById(loanId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Préstamo no encontrado."));
        }
        String imageBase64 = (String) body.get("imageBase64");
        if (imageBase64 == null || imageBase64.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Falta la foto del comprobante."));
        }

        Loan loan = opt.get();

        PaymentProof proof = new PaymentProof();
        proof.setUserId(loan.getUserId());
        proof.setLoanId(loan.getId());
        proof.setBorrower(loan.getBorrower());
        proof.setPhone(loan.getPhone());
        try {
            proof.setAmount(body.get("amount") != null ? Double.parseDouble(String.valueOf(body.get("amount"))) : 0);
        } catch (NumberFormatException ignored) {}
        proof.setNote((String) body.getOrDefault("note", ""));
        proof.setImageBase64(imageBase64);
        proof.setEstado("pendiente");
        proof.setLeido(false);

        proofRepo.save(proof);

        return ResponseEntity.ok(Map.of("ok", true, "id", proof.getId()));
    }

}