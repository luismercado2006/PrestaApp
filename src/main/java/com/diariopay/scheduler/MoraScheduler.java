package com.diariopay.scheduler;

import com.diariopay.model.Loan;
import com.diariopay.model.User;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.UserRepository;
import com.diariopay.service.WhatsAppService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class MoraScheduler {

    @Autowired private LoanRepository    loanRepo;
    @Autowired private UserRepository    userRepo;
    @Autowired private WhatsAppService   whatsAppService;

    @Scheduled(cron = "0 20 21 * * *")
    public void verificarPrestamosEnMora() {
        LocalDate hoy = LocalDate.now();

        // 1. Marcar como overdue los préstamos activos vencidos
        List<Loan> activos = loanRepo.findByStatus("active");
        for (Loan loan : activos) {
            if (loan.getEndDate() != null && !hoy.isBefore(loan.getEndDate())) {
                loan.setStatus("overdue");
                loanRepo.save(loan);
                System.out.println("Préstamo de " + loan.getBorrower() + " marcado como MORA");
            }
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
