package com.diariopay.controller;

import com.diariopay.model.User;
import com.diariopay.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Expone el estado de la suscripción/premium del usuario logueado.
 * Esta ruta NUNCA queda bloqueada por el filtro de premium (ver PremiumAccessFilter),
 * para que el front siempre pueda consultar si la cuenta está activa o no.
 */
@RestController
@RequestMapping("/api/premium")
public class PremiumController {

    @Autowired
    private UserRepository userRepo;

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<User> opt = userRepo.findById(uid);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("User not found");

        User user = opt.get();
        LocalDateTime expires = user.getEffectivePremiumExpiresAt();
        LocalDateTime now = LocalDateTime.now();
        long daysLeft = ChronoUnit.DAYS.between(now, expires);

        return ResponseEntity.ok(Map.of(
                "active", user.isPremiumActive(),
                "createdAt", user.getCreatedAt(),
                "expiresAt", expires,
                "daysLeft", daysLeft,
                "override", user.getPremiumOverride() == null ? "auto" : (user.getPremiumOverride() ? "activado" : "desactivado")
        ));
    }
}