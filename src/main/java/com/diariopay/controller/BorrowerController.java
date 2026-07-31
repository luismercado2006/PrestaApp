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
     * Fecha de vencimiento de la cuota número "i" (1-indexada) de un préstamo
     * "extra", usando la misma lógica que el panel de administración: la
     * última cuota siempre vence el mismo día que endDate.
     */
    private LocalDate fechaCuotaExtra(LocalDate start, String freq, int i,
                                      int weeklyIntervalDays, int totalInstallments, LocalDate endDate) {
        if (i >= totalInstallments && endDate != null) return endDate;
        return switch (freq != null ? freq : "daily") {
            case "weekly"   -> start.plusDays(i * (long) weeklyIntervalDays);
            case "biweekly" -> start.plusDays(i * 15L);
            case "monthly"  -> start.plusMonths(i);
            default         -> start.plusDays(i);
        };
    }

    /**
     * GET /public/borrower/{phone}
     * Devuelve todos los préstamos activos/en mora/pagados asociados al teléfono.
     */
    @GetMapping("/{phone}")
    public ResponseEntity<?> getLoansByPhone(@PathVariable String phone) {
        // Normalizar: quitar espacios, guiones, +
        String normalizedPhone = phone.replaceAll("[\\s\\-+]", "");

        // Antes esto hacía loanRepo.findAll() y filtraba en memoria, es decir
        // traía TODA la colección de préstamos (de TODOS los usuarios) en cada
        // consulta pública. Ahora se arman las variantes posibles del número
        // (con/sin prefijo 57) y se consulta directo por el índice de "phone",
        // así Mongo solo trae los préstamos que realmente coinciden.
        java.util.Set<String> variantes = new java.util.LinkedHashSet<>();
        variantes.add(normalizedPhone);
        variantes.add("57" + normalizedPhone);
        if (normalizedPhone.startsWith("57") && normalizedPhone.length() > 2) {
            variantes.add(normalizedPhone.substring(2));
        }

        List<Loan> loans = loanRepo.findByPhoneIn(new ArrayList<>(variantes)).stream()
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

            // ─── Interés de mora (aplica a préstamo "extra" y "grande") ─────
            // Misma fórmula que el panel de administración: el % de interés
            // del préstamo es una tasa MENSUAL (ej: 20% cada mes, por eso 2
            // meses acumulan 40%). Por eso la tasa diaria siempre se calcula
            // dividiendo entre 30 (días de un mes), sin importar cuántos
            // meses dure el plazo, y se aplica al capital por cada día de
            // atraso.
            double moraMonto = 0;
            long moraDias = 0;
            if ("extra".equals(loan.getLoanType()) && loan.getStartDate() != null) {
                int totalInstEx = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
                LocalDate endDateEx = loan.getEndDate();
                int wDays = loan.getWeeklyIntervalDaysOrDefault();

                long cuotasEsperadas = 0;
                for (int i = 1; i <= totalInstEx; i++) {
                    LocalDate fechaI = fechaCuotaExtra(loan.getStartDate(), loan.getFrequency(), i, wDays, totalInstEx, endDateEx);
                    if (!fechaI.isAfter(hoy)) cuotasEsperadas = i; else break;
                }
                long cuotasEnMora = Math.max(cuotasEsperadas - cuotasPagadasInt, 0);
                if (cuotasEnMora > 0) {
                    LocalDate fechaVieja = fechaCuotaExtra(loan.getStartDate(), loan.getFrequency(),
                            (int) cuotasPagadasInt + 1, wDays, totalInstEx, endDateEx);
                    moraDias = Math.max(ChronoUnit.DAYS.between(fechaVieja, hoy), 0);
                }

                double tasaDiariaMora = loan.getInterest() / 100.0 / 30.0;
                moraMonto = loan.getAmount() * tasaDiariaMora * moraDias;
            } else if (isGrande && loan.getEndDate() != null) {
                // "Grande": el crédito solo tiene una fecha de vencimiento
                // (mensual), así que la mora se mide contra endDate y se
                // aplica sobre el saldo de capital pendiente, no sobre el
                // monto original.
                LocalDate endDateGr = loan.getEndDate();
                if (hoy.isAfter(endDateGr)) {
                    moraDias = ChronoUnit.DAYS.between(endDateGr, hoy);
                    double saldoCapitalGr = Math.max(loan.getAmount() - paidCapital, 0);
                    double tasaDiariaMoraG = loan.getInterest() / 100.0 / 30.0;
                    moraMonto = saldoCapitalGr * tasaDiariaMoraG * moraDias;
                }
            }
            item.put("moraMonto", Math.round(moraMonto));
            item.put("moraDias", moraDias);

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