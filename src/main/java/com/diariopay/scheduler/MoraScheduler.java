package com.diariopay.scheduler;

import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import com.diariopay.model.User;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.PaymentRepository;
import com.diariopay.repository.UserRepository;
import com.diariopay.service.WhatsAppService;
import com.diariopay.service.LoanStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class MoraScheduler {

    @Autowired private LoanRepository    loanRepo;
    @Autowired private PaymentRepository paymentRepo;
    @Autowired private UserRepository    userRepo;
    @Autowired private WhatsAppService   whatsAppService;
    @Autowired private LoanStatusService loanStatusService;

    @Scheduled(cron = "0 35 20 * * *", zone = "America/Bogota")
    public void verificarPrestamosEnMora() {

        // 1. Recalcular el estado real (active | overdue | paid) de todos los
        //    préstamos activos o en mora, según las cuotas realmente pagadas.
        //    Esto es lo que permite que un préstamo SALGA de mora automáticamente
        //    cuando el cliente se pone al día (antes solo se podía pasar de
        //    "active" a "overdue", nunca al revés).
        List<Loan> paraRevisar = new ArrayList<>();
        paraRevisar.addAll(loanRepo.findByStatus("active"));
        paraRevisar.addAll(loanRepo.findByStatus("overdue"));

        for (Loan loan : paraRevisar) {
            List<Payment> payments = paymentRepo.findByLoanIdAndArchivadoFalse(loan.getId());
            String estadoAnterior = loan.getStatus();
            String nuevoEstado    = loanStatusService.calcularEstadoActual(loan, payments);

            if (nuevoEstado.equals(estadoAnterior)) continue;

            loan.setStatus(nuevoEstado);
            if ("active".equals(nuevoEstado)) {
                loan.setMoraNotificada(false);
                System.out.println("✅ Préstamo de " + loan.getBorrower() + " vuelve a estar AL DÍA");
            } else if ("overdue".equals(nuevoEstado)) {
                System.out.println("Préstamo de " + loan.getBorrower() + " marcado como MORA");
            }
            loanRepo.save(loan);
        }

        // 2. Notificar los que están en mora y no han sido notificados
        List<Loan> enMora = loanRepo.findByStatus("overdue");
        for (Loan loan : enMora) {
            if (!loan.isMoraNotificada()
                    && loan.getPhone() != null
                    && !loan.getPhone().isBlank()) {

                String nombrePrestamista = obtenerNombrePrestamista(loan.getUserId());

                try {
                    whatsAppService.enviarMensajeMora(
                            loan.getPhone(),
                            loan.getBorrower(),
                            loan.getAmount(),
                            nombrePrestamista
                    );
                    loan.setMoraNotificada(true);
                    loanRepo.save(loan);
                    System.out.println("✅ WhatsApp enviado a " + loan.getBorrower() + " de parte de " + nombrePrestamista);
                } catch (Exception e) {
                    System.err.println("❌ Error WhatsApp: " + e.getMessage());
                }
            }
        }
    }

    private String obtenerNombrePrestamista(String userId) {
        if (userId == null) return "DiarioPay";
        return userRepo.findById(userId)
                .map(User::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("DiarioPay");
    }
}