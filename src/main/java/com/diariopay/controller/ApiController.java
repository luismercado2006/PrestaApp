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
import java.util.*;

// ─── Payments ────────────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/payments")
class PaymentController {

    @Autowired private PaymentRepository paymentRepo;
    @Autowired private LoanRepository    loanRepo;

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
        paymentRepo.save(p);

        List<Payment> payments = paymentRepo.findByLoanId(loanId);
        double paid  = payments.stream().mapToDouble(Payment::getAmount).sum();
        double total = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100);
        if (paid >= total) {
            loan.setStatus("paid");
            loanRepo.save(loan);
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
        List<Payment> allPayments = paymentRepo.findByUserId(uid);

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

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalLoaned",    totalLoaned);
        resp.put("totalCollected", totalCollected);
        resp.put("pending",        pending);
        resp.put("activeLoans",    (long) activeLoans.size());
        resp.put("completed",      completed);
        resp.put("overdue",        overdue);
        resp.put("todayCollected", todayCollected);
        resp.put("todayPayments",  (long) todayPay.size());
        resp.put("chart",          chart);
        return ResponseEntity.ok(resp);
    }

    private String dayLabel(LocalDateTime day, int daysAgo) {
        if (daysAgo == 0) return "Hoy";
        if (daysAgo == 1) return "Ayer";
        String[] days = {"Lun","Mar","Mié","Jue","Vie","Sáb","Dom"};
        int dow = day.getDayOfWeek().getValue() - 1;
        return days[dow] + " " + day.getDayOfMonth();
    }
}
