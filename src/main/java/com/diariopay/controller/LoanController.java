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
import java.time.YearMonth;
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

        String freq     = loan.getFrequency();
        String loanType = loan.getLoanType();

        // ─── PRÉSTAMO EXTRA ──────────────────────────────────────────────
        // Interés fijo total = monto * interés% * meses (no compuesto).
        // Ese total se reparte entre las cuotas según la frecuencia elegida.
        if ("extra".equals(loanType)) {
            int months = toInt(body.getOrDefault("months", 1));
            if (months < 1) months = 1;
            if (months > 12) months = 12;
            loan.setMonths(months);

            LocalDate calculatedEnd = startDate.plusMonths(months);
            loan.setEndDate(calculatedEnd);
            loan.setDueDate(calculatedEnd.atStartOfDay());

            long dias = ChronoUnit.DAYS.between(startDate, calculatedEnd);

            Integer cuotasSemanalesExtra = body.containsKey("cuotasSemanales")
                    ? toInt(body.get("cuotasSemanales")) : null;
            Integer weeklyIntervalDaysExtra = null;
            int totalInstallmentsExtra;
            if ("weekly".equals(freq) && cuotasSemanalesExtra != null && cuotasSemanalesExtra > 0) {
                // El usuario eligió 4 o 5 cuotas por mes: el total de cuotas es
                // esa cantidad multiplicada por los meses del préstamo.
                int n = cuotasSemanalesExtra >= 5 ? 5 : 4;
                totalInstallmentsExtra = n * months;
                weeklyIntervalDaysExtra = (int) Math.max(1, Math.floor(dias / (double) totalInstallmentsExtra));
            } else {
                totalInstallmentsExtra = switch (freq) {
                    case "weekly"   -> (int) Math.floor(dias / 7.0);
                    case "biweekly" -> (int) Math.floor(dias / 15.0);
                    case "monthly"  -> months;
                    default         -> (int) dias; // daily
                };
            }
            if (totalInstallmentsExtra < 1) totalInstallmentsExtra = 1;

            double totalInteres = loan.getAmount() * (loan.getInterest() / 100.0) * months;
            double totalAPagarExtra = loan.getAmount() + totalInteres;
            double installmentAmountExtra = totalAPagarExtra / totalInstallmentsExtra;

            loan.setTotalInstallments(totalInstallmentsExtra);
            loan.setInstallmentAmount(installmentAmountExtra);
            loan.setWeeklyIntervalDays(weeklyIntervalDaysExtra);

            loanRepo.save(loan);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "id", loan.getId(),
                    "totalInstallments", totalInstallmentsExtra,
                    "installmentAmount", installmentAmountExtra
            ));
        }

        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);

        int totalInstallments;
        Integer weeklyIntervalDays = null;
        Integer cuotasSemanales = body.containsKey("cuotasSemanales")
                ? toInt(body.get("cuotasSemanales")) : null;
        if ("metodo".equals(loanType) && body.containsKey("numMonths")) {
            totalInstallments = toInt(body.get("numMonths"));
        } else if ("weekly".equals(freq) && cuotasSemanales != null && cuotasSemanales > 0) {
            // El usuario eligió 4 o 5 cuotas dentro del mes en vez de la cadencia
            // clásica de 7 días: el intervalo se ajusta para que todas las cuotas
            // caigan dentro del período elegido sin pasarse de la fecha de fin.
            int n = cuotasSemanales >= 5 ? 5 : 4;
            weeklyIntervalDays = (int) Math.max(1, Math.floor(daysBetween / (double) n));
            totalInstallments = n;
        } else {
            // floor (no ceil): así la última cuota (start + n*periodo) nunca cae
            // después de la fecha de fin que el usuario eligió.
            totalInstallments = switch (freq) {
                case "weekly"   -> (int) Math.floor(daysBetween / 7.0);
                case "biweekly" -> (int) Math.floor(daysBetween / 15.0);
                case "monthly"  -> (int) Math.round(daysBetween / 30.4375);
                default         -> (int) daysBetween;
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
        loan.setWeeklyIntervalDays(weeklyIntervalDays);

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

        List<Payment> payments = ordenarPagos(paymentRepo.findByLoanIdAndArchivadoFalse(id));
        double paidTotal    = payments.stream().mapToDouble(Payment::getAmount).sum();
        double paidInterest = payments.stream()
                .filter(p -> "interest".equals(p.getPaymentType()))
                .mapToDouble(Payment::getAmount).sum();
        // Incluye devoluciones (montos negativos) para que reduzcan el
        // capital pagado real, igual que en LoanStatusService.
        double paidCapital = payments.stream()
                .filter(p -> "capital".equals(p.getPaymentType()) || "normal".equals(p.getPaymentType()))
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
        resp.put("weeklyIntervalDays", loan.getWeeklyIntervalDaysOrDefault());
        resp.put("startDate",         loan.getStartDate());
        resp.put("endDate",           loan.getEndDate());
        resp.put("loanType",          loan.getLoanType() != null ? loan.getLoanType() : "normal");
        resp.put("months",            loan.getMonths());
        resp.put("renovado",          loan.isRenovado());
        resp.put("ruta",              loan.getRuta());
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
        snap.setMonths(loan.getMonths());
        snap.setWeeklyIntervalDays(loan.getWeeklyIntervalDays());
        loan.setSnapshotAnterior(snap);

        List<Payment> pagosActuales = paymentRepo.findByLoanIdAndArchivadoFalse(id);
        for (Payment p : pagosActuales) {
            p.setArchivado(true);
            paymentRepo.save(p);
        }

        // Fecha de inicio de la renovación: si el usuario elige una, se usa esa;
        // si no viene (frontend viejo/cacheado), se usa hoy como antes.
        LocalDate hoy = body.containsKey("startDate") && body.get("startDate") != null
                ? LocalDate.parse((String) body.get("startDate"))
                : LocalDate.now();
        String loanType = loan.getLoanType() != null ? loan.getLoanType() : "normal";

// Leer nuevos campos del frontend si vienen, si no usar los del préstamo original
        String freq = body.containsKey("frequency")
                ? (String) body.get("frequency")
                : (loan.getFrequency() != null ? loan.getFrequency() : "daily");

        // ─── RENOVACIÓN DE PRÉSTAMO EXTRA ─────────────────────────────────
        if ("extra".equals(loanType)) {
            int months = toInt(body.getOrDefault("months", loan.getMonths() > 0 ? loan.getMonths() : 1));
            if (months < 1) months = 1;
            if (months > 12) months = 12;

            LocalDate nuevaFechaFinExtra = hoy.plusMonths(months);
            long dias = ChronoUnit.DAYS.between(hoy, nuevaFechaFinExtra);

            Integer cuotasSemanalesExtra = body.containsKey("cuotasSemanales")
                    ? toInt(body.get("cuotasSemanales")) : null;
            Integer weeklyIntervalDaysExtra = null;
            int totalInstallmentsExtra;
            if ("weekly".equals(freq) && cuotasSemanalesExtra != null && cuotasSemanalesExtra > 0) {
                int n = cuotasSemanalesExtra >= 5 ? 5 : 4;
                totalInstallmentsExtra = n * months;
                weeklyIntervalDaysExtra = (int) Math.max(1, Math.floor(dias / (double) totalInstallmentsExtra));
            } else {
                totalInstallmentsExtra = switch (freq) {
                    case "weekly"   -> (int) Math.floor(dias / 7.0);
                    case "biweekly" -> (int) Math.floor(dias / 15.0);
                    case "monthly"  -> months;
                    default         -> (int) dias;
                };
            }
            if (totalInstallmentsExtra < 1) totalInstallmentsExtra = 1;

            double totalInteresExtra = nuevoMonto * (nuevoPorcentaje / 100.0) * months;
            double installmentAmountExtra = (nuevoMonto + totalInteresExtra) / totalInstallmentsExtra;

            loan.setAmount(nuevoMonto);
            loan.setInterest(nuevoPorcentaje);
            loan.setStartDate(hoy);
            loan.setEndDate(nuevaFechaFinExtra);
            loan.setFrequency(freq);
            loan.setMonths(months);
            loan.setCreatedAt(hoy.atStartOfDay());
            loan.setDueDate(nuevaFechaFinExtra.atStartOfDay());
            loan.setTotalInstallments(totalInstallmentsExtra);
            loan.setInstallmentAmount(installmentAmountExtra);
            loan.setWeeklyIntervalDays(weeklyIntervalDaysExtra);
            loan.setStatus("active");
            loan.setMoraNotificada(false);
            loan.setRenovado(true);

            loanRepo.save(loan);

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "totalInstallments", totalInstallmentsExtra,
                    "installmentAmount", installmentAmountExtra
            ));
        }

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
        Integer weeklyIntervalDays = null;
        Integer cuotasSemanales = body.containsKey("cuotasSemanales")
                ? toInt(body.get("cuotasSemanales")) : null;
        if ("metodo".equals(loanType)) {
            totalInstallments = body.containsKey("totalInstallments")
                    ? toInt(body.get("totalInstallments"))
                    : (loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1);
        } else if ("weekly".equals(freq) && cuotasSemanales != null && cuotasSemanales > 0) {
            long dias = ChronoUnit.DAYS.between(hoy, nuevaFechaFin);
            int n = cuotasSemanales >= 5 ? 5 : 4;
            weeklyIntervalDays = (int) Math.max(1, Math.floor(dias / (double) n));
            totalInstallments = n;
        } else {
            long dias = ChronoUnit.DAYS.between(hoy, nuevaFechaFin);
            // floor (no ceil): consistente con la creación, evita que la última cuota
            // caiga después de la nueva fecha de fin.
            totalInstallments = switch (freq) {
                case "weekly"   -> (int) Math.floor(dias / 7.0);
                case "biweekly" -> (int) Math.floor(dias / 15.0);
                case "monthly"  -> (int) Math.round(dias / 30.4375);
                default         -> (int) dias;
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
        loan.setWeeklyIntervalDays(weeklyIntervalDays);
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
        loan.setMonths(snap.getMonths());
        loan.setWeeklyIntervalDays(snap.getWeeklyIntervalDays());
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

    /**
     * Préstamos activos de una ruta específica con cuota diaria de hoy.
     * "mes" (1-12) y "anio" son opcionales: permiten consultar el recaudo
     * ("totalMes") de un mes distinto al actual. Si no vienen, se usa el
     * mes/año de hoy (comportamiento original).
     */
    @GetMapping("/rutas/{nombre}")
    public ResponseEntity<?> getPrestamosPorRuta(@PathVariable String nombre,
                                                 @RequestParam(required = false) Integer mes,
                                                 @RequestParam(required = false) Integer anio,
                                                 HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        List<Loan> loans = loanRepo.findByUserIdAndRutaOrderByCreatedAtDesc(uid, nombre);
        detectarMoraEnTiempoReal(loans);

        LocalDate hoy = LocalDate.now();
        int mesSeleccionado  = (mes  != null && mes  >= 1 && mes <= 12) ? mes  : hoy.getMonthValue();
        int anioSeleccionado = (anio != null && anio >  0)              ? anio : hoy.getYear();
        YearMonth mesConsultado = YearMonth.of(anioSeleccionado, mesSeleccionado);
        double totalHoy = 0;
        double totalMes = 0;
        double totalRecaudado = 0;
        List<Map<String, Object>> resultado = new ArrayList<>();

        for (Loan loan : loans) {
            // Pagos de este préstamo: se toman en cuenta para los totales de la ruta
            // (todos los préstamos, sin importar su estado: activos, en mora o ya pagados)
            // y también para el detalle individual (que solo lista activos/mora).
            List<com.diariopay.model.Payment> pagosLoan = paymentRepo.findByLoanIdAndArchivadoFalse(loan.getId());
            double pagadoLoan = 0;
            for (com.diariopay.model.Payment p : pagosLoan) {
                pagadoLoan += p.getAmount();
                totalRecaudado += p.getAmount();
                if (p.getDate() != null && YearMonth.from(p.getDate()).equals(mesConsultado)) {
                    totalMes += p.getAmount();
                }
            }

            if (!"active".equals(loan.getStatus()) && !"overdue".equals(loan.getStatus())) continue;

            double cuotaHoy = 0;
            if (loan.getStartDate() != null && !hoy.isBefore(loan.getStartDate())) {
                String freq = loan.getFrequency() != null ? loan.getFrequency() : "daily";
                long diasTranscurridos = ChronoUnit.DAYS.between(loan.getStartDate(), hoy);
                boolean aplica = switch (freq) {
                    // > 0 para no contar el día de creación como si ya tocara cuota
                    case "weekly"   -> diasTranscurridos > 0 && diasTranscurridos % loan.getWeeklyIntervalDaysOrDefault() == 0;
                    case "biweekly" -> diasTranscurridos > 0 && diasTranscurridos % 15 == 0;
                    case "monthly"  -> {
                        // Si el préstamo arranca el 29/30/31 y el mes actual no tiene
                        // ese día (ej: febrero), la cuota cae en el último día del mes.
                        int diaInicio = loan.getStartDate().getDayOfMonth();
                        int diaEsperado = Math.min(diaInicio, hoy.lengthOfMonth());
                        yield diasTranscurridos > 0 && hoy.getDayOfMonth() == diaEsperado;
                    }
                    default         -> true;
                };
                if (aplica) {
                    cuotaHoy = loan.getInstallmentAmount();
                    totalHoy += cuotaHoy;
                }
            }

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
            item.put("pagado",            pagadoLoan);
            resultado.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ruta",              nombre);
        resp.put("prestamos",         resultado);
        resp.put("totalHoy",          totalHoy);
        resp.put("totalMes",          totalMes);
        resp.put("totalRecaudado",    totalRecaudado);
        resp.put("mesConsultado",     mesSeleccionado);
        resp.put("anioConsultado",    anioSeleccionado);
        return ResponseEntity.ok(resp);
    }

    /**
     * Ordena el historial de pagos para mostrarlo (el primero de la lista
     * queda arriba del todo). Si el usuario ya reordenó manualmente algún
     * pago del préstamo (sortOrder != null), se respeta ese orden manual.
     * Si no, se ordena automáticamente por fecha, del más reciente al más
     * antiguo (comportamiento por defecto).
     */
    private List<Payment> ordenarPagos(List<Payment> payments) {
        boolean tieneOrdenManual = payments.stream().anyMatch(p -> p.getSortOrder() != null);
        if (tieneOrdenManual) {
            return payments.stream()
                    .sorted(Comparator.comparing(
                            (Payment p) -> p.getSortOrder() != null ? p.getSortOrder() : Integer.MAX_VALUE))
                    .toList();
        }
        return payments.stream()
                .sorted(Comparator.comparing(
                        (Payment p) -> p.getDate() != null ? p.getDate() : java.time.LocalDateTime.MIN,
                        Comparator.reverseOrder()))
                .toList();
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