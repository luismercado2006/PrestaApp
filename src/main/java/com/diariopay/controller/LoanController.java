package com.diariopay.controller;

import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.PaymentRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    @Autowired private LoanRepository    loanRepo;
    @Autowired private PaymentRepository paymentRepo;

    @GetMapping
    public ResponseEntity<?> getLoans(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");
        return ResponseEntity.ok(loanRepo.findByUserIdOrderByCreatedAtDesc(uid));
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
        loan.setNotes((String) body.getOrDefault("notes", ""));
        loan.setStatus("active");
        LocalDate startDate = LocalDate.parse((String) body.getOrDefault("startDate", LocalDate.now().toString()));
        LocalDate endDate   = LocalDate.parse((String) body.getOrDefault("endDate",   LocalDate.now().plusDays(30).toString()));
        loan.setStartDate(startDate);
        loan.setEndDate(endDate);
        loan.setDueDate(endDate.atStartOfDay());

        long daysBetween = ChronoUnit.DAYS.between(startDate.plusDays(1), endDate);
        String freq = loan.getFrequency();
        int totalInstallments = switch (freq) {
            case "weekly"  -> (int) Math.ceil(daysBetween / 7.0);
            case "monthly" -> (int) Math.ceil(daysBetween / 30.0);
            default        -> (int) daysBetween; // daily
        };
        if (totalInstallments < 1) totalInstallments = 1;

        double totalConInteres = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100);
        double installmentAmount = totalConInteres / totalInstallments;
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
        List<Payment> payments = paymentRepo.findByLoanId(id);
        double paidTotal = payments.stream().mapToDouble(Payment::getAmount).sum();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id",        loan.getId());
        resp.put("borrower",  loan.getBorrower());
        resp.put("amount",    loan.getAmount());
        resp.put("interest",  loan.getInterest());
        resp.put("frequency", loan.getFrequency());
        resp.put("status",    loan.getStatus());
        resp.put("notes",     loan.getNotes());
        resp.put("createdAt", loan.getCreatedAt());
        resp.put("dueDate",   loan.getDueDate());
        resp.put("payments",  payments);
        resp.put("paidTotal", paidTotal);
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
        if (body.containsKey("status")) loan.setStatus((String) body.get("status"));
        if (body.containsKey("notes"))  loan.setNotes((String) body.get("notes"));
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
