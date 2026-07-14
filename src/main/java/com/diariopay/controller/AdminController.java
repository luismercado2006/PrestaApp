package com.diariopay.controller;

import com.diariopay.model.User;
import com.diariopay.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Panel de administración para gestionar las suscripciones/premium de los usuarios.
 * Protegido en SecurityConfig: solo accesible para cuentas con role = "ADMIN".
 *
 * Para convertir una cuenta en admin, desde Mongo:
 *   db.users.updateOne({username:"tuusuario"}, {$set:{role:"ADMIN"}})
 */
@Controller
public class AdminController {

    @Autowired
    private UserRepository userRepo;

    // ── Página HTML del panel ──────────────────────────────────────────────
    @GetMapping("/admin")
    public String adminPage() {
        return "admin";
    }

    // ── API: listar usuarios con su estado de suscripción ─────────────────
    @GetMapping("/api/admin/users")
    @ResponseBody
    public List<Map<String, Object>> listUsers() {
        List<User> users = userRepo.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : users) {
            LocalDateTime expires = u.getEffectivePremiumExpiresAt();
            long daysLeft = ChronoUnit.DAYS.between(LocalDateTime.now(), expires);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("name", u.getName());
            m.put("role", u.getRole());
            m.put("createdAt", u.getCreatedAt());
            m.put("expiresAt", expires);
            m.put("daysLeft", daysLeft);
            m.put("active", u.isPremiumActive());
            m.put("override", u.getPremiumOverride() == null ? "auto" : (u.getPremiumOverride() ? "activado" : "desactivado"));
            result.add(m);
        }
        // Vencidos/bloqueados primero, para que el admin los vea rápido
        result.sort((a, b) -> {
            boolean activeA = (boolean) a.get("active");
            boolean activeB = (boolean) b.get("active");
            if (activeA == activeB) return 0;
            return activeA ? 1 : -1;
        });
        return result;
    }

    // ── API: activar manualmente (override = true) ─────────────────────────
    @PostMapping("/api/admin/users/{id}/activar")
    @ResponseBody
    public ResponseEntity<?> activar(@PathVariable String id) {
        return setOverride(id, true);
    }

    // ── API: desactivar manualmente (override = false) ─────────────────────
    @PostMapping("/api/admin/users/{id}/desactivar")
    @ResponseBody
    public ResponseEntity<?> desactivar(@PathVariable String id) {
        return setOverride(id, false);
    }

    // ── API: volver al modo automático (quita el override, respeta la fecha) ──
    @PostMapping("/api/admin/users/{id}/automatico")
    @ResponseBody
    public ResponseEntity<?> automatico(@PathVariable String id) {
        Optional<User> opt = userRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Usuario no encontrado");
        User u = opt.get();
        u.setPremiumOverride(null);
        userRepo.save(u);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── API: renovar 1 mes desde hoy (o desde su vencimiento si aún no vence) ──
    @PostMapping("/api/admin/users/{id}/renovar")
    @ResponseBody
    public ResponseEntity<?> renovar(@PathVariable String id) {
        Optional<User> opt = userRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Usuario no encontrado");
        User u = opt.get();
        LocalDateTime base = u.getEffectivePremiumExpiresAt();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nuevaFecha = (base.isAfter(now) ? base : now).plusMonths(1);
        u.setPremiumExpiresAt(nuevaFecha);
        u.setPremiumOverride(null); // vuelve a modo automático, ahora con la nueva fecha
        userRepo.save(u);
        return ResponseEntity.ok(Map.of("ok", true, "nuevaFecha", nuevaFecha));
    }

    // ── API: retroceder 1 mes la fecha de vencimiento ──
    @PostMapping("/api/admin/users/{id}/retroceder")
    @ResponseBody
    public ResponseEntity<?> retroceder(@PathVariable String id) {
        Optional<User> opt = userRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Usuario no encontrado");
        User u = opt.get();
        LocalDateTime base = u.getEffectivePremiumExpiresAt();
        LocalDateTime nuevaFecha = base.minusMonths(1);
        u.setPremiumExpiresAt(nuevaFecha);
        u.setPremiumOverride(null); // modo automático: si queda vencida, se bloquea sola
        userRepo.save(u);
        return ResponseEntity.ok(Map.of("ok", true, "nuevaFecha", nuevaFecha));
    }

    private ResponseEntity<?> setOverride(String id, boolean value) {
        Optional<User> opt = userRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Usuario no encontrado");
        User u = opt.get();
        u.setPremiumOverride(value);
        userRepo.save(u);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}