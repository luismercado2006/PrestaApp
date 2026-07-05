package com.diariopay.controller;

import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import com.diariopay.model.User;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.PaymentRepository;
import com.diariopay.repository.UserRepository;
import com.diariopay.service.WhatsAppService;
import com.diariopay.service.LoanStatusService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.diariopay.scheduler.MoraScheduler;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    @Autowired private LoanRepository    loanRepo;
    @Autowired private PaymentRepository paymentRepo;
    @Autowired private UserRepository    userRepo;
    @Autowired private WhatsAppService   whatsAppService;
    @Autowired private MoraScheduler     moraScheduler;
    @Autowired private LoanStatusService loanStatusService;

    // ─── Detecta mora en tiempo real (y también saca de mora si ya está al día)
    // y envía WhatsApp con nombre del prestamista solo cuando entra en mora.
    private void detectarMoraEnTiempoReal(List<Loan> prestamos) {
        for (Loan loan : prestamos) {
            if ("paid".equals(loan.getStatus())) continue;

            List<Payment> payments = paymentRepo.findByLoanIdAndArchivadoFalse(loan.getId());
            String estadoAnterior = loan.getStatus();
            String nuevoEstado    = loanStatusService.calcularEstadoActual(loan, payments);

            if (nuevoEstado.equals(estadoAnterior)) continue;

            loan.setStatus(nuevoEstado);

            if ("active".equals(nuevoEstado)) {
                // El cliente se puso al día: reseteamos el aviso para que, si
                // vuelve a caer en mora más adelante, se le notifique de nuevo.
                loan.setMoraNotificada(false);
                loanRepo.save(loan);
                System.out.println("✅ Préstamo de " + loan.getBorrower() + " vuelve a estar AL DÍA");
                continue;
            }

            loanRepo.save(loan);

            if ("overdue".equals(nuevoEstado)
                    && !loan.isMoraNotificada()
                    && loan.getPhone() != null
                    && !loan.getPhone().isBlank()) {

                // Obtener nombre real del prestamista
                String nombrePrestamista = userRepo.findById(loan.getUserId())
                        .map(User::getName)
                        .filter(n -> n != null && !n.isBlank())
                        .orElse("DiarioPay");

                try {
                    whatsAppService.enviarMensajeMora(
                            loan.getPhone(),
                            loan.getBorrower(),
                            loan.getAmount(),
                            nombrePrestamista
                    );
                    loan.setMoraNotificada(true);
                    loanRepo.save(loan);
                    System.out.println("✅ Mora en tiempo real → WhatsApp enviado a " + loan.getBorrower() + " de parte de " + nombrePrestamista);
                } catch (Exception e) {
                    System.err.println("❌ Error WhatsApp mora: " + e.getMessage());
                }
            }
        }
    }

    @GetMapping
    public ResponseEntity<?> getLoans(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        List<Loan> loans = loanRepo.findByUserIdOrderByCreatedAtDesc(uid);
        detectarMoraEnTiempoReal(loans);
        return ResponseEntity.ok(loanRepo.findByUserIdOrderByCreatedAtDesc(uid));
    }

    @GetMapping("/test-mora")
    public ResponseEntity<?> testMora(HttpSession session) {
        moraScheduler.verificarPrestamosEnMora();
        return ResponseEntity.ok("Verificación ejecutada, revisa los logs del servidor");
    }

    @PostMapping
    public ResponseEntity<?> createLoan(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Loan loan = new Loan();
        loan.setUserId(uid);
        loan.setBorrower((String) body.get("borrower"));
        loan.setAmount(toDouble(body.get("amount")));
        loan.setInterest(toDouble(body.get("interest")));
        loan.setFrequency((String) body.getOrDefault("frequency", "daily"));
        loan.setLoanType((String) body.getOrDefault("loanType", "normal"));
        loan.setNotes((String) body.getOrDefault("notes", ""));
        String phoneVal = (String) body.getOrDefault("phone", "");
        if (phoneVal == null || phoneVal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "El número de teléfono es obligatorio"
            ));
        }
        loan.setPhone(phoneVal.trim());
        String rutaVal = (String) body.getOrDefault("ruta", "");
        loan.setRuta(rutaVal != null ? rutaVal.trim() : "");
        loan.setStatus("active");

        LocalDate startDate = LocalDate.parse((String) body.getOrDefault("startDate", LocalDate.now().toString()));
        LocalDate endDate   = LocalDate.parse((String) body.getOrDefault("endDate",   LocalDate.now().plusDays(30).toString()));
        loan.setStartDate(startDate);
        loan.setEndDate(endDate);
        loan.setDueDate(endDate.atStartOfDay());
        loan.setCreatedAt(startDate.atStartOfDay());

        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);
        String freq     = loan.getFrequency();
        String loanType = loan.getLoanType();

        int totalInstallments;
        if ("metodo".equals(loanType) && body.containsKey("numMonths")) {
            totalInstallments = toInt(body.get("numMonths"));
        } else {
            // floor (no ceil): así la última cuota (start + n*periodo) nunca cae
            // después de la fecha de fin que el usuario eligió.
            totalInstallments = switch (freq) {
                case "weekly"  -> (int) Math.floor(daysBetween / 7.0);
                case "monthly" -> (int) Math.round(daysBetween / 30.4375);
                default        -> (int) daysBetween;
            };
        }
        if (totalInstallments < 1) totalInstallments = 1;
        double installmentAmount;
        if ("metodo".equals(loanType)) {
            // Amortización francesa — misma fórmula que el frontend del dashboard
            // r = interés mensual directo (ej: 20% -> 0.20), n = número de cuotas
            // C = P * r / (1 - (1+r)^-n)
            double r = loan.getInterest() / 100.0;
            if (r == 0) {
                installmentAmount = loan.getAmount() / totalInstallments;
            } else {
                installmentAmount = loan.getAmount() * r
                        / (1 - Math.pow(1 + r, -totalInstallments));
            }
        } else {
            double totalConInteres = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100);
            installmentAmount = totalConInteres / totalInstallments;
        }
        loan.setTotalInstallments(totalInstallments);
        loan.setInstallmentAmount(installmentAmount);

        loanRepo.save(loan);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "id", loan.getId(),
                "totalInstallments", totalInstallments,
                "installmentAmount", installmentAmount
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getLoan(@PathVariable String id, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<Loan> opt = loanRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Not found");

        Loan loan = opt.get();
        detectarMoraEnTiempoReal(List.of(loan));

        List<Payment> payments = paymentRepo.findByLoanIdAndArchivadoFalse(id);
        double paidTotal    = payments.stream().mapToDouble(Payment::getAmount).sum();
        double paidInterest = payments.stream()
                .filter(p -> "interest".equals(p.getPaymentType()))
                .mapToDouble(Payment::getAmount).sum();
        double paidCapital = payments.stream()
                .filter(p -> "capital".equals(p.getPaymentType()) || "normal".equals(p.getPaymentType()))
                .filter(p -> p.getAmount() > 0)
                .mapToDouble(Payment::getAmount).sum();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id",                loan.getId());
        resp.put("borrower",          loan.getBorrower());
        resp.put("phone",             loan.getPhone());
        resp.put("amount",            loan.getAmount());
        resp.put("interest",          loan.getInterest());
        resp.put("frequency",         loan.getFrequency());
        resp.put("status",            loan.getStatus());
        resp.put("notes",             loan.getNotes());
        resp.put("createdAt",         loan.getCreatedAt());
        resp.put("dueDate",           loan.getDueDate());
        resp.put("payments",          payments);
        resp.put("paidTotal",         paidTotal);
        resp.put("paidInterest",      paidInterest);
        resp.put("paidCapital",       paidCapital);
        resp.put("totalInstallments", loan.getTotalInstallments());
        resp.put("installmentAmount", loan.getInstallmentAmount());
        resp.put("startDate",         loan.getStartDate());
        resp.put("endDate",           loan.getEndDate());
        resp.put("loanType",          loan.getLoanType() != null ? loan.getLoanType() : "normal");
        resp.put("renovado",          loan.isRenovado());
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateLoan(@PathVariable String id,
                                        @RequestBody Map<String, Object> body,
                                        HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<Loan> opt = loanRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Not found");

        Loan loan = opt.get();
        if (body.containsKey("status"))  loan.setStatus((String) body.get("status"));
        if (body.containsKey("notes"))   loan.setNotes((String) body.get("notes"));
        if (body.containsKey("amount"))  loan.setAmount(toDouble(body.get("amount")));
        if (body.containsKey("phone")) {
            String newPhone = (String) body.get("phone");
            if (newPhone == null || newPhone.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "ok", false,
                        "error", "El número de teléfono es obligatorio"
                ));
            }
            loan.setPhone(newPhone.trim());
        }
        if (body.containsKey("endDate")) {
            LocalDate newEnd = LocalDate.parse((String) body.get("endDate"));
            loan.setEndDate(newEnd);
            loan.setDueDate(newEnd.atStartOfDay());
        }
        loanRepo.save(loan);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteLoan(@PathVariable String id, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<Loan> opt = loanRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Not found");

        paymentRepo.findByLoanId(id).forEach(p -> paymentRepo.deleteById(p.getId()));
        loanRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
    // ─── RENOVACIÓN DE CRÉDITO ──────────────────────────────────────────────

    @PostMapping("/{id}/renovar")
    public ResponseEntity<?> renovarCredito(@PathVariable String id,
                                            @RequestBody Map<String, Object> body,
                                            HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<Loan> opt = loanRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Not found");

        Loan loan = opt.get();

        double nuevoMonto      = toDouble(body.get("amount"));
        double nuevoPorcentaje = toDouble(body.get("interest"));
        if (nuevoMonto <= 0) {
            return ResponseEntity.badRequest().body("El monto a ingresar debe ser mayor a 0");
        }

        Loan.RenovacionSnapshot snap = new Loan.RenovacionSnapshot();
        snap.setAmount(loan.getAmount());
        snap.setInterest(loan.getInterest());
        snap.setFrequency(loan.getFrequency());
        snap.setLoanType(loan.getLoanType());
        snap.setStatus(loan.getStatus());
        snap.setCreatedAt(loan.getCreatedAt());
        snap.setDueDate(loan.getDueDate());
        snap.setStartDate(loan.getStartDate());
        snap.setEndDate(loan.getEndDate());
        snap.setTotalInstallments(loan.getTotalInstallments());
        snap.setInstallmentAmount(loan.getInstallmentAmount());
        snap.setMoraNotificada(loan.isMoraNotificada());
        loan.setSnapshotAnterior(snap);

        List<Payment> pagosActuales = paymentRepo.findByLoanIdAndArchivadoFalse(id);
        for (Payment p : pagosActuales) {
            p.setArchivado(true);
            paymentRepo.save(p);
        }

        LocalDate hoy = LocalDate.now();
        String loanType = loan.getLoanType() != null ? loan.getLoanType() : "normal";

// Leer nuevos campos del frontend si vienen, si no usar los del préstamo original
        String freq = body.containsKey("frequency")
                ? (String) body.get("frequency")
                : (loan.getFrequency() != null ? loan.getFrequency() : "daily");

        LocalDate nuevaFechaFin;
        if (body.containsKey("endDate")) {
            nuevaFechaFin = LocalDate.parse((String) body.get("endDate"));
        } else {
            long diasOriginal = (loan.getStartDate() != null && loan.getEndDate() != null)
                    ? ChronoUnit.DAYS.between(loan.getStartDate(), loan.getEndDate())
                    : 30;
            if (diasOriginal < 1) diasOriginal = 30;
            nuevaFechaFin = hoy.plusDays(diasOriginal);
        }

        int totalInstallments;
        if ("metodo".equals(loanType)) {
            totalInstallments = body.containsKey("totalInstallments")
                    ? toInt(body.get("totalInstallments"))
                    : (loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1);
        } else {
            long dias = ChronoUnit.DAYS.between(hoy, nuevaFechaFin);
            // floor (no ceil): consistente con la creación, evita que la última cuota
            // caiga después de la nueva fecha de fin.
            totalInstallments = switch (freq) {
                case "weekly"  -> (int) Math.floor(dias / 7.0);
                case "monthly" -> (int) Math.round(dias / 30.4375);
                default        -> (int) dias;
            };
        }
        if (totalInstallments < 1) totalInstallments = 1;

        double installmentAmount;
        if ("metodo".equals(loanType)) {
            double r = nuevoPorcentaje / 100.0;
            if (r == 0) {
                installmentAmount = nuevoMonto / totalInstallments;
            } else {
                installmentAmount = nuevoMonto * r / (1 - Math.pow(1 + r, -totalInstallments));
            }
        } else {
            double totalConInteres = nuevoMonto + (nuevoMonto * nuevoPorcentaje / 100);
            installmentAmount = totalConInteres / totalInstallments;
        }

        loan.setAmount(nuevoMonto);
        loan.setInterest(nuevoPorcentaje);
        loan.setStartDate(hoy);
        loan.setEndDate(nuevaFechaFin);
        loan.setFrequency(freq);
        loan.setCreatedAt(hoy.atStartOfDay());
        loan.setDueDate(nuevaFechaFin.atStartOfDay());
        loan.setTotalInstallments(totalInstallments);
        loan.setInstallmentAmount(installmentAmount);
        loan.setStatus("active");
        loan.setMoraNotificada(false);
        loan.setRenovado(true);

        loanRepo.save(loan);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "totalInstallments", totalInstallments,
                "installmentAmount", installmentAmount
        ));
    }

    @PostMapping("/{id}/deshacer-renovacion")
    public ResponseEntity<?> deshacerRenovacion(@PathVariable String id, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<Loan> opt = loanRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Not found");

        Loan loan = opt.get();
        if (!loan.isRenovado() || loan.getSnapshotAnterior() == null) {
            return ResponseEntity.badRequest().body("Este préstamo no tiene una renovación para deshacer");
        }

        List<Payment> pagosDelCicloRenovado = paymentRepo.findByLoanIdAndArchivadoFalse(id);
        for (Payment p : pagosDelCicloRenovado) {
            paymentRepo.deleteById(p.getId());
        }

        List<Payment> pagosArchivados = paymentRepo.findByLoanIdAndArchivadoTrue(id);
        for (Payment p : pagosArchivados) {
            p.setArchivado(false);
            paymentRepo.save(p);
        }

        Loan.RenovacionSnapshot snap = loan.getSnapshotAnterior();
        loan.setAmount(snap.getAmount());
        loan.setInterest(snap.getInterest());
        loan.setFrequency(snap.getFrequency());
        loan.setLoanType(snap.getLoanType());
        loan.setStatus(snap.getStatus());
        loan.setCreatedAt(snap.getCreatedAt());
        loan.setDueDate(snap.getDueDate());
        loan.setStartDate(snap.getStartDate());
        loan.setEndDate(snap.getEndDate());
        loan.setTotalInstallments(snap.getTotalInstallments());
        loan.setInstallmentAmount(snap.getInstallmentAmount());
        loan.setMoraNotificada(snap.isMoraNotificada());
        loan.setRenovado(false);
        loan.setSnapshotAnterior(null);

        loanRepo.save(loan);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─── RUTAS ─────────────────────────────────────────────────────────────

    // ─── RUTAS ─────────────────────────────────────────────────────────────

    /** Lista los nombres de rutas distintas que tiene el usuario */
    @GetMapping("/rutas")
    public ResponseEntity<?> getRutas(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        List<Loan> loans = loanRepo.findByUserIdConRuta(uid);
        List<String> rutas = loans.stream()
                .map(Loan::getRuta)
                .filter(r -> r != null && !r.isBlank())
                .distinct()
                .sorted()
                .toList();
        return ResponseEntity.ok(rutas);
    }

    /** Préstamos activos de una ruta específica con cuota diaria de hoy */
    @GetMapping("/rutas/{nombre}")
    public ResponseEntity<?> getPrestamosPorRuta(@PathVariable String nombre, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        List<Loan> loans = loanRepo.findByUserIdAndRutaOrderByCreatedAtDesc(uid, nombre);
        detectarMoraEnTiempoReal(loans);

        LocalDate hoy = LocalDate.now();
        double totalHoy = 0;
        List<Map<String, Object>> resultado = new ArrayList<>();

        for (Loan loan : loans) {
            if (!"active".equals(loan.getStatus()) && !"overdue".equals(loan.getStatus())) continue;

            double cuotaHoy = 0;
            if (loan.getStartDate() != null && !hoy.isBefore(loan.getStartDate())) {
                String freq = loan.getFrequency() != null ? loan.getFrequency() : "daily";
                boolean aplica = switch (freq) {
                    case "weekly"  -> loan.getStartDate().until(hoy).getDays() % 7 == 0;
                    case "monthly" -> loan.getStartDate().getDayOfMonth() == hoy.getDayOfMonth();
                    default        -> true;
                };
                if (aplica) {
                    cuotaHoy = loan.getInstallmentAmount();
                    totalHoy += cuotaHoy;
                }
            }

            List<com.diariopay.model.Payment> pagos = paymentRepo.findByLoanIdAndArchivadoFalse(loan.getId());
            double pagado = pagos.stream().mapToDouble(com.diariopay.model.Payment::getAmount).sum();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",                loan.getId());
            item.put("borrower",          loan.getBorrower());
            item.put("amount",            loan.getAmount());
            item.put("interest",          loan.getInterest());
            item.put("frequency",         loan.getFrequency());
            item.put("loanType",          loan.getLoanType() != null ? loan.getLoanType() : "normal");
            item.put("status",            loan.getStatus());
            item.put("startDate",         loan.getStartDate());
            item.put("endDate",           loan.getEndDate());
            item.put("installmentAmount", loan.getInstallmentAmount());
            item.put("cuotaHoy",          cuotaHoy);
            item.put("pagado",            pagado);
            resultado.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ruta",      nombre);
        resp.put("prestamos", resultado);
        resp.put("totalHoy",  totalHoy);
        return ResponseEntity.ok(resp);
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }
}