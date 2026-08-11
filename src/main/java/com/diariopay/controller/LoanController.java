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
import java.time.LocalDateTime;
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

    // ─── Trae de una sola vez los pagos de todos los préstamos de la lista,
    // agrupados por loanId, para no consultar Mongo préstamo por préstamo
    // (problema N+1) en los bucles que recorren una ruta completa.
    private Map<String, List<Payment>> pagosPorPrestamo(List<Loan> loans) {
        List<String> ids = loans.stream().map(Loan::getId).toList();
        if (ids.isEmpty()) return Map.of();
        return paymentRepo.findByLoanIdInAndArchivadoFalse(ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(Payment::getLoanId));
    }

    // ─── Detecta mora en tiempo real (y también saca de mora si ya está al día)
    // y envía WhatsApp con nombre del prestamista solo cuando entra en mora.
    private void detectarMoraEnTiempoReal(List<Loan> prestamos) {
        detectarMoraEnTiempoReal(prestamos, pagosPorPrestamo(prestamos));
    }

    // Variante que reutiliza un mapa de pagos ya cargado (evita volver a
    // consultar Mongo cuando el caller ya trajo los pagos de antemano).
    private void detectarMoraEnTiempoReal(List<Loan> prestamos, Map<String, List<Payment>> pagosPorLoan) {
        // Antes cada cambio de estado hacía su propio loanRepo.save() (hasta 3
        // viajes de ida y vuelta a Mongo por préstamo, uno por uno). Ahora los
        // préstamos modificados se acumulan aquí y se guardan todos juntos con
        // un solo saveAll() al final, sin importar cuántos cambien de estado.
        List<Loan> cambiados = new ArrayList<>();

        for (Loan loan : prestamos) {
            if ("paid".equals(loan.getStatus())) continue;

            List<Payment> payments = pagosPorLoan.getOrDefault(loan.getId(), List.of());
            String estadoAnterior = loan.getStatus();
            String nuevoEstado    = loanStatusService.calcularEstadoActual(loan, payments);

            if (nuevoEstado.equals(estadoAnterior)) continue;

            loan.setStatus(nuevoEstado);
            cambiados.add(loan);

            if ("active".equals(nuevoEstado)) {
                // El cliente se puso al día: reseteamos el aviso para que, si
                // vuelve a caer en mora más adelante, se le notifique de nuevo.
                loan.setMoraNotificada(false);
                System.out.println("✅ Préstamo de " + loan.getBorrower() + " vuelve a estar AL DÍA");
                continue;
            }

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
                    System.out.println("✅ Mora en tiempo real → WhatsApp enviado a " + loan.getBorrower() + " de parte de " + nombrePrestamista);
                } catch (Exception e) {
                    System.err.println("❌ Error WhatsApp mora: " + e.getMessage());
                }
            }
        }

        if (!cambiados.isEmpty()) {
            loanRepo.saveAll(cambiados);
        }
    }

    @GetMapping
    public ResponseEntity<?> getLoans(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        // Antes se consultaba dos veces (una para detectar mora, otra —descartando
        // la primera— para la respuesta), duplicando el viaje de ida y vuelta a
        // Mongo. detectarMoraEnTiempoReal ya modifica los "loan" en memoria y los
        // guarda, así que basta con devolver la misma lista.
        List<Loan> loans = loanRepo.findByUserIdOrderByCreatedAtDesc(uid);
        detectarMoraEnTiempoReal(loans);
        return ResponseEntity.ok(loans);
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
        // Hora real de creación (independiente de startDate): la usa la caja
        // para saber si este préstamo se otorgó mientras estaba abierta.
        loan.setFechaRegistro(LocalDateTime.now());

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

            Integer cuotasSemanalesExtra = body.containsKey("cuotasSemanales")
                    ? toInt(body.get("cuotasSemanales")) : null;

            // Si eligió "6 semanas" (Semanal + 6 cuotas), la fecha final es
            // exactamente 42 días después del inicio; "months" (1) queda
            // solo para el cálculo del interés total.
            LocalDate calculatedEnd;
            if ("weekly".equals(freq) && cuotasSemanalesExtra != null && cuotasSemanalesExtra >= 6) {
                calculatedEnd = startDate.plusDays(42);
            } else {
                calculatedEnd = startDate.plusMonths(months);
            }
            loan.setEndDate(calculatedEnd);
            loan.setDueDate(calculatedEnd.atStartOfDay());

            long dias = ChronoUnit.DAYS.between(startDate, calculatedEnd);

            Integer weeklyIntervalDaysExtra = null;
            int totalInstallmentsExtra;
            if ("weekly".equals(freq) && cuotasSemanalesExtra != null && cuotasSemanalesExtra > 0) {
                // El usuario eligió 4, 5 o 6 cuotas por mes: el total de cuotas es
                // esa cantidad multiplicada por los meses del préstamo. Con 6, el
                // intervalo queda fijo en 7 días (una detrás de otra, sin comprimir).
                int n = cuotasSemanalesExtra >= 6 ? 6 : (cuotasSemanalesExtra >= 5 ? 5 : 4);
                totalInstallmentsExtra = n * months;
                if (n == 6) {
                    weeklyIntervalDaysExtra = 7;
                } else {
                    weeklyIntervalDaysExtra = (int) Math.max(1, Math.floor(dias / (double) totalInstallmentsExtra));
                }
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

        // ─── PRÉSTAMO MÉTODO (amortización francesa) ──────────────────────
        // La cuota se calcula SIEMPRE como la cuota fija MENSUAL de toda la
        // vida: r = interés mensual directo, n = número de meses del
        // préstamo. La tasa y la fórmula de amortización nunca se tocan.
        // Si el cobro elegido es quincenal o semanal, esa misma cuota
        // mensual simplemente se reparte entre los cobros del mes:
        // quincenal ÷2, semanal ÷4 (y el total de cuotas se multiplica
        // igual: meses×2 o meses×4).
        if ("metodo".equals(loanType)) {
            int numMonths = body.containsKey("numMonths")
                    ? toInt(body.get("numMonths"))
                    : (int) Math.round(daysBetween / 30.4375);
            if (numMonths < 1) numMonths = 1;
            if (numMonths > 12) numMonths = 12;
            loan.setMonths(numMonths);

            double r = loan.getInterest() / 100.0;
            double cuotaMensual = (r == 0)
                    ? loan.getAmount() / numMonths
                    : loan.getAmount() * r / (1 - Math.pow(1 + r, -numMonths));

            int cobrosPorMes = "biweekly".equals(freq) ? 2 : "weekly".equals(freq) ? 4 : 1;
            int totalInstallmentsMetodo    = numMonths * cobrosPorMes;
            double installmentAmountMetodo = cuotaMensual / cobrosPorMes;
            Integer weeklyIntervalDaysMetodo = cobrosPorMes == 4 ? 7 : cobrosPorMes == 2 ? 15 : null;

            loan.setTotalInstallments(totalInstallmentsMetodo);
            loan.setInstallmentAmount(installmentAmountMetodo);
            loan.setWeeklyIntervalDays(weeklyIntervalDaysMetodo);

            loanRepo.save(loan);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "id", loan.getId(),
                    "totalInstallments", totalInstallmentsMetodo,
                    "installmentAmount", installmentAmountMetodo
            ));
        }

        int totalInstallments;
        Integer weeklyIntervalDays = null;
        Integer cuotasSemanales = body.containsKey("cuotasSemanales")
                ? toInt(body.get("cuotasSemanales")) : null;
        if ("weekly".equals(freq) && cuotasSemanales != null && cuotasSemanales > 0) {
            // El usuario eligió 4 o 5 cuotas dentro del mes en vez de la cadencia
            // clásica de 7 días: el intervalo se ajusta para que todas las cuotas
            // caigan dentro del período elegido sin pasarse de la fecha de fin.
            // Con 6 cuotas el comportamiento es distinto: se cuentan una detrás
            // de otra cada 7 días exactos (sin comprimir), y la fecha de fin se
            // recalcula sola como el día de la 6ª cuota, sin importar qué fecha
            // haya llegado del formulario.
            int n = cuotasSemanales >= 6 ? 6 : (cuotasSemanales >= 5 ? 5 : 4);
            totalInstallments = n;
            if (n == 6) {
                weeklyIntervalDays = 7;
                endDate = startDate.plusDays(7L * n);
                loan.setEndDate(endDate);
                loan.setDueDate(endDate.atStartOfDay());
            } else {
                weeklyIntervalDays = (int) Math.max(1, Math.floor(daysBetween / (double) n));
            }
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
        double totalConInteres = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100);
        installmentAmount = totalConInteres / totalInstallments;
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
        if (body.containsKey("borrower")) {
            String newName = (String) body.get("borrower");
            if (newName == null || newName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "ok", false,
                        "error", "El nombre es obligatorio"
                ));
            }
            loan.setBorrower(newName.trim());
        }
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

    // ─── "Paso": adelanta un día el cronograma de un préstamo DIARIO ──────
    // No registra ningún pago; simplemente corre startDate y endDate un día
    // hacia adelante, de modo que la cuota que hoy aparecía vencida (o por
    // cobrar hoy) pasa a vencer mañana, y todas las cuotas siguientes se
    // recalculan automáticamente en el frontend a partir de la nueva fecha.
    @PostMapping("/{id}/paso")
    public ResponseEntity<?> pasarDia(@PathVariable String id, HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<Loan> opt = loanRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("Not found");

        Loan loan = opt.get();
        if (!"daily".equals(loan.getFrequency())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "El botón de paso solo aplica a préstamos con cobro diario"
            ));
        }

        // Solo se adelanta startDate: la fecha de vencimiento del préstamo
        // (endDate / "Vence") NUNCA se mueve. La última cuota, sea cual sea,
        // siempre sigue cayendo en endDate (así lo maneja ya el frontend).
        LocalDate nuevoStart = loan.getStartDate().plusDays(1);
        loan.setStartDate(nuevoStart);

        loanRepo.save(loan);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "startDate", nuevoStart.toString(),
                "endDate", loan.getEndDate().toString()
        ));
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

            Integer cuotasSemanalesExtra = body.containsKey("cuotasSemanales")
                    ? toInt(body.get("cuotasSemanales")) : null;

            // Igual que en creación: 6 semanas = 42 días exactos desde el
            // inicio de la renovación; "months" solo se usa para el interés.
            LocalDate nuevaFechaFinExtra;
            if ("weekly".equals(freq) && cuotasSemanalesExtra != null && cuotasSemanalesExtra >= 6) {
                nuevaFechaFinExtra = hoy.plusDays(42);
            } else {
                nuevaFechaFinExtra = hoy.plusMonths(months);
            }
            long dias = ChronoUnit.DAYS.between(hoy, nuevaFechaFinExtra);

            Integer weeklyIntervalDaysExtra = null;
            int totalInstallmentsExtra;
            if ("weekly".equals(freq) && cuotasSemanalesExtra != null && cuotasSemanalesExtra > 0) {
                int n = cuotasSemanalesExtra >= 6 ? 6 : (cuotasSemanalesExtra >= 5 ? 5 : 4);
                totalInstallmentsExtra = n * months;
                if (n == 6) {
                    weeklyIntervalDaysExtra = 7;
                } else {
                    weeklyIntervalDaysExtra = (int) Math.max(1, Math.floor(dias / (double) totalInstallmentsExtra));
                }
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

        // ─── RENOVACIÓN DE PRÉSTAMO MÉTODO ─────────────────────────────────
        // Misma lógica que en creación: cuota fija MENSUAL de siempre (fórmula
        // de amortización francesa intacta), repartida entre los cobros del
        // mes según la frecuencia (quincenal ÷2, semanal ÷4).
        if ("metodo".equals(loanType)) {
            int numMonths = body.containsKey("numMonths")
                    ? toInt(body.get("numMonths"))
                    : (body.containsKey("totalInstallments")
                    ? toInt(body.get("totalInstallments"))
                    : (loan.getMonths() > 0 ? loan.getMonths()
                    : (loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1)));
            if (numMonths < 1) numMonths = 1;
            if (numMonths > 12) numMonths = 12;

            LocalDate nuevaFechaFinMetodo = hoy.plusMonths(numMonths);

            double r = nuevoPorcentaje / 100.0;
            double cuotaMensual = (r == 0)
                    ? nuevoMonto / numMonths
                    : nuevoMonto * r / (1 - Math.pow(1 + r, -numMonths));

            int cobrosPorMes = "biweekly".equals(freq) ? 2 : "weekly".equals(freq) ? 4 : 1;
            int totalInstallmentsMetodo    = numMonths * cobrosPorMes;
            double installmentAmountMetodo = cuotaMensual / cobrosPorMes;
            Integer weeklyIntervalDaysMetodo = cobrosPorMes == 4 ? 7 : cobrosPorMes == 2 ? 15 : null;

            loan.setAmount(nuevoMonto);
            loan.setInterest(nuevoPorcentaje);
            loan.setStartDate(hoy);
            loan.setEndDate(nuevaFechaFinMetodo);
            loan.setFrequency(freq);
            loan.setMonths(numMonths);
            loan.setCreatedAt(hoy.atStartOfDay());
            loan.setDueDate(nuevaFechaFinMetodo.atStartOfDay());
            loan.setTotalInstallments(totalInstallmentsMetodo);
            loan.setInstallmentAmount(installmentAmountMetodo);
            loan.setWeeklyIntervalDays(weeklyIntervalDaysMetodo);
            loan.setStatus("active");
            loan.setMoraNotificada(false);
            loan.setRenovado(true);

            loanRepo.save(loan);

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "totalInstallments", totalInstallmentsMetodo,
                    "installmentAmount", installmentAmountMetodo
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
        if ("weekly".equals(freq) && cuotasSemanales != null && cuotasSemanales > 0) {
            int n = cuotasSemanales >= 6 ? 6 : (cuotasSemanales >= 5 ? 5 : 4);
            totalInstallments = n;
            if (n == 6) {
                // 6 cuotas: cada 7 días exactos, una detrás de otra; la fecha
                // de fin se recalcula sola como el día de la 6ª cuota, sin
                // importar la fecha de fin que haya llegado del formulario.
                weeklyIntervalDays = 7;
                nuevaFechaFin = hoy.plusDays(7L * n);
            } else {
                long dias = ChronoUnit.DAYS.between(hoy, nuevaFechaFin);
                weeklyIntervalDays = (int) Math.max(1, Math.floor(dias / (double) n));
            }
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
        double totalConInteres = nuevoMonto + (nuevoMonto * nuevoPorcentaje / 100);
        installmentAmount = totalConInteres / totalInstallments;

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
        List<String> rutas = new ArrayList<>(loans.stream()
                .map(Loan::getRuta)
                .filter(r -> r != null && !r.isBlank())
                .distinct()
                .sorted()
                .toList());

        // "Sin ruta": agrupa los préstamos que no tienen ninguna ruta asignada,
        // y se comporta como una ruta más (mismo endpoint de detalle).
        // Si el usuario ya tiene una ruta real llamada así, no la duplicamos.
        boolean yaExisteRutaConEseNombre = rutas.stream().anyMatch(r -> r.equalsIgnoreCase("Sin ruta"));
        if (!yaExisteRutaConEseNombre && !loanRepo.findByUserIdSinRuta(uid).isEmpty()) {
            rutas.add("Sin ruta");
        }
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
                                                 @RequestParam(required = false) Integer dia,
                                                 HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        List<Loan> loans = loanRepo.findByUserIdAndRutaOrderByCreatedAtDesc(uid, nombre);
        if (loans.isEmpty() && "sin ruta".equalsIgnoreCase(nombre)) {
            // Pseudo-ruta: agrupa los préstamos que no tienen ninguna ruta asignada
            loans = loanRepo.findByUserIdSinRuta(uid).stream()
                    .sorted(Comparator.comparing(Loan::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }
        // Se traen los pagos de TODOS los préstamos de la ruta en una sola
        // consulta (en vez de una consulta por préstamo, repetida además en
        // cada uno de los 3 lugares que los necesitan), que es lo que hacía
        // lenta la carga de rutas con varios préstamos.
        Map<String, List<Payment>> pagosPorLoan = pagosPorPrestamo(loans);
        detectarMoraEnTiempoReal(loans, pagosPorLoan);

        LocalDate hoy = LocalDate.now();
        int mesSeleccionado  = (mes  != null && mes  >= 1 && mes <= 12) ? mes  : hoy.getMonthValue();
        int anioSeleccionado = (anio != null && anio >  0)              ? anio : hoy.getYear();
        YearMonth mesConsultado = YearMonth.of(anioSeleccionado, mesSeleccionado);
        // Día específico (opcional): si viene y es válido dentro del mes consultado,
        // se calcula además el total recaudado únicamente ese día.
        Integer diaSeleccionado = (dia != null && dia >= 1 && dia <= mesConsultado.lengthOfMonth()) ? dia : null;
        LocalDate fechaDiaConsultado = diaSeleccionado != null ? mesConsultado.atDay(diaSeleccionado) : null;
        double totalHoy = 0;
        double totalMes = 0;
        double totalDia = 0;
        double totalRecaudado = 0;
        List<Map<String, Object>> resultado = new ArrayList<>();

        for (Loan loan : loans) {
            // Pagos de este préstamo: se toman en cuenta para los totales de la ruta
            // (todos los préstamos, sin importar su estado: activos, en mora o ya pagados)
            // y también para el detalle individual (que solo lista activos/mora).
            List<com.diariopay.model.Payment> pagosLoan = pagosPorLoan.getOrDefault(loan.getId(), List.of());
            double pagadoLoan = 0;
            for (com.diariopay.model.Payment p : pagosLoan) {
                pagadoLoan += p.getAmount();
                totalRecaudado += p.getAmount();
                if (p.getDate() != null && YearMonth.from(p.getDate()).equals(mesConsultado)) {
                    totalMes += p.getAmount();
                }
                if (fechaDiaConsultado != null && p.getDate() != null
                        && p.getDate().toLocalDate().equals(fechaDiaConsultado)) {
                    totalDia += p.getAmount();
                }
            }

            if (!"active".equals(loan.getStatus()) && !"overdue".equals(loan.getStatus())) continue;

            boolean yaPagoHoy = pagosLoan.stream()
                    .anyMatch(p -> p.getDate() != null && p.getDate().toLocalDate().equals(hoy));

            double cuotaHoy = 0;
            if (!yaPagoHoy && loan.getStartDate() != null && !hoy.isBefore(loan.getStartDate())) {
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
        resp.put("diaConsultado",     diaSeleccionado);
        resp.put("totalDia",          totalDia);

        // ─── Cuadros de resumen (solo plan PRO) ────────────────────────────
        // Mismos 6 cuadros que el inicio (Total prestado activo, Total
        // cobrado, Por cobrar, Préstamos activos, Total capital+interés,
        // Interés del mes) pero calculados únicamente con los préstamos de
        // esta ruta. Solo se incluyen en la respuesta si el usuario tiene el
        // plan PRO activado desde el panel admin; si no, el front no recibe
        // estos datos y no muestra los cuadros.
        boolean esPro = userRepo.findById(uid).map(com.diariopay.model.User::isPlanPro).orElse(false);
        if (esPro) {
            resp.put("resumenRuta", calcularResumenRuta(loans, pagosPorLoan));
        }

        return ResponseEntity.ok(resp);
    }

    /**
     * Calcula los mismos 6 indicadores del resumen del inicio
     * (/api/stats) pero limitados a los préstamos de una sola ruta.
     */
    private Map<String, Object> calcularResumenRuta(List<Loan> loansRuta, Map<String, List<Payment>> pagosPorLoan) {
        double totalPrestadoActivo = 0;
        double totalCobrado        = 0;
        long   prestamosActivos    = 0;
        double saldoTotalConInteres = 0;
        double interesMesActual     = 0;
        // "Por cobrar" se calcula sobre TODOS los préstamos no pagados
        // (activos + en mora), no solo los activos: si no, un préstamo en
        // mora deja de sumar en "prestado" pero sus pagos sí restan de
        // "cobrado", y "por cobrar" termina bajando de forma artificial.
        double prestadoPendiente   = 0;
        double cobradoDePendientes = 0;

        for (Loan loan : loansRuta) {
            List<Payment> pagosLoan = pagosPorLoan.getOrDefault(loan.getId(), List.of());
            double pagadoLoan = pagosLoan.stream().mapToDouble(Payment::getAmount).sum();

            if ("active".equals(loan.getStatus())) {
                totalPrestadoActivo += loan.getAmount();
                totalCobrado        += pagadoLoan;
                prestamosActivos++;
            }
            if (!"paid".equals(loan.getStatus())) {
                prestadoPendiente    += loan.getAmount();
                cobradoDePendientes  += pagadoLoan;
                saldoTotalConInteres += calcularSaldoConInteresRuta(loan, pagosLoan);
                interesMesActual     += calcularInteresMesRuta(loan, pagosLoan);
            }
        }

        double porCobrar = Math.max(prestadoPendiente - cobradoDePendientes, 0);

        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("totalPrestadoActivo",  totalPrestadoActivo);
        resumen.put("totalCobrado",         totalCobrado);
        resumen.put("porCobrar",            porCobrar);
        resumen.put("prestamosActivos",     prestamosActivos);
        resumen.put("saldoTotalConInteres", saldoTotalConInteres);
        resumen.put("interesMesActual",     interesMesActual);
        return resumen;
    }

    /** Igual que StatsController.calcularSaldoConInteres, para uso por ruta. */
    private double calcularSaldoConInteresRuta(Loan loan, List<Payment> pagos) {
        double paidTotal = pagos.stream().mapToDouble(Payment::getAmount).sum();
        String tipo = loan.getLoanType() != null ? loan.getLoanType() : "normal";
        switch (tipo) {
            case "grande": {
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
                double cuota = calcCuotaFijaRuta(P, r, n);
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

    /** Igual que StatsController.calcularInteresMes, para uso por ruta. */
    private double calcularInteresMesRuta(Loan loan, List<Payment> pagos) {
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
                double cuota = calcCuotaFijaRuta(P, r, n);
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
                return loan.getAmount() * loan.getInterest() / 100.0;
            }
        }
    }

    private double calcCuotaFijaRuta(double P, double r, int n) {
        if (r == 0) return P / n;
        return P * r / (1 - Math.pow(1 + r, -n));
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