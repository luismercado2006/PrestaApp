package com.diariopay.controller;

import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.PaymentRepository;
import com.diariopay.service.LoanStatusService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

// ─── Payments ────────────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/payments")
class PaymentController {

    @Autowired private PaymentRepository paymentRepo;
    @Autowired private LoanRepository    loanRepo;
    @Autowired private LoanStatusService loanStatusService;

    @PostMapping
    public ResponseEntity<?> addPayment(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        String loanId = toString(body.get("loanId"));
        Optional<Loan> opt = loanRepo.findById(loanId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Loan not found");

        Loan loan = opt.get();
        Payment p = new Payment();
        p.setUserId(uid);
        p.setLoanId(loan.getId());
        p.setAmount(toDouble(body.get("amount")));
        p.setNote((String) body.getOrDefault("note", ""));
        p.setPaymentType((String) body.getOrDefault("paymentType", "normal"));
        // Fecha del pago: si el front envía "date" (yyyy-MM-dd) usamos esa fecha
        // (con la hora actual, para no perder el orden cronológico dentro del
        // mismo día); si no la envía, o llega inválida, usamos el momento actual.
        p.setDate(resolvePaymentDate(body.get("date")));
        paymentRepo.save(p);

        // Recalculamos el estado real del préstamo (active | overdue | paid)
        // en base a las cuotas que ya quedaron pagadas, no solo si ya se
        // terminó de pagar. Así, si el cliente se pone al día, el préstamo
        // sale de mora automáticamente en vez de quedarse "overdue" para siempre.
        List<Payment> payments = paymentRepo.findByLoanIdAndArchivadoFalse(loanId);
        String nuevoEstado = loanStatusService.calcularEstadoActual(loan, payments);
        if (!nuevoEstado.equals(loan.getStatus())) {
            loan.setStatus(nuevoEstado);
            if ("active".equals(nuevoEstado)) {
                loan.setMoraNotificada(false);
            }
            loanRepo.save(loan);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Guarda el orden manual del historial de pagos de un préstamo.
     * Body esperado: { "loanId": "...", "order": ["idPago1", "idPago2", ...] }
     * El array "order" debe venir en el orden en que se quieren ver los pagos
     * (el primero de la lista queda arriba del todo en el historial).
     */
    @PutMapping("/reorder")
    public ResponseEntity<?> reorderPayments(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        String loanId = toString(body.get("loanId"));
        if (loanId == null) return ResponseEntity.badRequest().body("loanId es requerido");

        Optional<Loan> opt = loanRepo.findById(loanId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Loan not found");

        Object rawOrder = body.get("order");
        if (!(rawOrder instanceof List<?> orderList))
            return ResponseEntity.badRequest().body("order debe ser una lista de IDs de pagos");

        int index = 0;
        for (Object idObj : orderList) {
            String paymentId = toString(idObj);
            if (paymentId == null) { index++; continue; }
            Optional<Payment> pOpt = paymentRepo.findById(paymentId);
            if (pOpt.isPresent()
                    && loanId.equals(pOpt.get().getLoanId())
                    && uid.equals(pOpt.get().getUserId())) {
                Payment p = pOpt.get();
                p.setSortOrder(index);
                paymentRepo.save(p);
            }
            index++;
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private String toString(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * Convierte la fecha elegida por el usuario (String "yyyy-MM-dd") en un
     * LocalDateTime. Se conserva la hora actual para que, si se registran
     * varios pagos el mismo día, mantengan su orden real de creación.
     * Si no llega fecha, o llega en un formato inválido, se usa "ahora".
     */
    private LocalDateTime resolvePaymentDate(Object rawDate) {
        if (rawDate == null) return LocalDateTime.now();
        String value = rawDate.toString().trim();
        if (value.isEmpty()) return LocalDateTime.now();
        try {
            LocalDate fecha = LocalDate.parse(value); // espera "yyyy-MM-dd"
            return fecha.atTime(LocalTime.now());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}

// ─── Stats ───────────────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/stats")
class StatsController {

    @Autowired private LoanRepository    loanRepo;
    @Autowired private PaymentRepository paymentRepo;

    @GetMapping
    public ResponseEntity<?> getStats(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        List<Loan>    activeLoans = loanRepo.findByUserIdAndStatus(uid, "active");
        List<Payment> allPayments = paymentRepo.findByUserId(uid).stream()
                .filter(p -> !p.isArchivado())
                .toList();

        double totalLoaned    = activeLoans.stream().mapToDouble(Loan::getAmount).sum();
        double totalCollected = allPayments.stream().mapToDouble(Payment::getAmount).sum();
        double pending        = Math.max(totalLoaned - totalCollected, 0);
        long completed = loanRepo.countByUserIdAndStatus(uid, "paid");
        long overdue   = loanRepo.countByUserIdAndStatus(uid, "overdue");

        List<Payment> todayPay = allPayments.stream()
                .filter(p -> p.getDate() != null && p.getDate().isAfter(todayStart))
                .toList();
        double todayCollected = todayPay.stream().mapToDouble(Payment::getAmount).sum();

        List<Map<String, Object>> chart = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDateTime dayStart = todayStart.minusDays(i);
            LocalDateTime dayEnd   = dayStart.plusDays(1);
            double val = allPayments.stream()
                    .filter(p -> p.getDate() != null
                            && !p.getDate().isBefore(dayStart)
                            && p.getDate().isBefore(dayEnd))
                    .mapToDouble(Payment::getAmount).sum();
            chart.add(Map.of("label", dayLabel(dayStart, i), "value", val));
        }

        // ─── Total de plata (capital + interés) que falta por cobrar en TODOS
        // los préstamos activos/en mora, y total de interés que se gana este
        // mes en curso. Se recalculan en vivo en cada carga con los pagos
        // registrados hasta el momento, así que bajan solos cuando pagan y
        // el interés del mes "se renueva" automáticamente al cambiar de mes.
        List<Loan> loansNoPagados = loanRepo.findByUserId(uid).stream()
                .filter(l -> !"paid".equals(l.getStatus()))
                .toList();

        double saldoTotalConInteres = 0;
        double interesMesActual     = 0;
        for (Loan loan : loansNoPagados) {
            List<Payment> pagosPrestamo = paymentRepo.findByLoanIdAndArchivadoFalse(loan.getId());
            saldoTotalConInteres += calcularSaldoConInteres(loan, pagosPrestamo);
            interesMesActual     += calcularInteresMes(loan, pagosPrestamo);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalLoaned",          totalLoaned);
        resp.put("totalCollected",       totalCollected);
        resp.put("pending",              pending);
        resp.put("activeLoans",          (long) activeLoans.size());
        resp.put("completed",            completed);
        resp.put("overdue",              overdue);
        resp.put("todayCollected",       todayCollected);
        resp.put("todayPayments",        (long) todayPay.size());
        resp.put("chart",                chart);
        resp.put("saldoTotalConInteres", saldoTotalConInteres);
        resp.put("interesMesActual",     interesMesActual);
        return ResponseEntity.ok(resp);
    }

    /**
     * Cuánta plata falta por cobrar de un préstamo, incluyendo el interés
     * (no solo el capital). Usa la misma fórmula que la pantalla de detalle
     * para cada tipo de préstamo, así que coincide con lo que ve el usuario
     * ahí. Baja automáticamente a medida que se registran pagos.
     */
    private double calcularSaldoConInteres(Loan loan, List<Payment> pagos) {
        double paidTotal = pagos.stream().mapToDouble(Payment::getAmount).sum();
        String tipo = loan.getLoanType() != null ? loan.getLoanType() : "normal";

        switch (tipo) {
            case "grande": {
                // Revolvente: no tiene un "total" fijo (el interés se cobra
                // mes a mes sobre el saldo). Lo que realmente se adeuda ahora
                // es el capital pendiente más el interés del ciclo actual.
                double paidCapital = pagos.stream()
                        .filter(p -> "capital".equals(p.getPaymentType()) || "normal".equals(p.getPaymentType()))
                        .filter(p -> p.getAmount() > 0)
                        .mapToDouble(Payment::getAmount).sum();
                double saldoCapital = Math.max(loan.getAmount() - paidCapital, 0);
                double intMes = saldoCapital * loan.getInterest() / 100.0;
                return saldoCapital + intMes;
            }
            case "metodo": {
                double P = loan.getAmount();
                double r = loan.getInterest() / 100.0;
                int n = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
                double cuota = calcCuotaFija(P, r, n);
                double total = cuota * n;
                return Math.max(total - paidTotal, 0);
            }
            case "extra": {
                int n = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
                double total = loan.getInstallmentAmount() * n;
                return Math.max(total - paidTotal, 0);
            }
            default: {
                double total = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100.0);
                return Math.max(total - paidTotal, 0);
            }
        }
    }

    /**
     * Interés que corresponde al mes/ciclo actual de un préstamo (no
     * acumulado histórico). Como se recalcula en cada carga a partir del
     * estado actual del préstamo (saldo pendiente, cuotas pagadas, etc.),
     * automáticamente queda al día cuando entra un nuevo mes.
     */
    private double calcularInteresMes(Loan loan, List<Payment> pagos) {
        String tipo = loan.getLoanType() != null ? loan.getLoanType() : "normal";

        switch (tipo) {
            case "grande": {
                double paidCapital = pagos.stream()
                        .filter(p -> "capital".equals(p.getPaymentType()) || "normal".equals(p.getPaymentType()))
                        .filter(p -> p.getAmount() > 0)
                        .mapToDouble(Payment::getAmount).sum();
                double saldoCapital = Math.max(loan.getAmount() - paidCapital, 0);
                return saldoCapital * loan.getInterest() / 100.0;
            }
            case "metodo": {
                double P = loan.getAmount();
                double r = loan.getInterest() / 100.0;
                int n = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
                long cuotasCapPositivas = pagos.stream()
                        .filter(p -> "capital".equals(p.getPaymentType()) && p.getAmount() > 0)
                        .count();
                long cuotasCapRevertidas = pagos.stream()
                        .filter(p -> p.getAmount() < 0)
                        .count();
                int cuotasPagadas = (int) Math.max(cuotasCapPositivas - cuotasCapRevertidas, 0);
                double cuota = calcCuotaFija(P, r, n);
                double saldo = P;
                for (int i = 0; i < cuotasPagadas; i++) {
                    double intMes = saldo * r;
                    double capMes = cuota - intMes;
                    saldo = Math.max(saldo - capMes, 0);
                }
                return saldo * r;
            }
            case "extra": {
                int meses = Math.max(loan.getMonths(), 1);
                double totalInteres = loan.getAmount() * (loan.getInterest() / 100.0) * meses;
                return totalInteres / meses;
            }
            default: {
                // Interés mensual "plano" sobre el capital del préstamo.
                return loan.getAmount() * loan.getInterest() / 100.0;
            }
        }
    }

    private double calcCuotaFija(double P, double r, int n) {
        if (r == 0) return P / n;
        return P * r / (1 - Math.pow(1 + r, -n));
    }

    private String dayLabel(LocalDateTime day, int daysAgo) {
        if (daysAgo == 0) return "Hoy";
        if (daysAgo == 1) return "Ayer";
        String[] days = {"Lun","Mar","Mié","Jue","Vie","Sáb","Dom"};
        int dow = day.getDayOfWeek().getValue() - 1;
        return days[dow] + " " + day.getDayOfMonth();
    }
}

// ─── Notificaciones (comprobantes de pago) ────────────────────────────────────
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    @Autowired private com.diariopay.repository.PaymentProofRepository proofRepo;

    /** GET /api/notifications -> últimos comprobantes subidos por los prestatarios */
    @GetMapping
    public ResponseEntity<?> listar(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        List<com.diariopay.model.PaymentProof> proofs = proofRepo.findByUserIdOrderByFechaDesc(uid);
        long noLeidas = proofs.stream().filter(p -> !p.isLeido()).count();

        List<Map<String, Object>> items = proofs.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("loanId", p.getLoanId());
            m.put("borrower", p.getBorrower());
            m.put("phone", p.getPhone());
            m.put("amount", p.getAmount());
            m.put("note", p.getNote());
            m.put("imageBase64", p.getImageBase64());
            m.put("estado", p.getEstado());
            m.put("leido", p.isLeido());
            m.put("fecha", p.getFecha());
            return m;
        }).toList();

        return ResponseEntity.ok(Map.of("noLeidas", noLeidas, "items", items));
    }

    /** GET /api/notifications/count -> solo el conteo de no leídas (para el badge, liviano) */
    @GetMapping("/count")
    public ResponseEntity<?> contar(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");
        long noLeidas = proofRepo.countByUserIdAndLeidoFalse(uid);
        return ResponseEntity.ok(Map.of("noLeidas", noLeidas));
    }

    /** PUT /api/notifications/{id}/leido -> marca una notificación como vista */
    @PutMapping("/{id}/leido")
    public ResponseEntity<?> marcarLeido(@PathVariable String id, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<com.diariopay.model.PaymentProof> opt = proofRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("No encontrado");

        com.diariopay.model.PaymentProof p = opt.get();
        p.setLeido(true);
        proofRepo.save(p);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** PUT /api/notifications/leer-todas -> marca todas como vistas */
    @PutMapping("/leer-todas")
    public ResponseEntity<?> marcarTodasLeidas(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        List<com.diariopay.model.PaymentProof> pendientes = proofRepo.findByUserIdAndLeidoFalseOrderByFechaDesc(uid);
        pendientes.forEach(p -> p.setLeido(true));
        proofRepo.saveAll(pendientes);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}