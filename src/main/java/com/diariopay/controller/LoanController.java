package com.diariopay.controller;

import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import com.diariopay.model.User;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.PaymentRepository;
import com.diariopay.repository.UserRepository;
import com.diariopay.service.WhatsAppService;
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

    // ─── Detecta mora en tiempo real y envía WhatsApp con nombre del prestamista
    private void detectarMoraEnTiempoReal(List<Loan> prestamos) {
        LocalDate hoy = LocalDate.now();
        for (Loan loan : prestamos) {
            if ("active".equals(loan.getStatus())
                    && loan.getEndDate() != null
                    && !hoy.isBefore(loan.getEndDate())) {

                loan.setStatus("overdue");
                loanRepo.save(loan);

                if (!loan.isMoraNotificada()
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
        loan.setPhone((String) body.getOrDefault("phone", ""));
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
            totalInstallments = switch (freq) {
                case "weekly"  -> (int) Math.ceil(daysBetween / 7.0);
                case "monthly" -> (int) Math.round(daysBetween / 30.4375);
                default        -> (int) daysBetween;
            };
        }
        if (totalInstallments < 1) totalInstallments = 1;

        double installmentAmount;
        if ("metodo".equals(loanType)) {
            installmentAmount = loan.getAmount() / totalInstallments;
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

        List<Payment> payments = paymentRepo.findByLoanId(id);
        double paidTotal    = payments.stream().mapToDouble(Payment::getAmount).sum();
        double paidInterest = payments.stream()
                .filter(p -> "interest".equals(p.getPaymentType()))
                .mapToDouble(Payment::getAmount).sum();
        double paidCapital  = payments.stream()
                .filter(p -> !"interest".equals(p.getPaymentType()))
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
        if (body.containsKey("phone"))   loan.setPhone((String) body.get("phone"));
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