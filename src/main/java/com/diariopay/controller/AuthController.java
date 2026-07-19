package com.diariopay.controller;

import com.diariopay.model.User;
import com.diariopay.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;

@Controller
public class AuthController {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private BCryptPasswordEncoder encoder;

    @GetMapping("/mi-prestamo")
    public String miPrestamo() {
        return "mi-prestamo";
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboardPage(Authentication authentication, Model model,
                                jakarta.servlet.http.HttpSession session) {

        String username = authentication.getName();

        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        session.setAttribute("userId", user.getId());

        model.addAttribute("user", user);
        model.addAttribute("premiumActive", user.isPremiumActive());

        return "dashboard";
    }



    @PostMapping("/register")
    @ResponseBody
    public Map<String, Object> doRegister(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim().toLowerCase();
        String password = body.getOrDefault("password", "");
        String name     = body.getOrDefault("name", "").trim();
        if (userRepo.existsByUsername(username)) {
            return Map.of("ok", false, "msg", "El usuario ya existe");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(encoder.encode(password));
        user.setName(name);
        user.setPremiumExpiresAt(java.time.LocalDateTime.now().plusMonths(1));
        user.setPruebaExpiraEn(java.time.LocalDateTime.now().plusDays(2));
        userRepo.save(user);
        return Map.of("ok", true);
    }

    // ─── MI CUENTA ──────────────────────────────────────────────────────
    // Nota de seguridad: la contraseña se guarda con hash (BCrypt) y nunca
    // se puede recuperar en texto plano, así que este endpoint jamás la
    // devuelve. El campo "hasPassword" solo le dice al frontend que ya hay
    // una contraseña configurada, para mostrar puntos de relleno en vez del
    // valor real.
    @GetMapping("/api/account")
    @ResponseBody
    public ResponseEntity<?> getAccount(jakarta.servlet.http.HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<User> opt = userRepo.findById(uid);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Not found");

        User user = opt.get();
        return ResponseEntity.ok(Map.of(
                "name", user.getName() != null ? user.getName() : "",
                "username", user.getUsername() != null ? user.getUsername() : "",
                "hasPassword", user.getPassword() != null && !user.getPassword().isBlank()
        ));
    }

    @PutMapping("/api/account")
    @ResponseBody
    public ResponseEntity<?> updateAccount(@RequestBody Map<String, String> body,
                                           jakarta.servlet.http.HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<User> opt = userRepo.findById(uid);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Not found");

        User user = opt.get();

        String newName = body.getOrDefault("name", "").trim();
        if (newName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "El nombre es obligatorio"));
        }

        String newUsername = body.getOrDefault("username", "").trim().toLowerCase();
        if (newUsername.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "El usuario es obligatorio"));
        }
        boolean usernameChanged = !newUsername.equals(user.getUsername());
        if (usernameChanged && userRepo.existsByUsername(newUsername)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Ese usuario ya está en uso"));
        }

        String newPassword = body.get("password");
        if (newPassword != null && !newPassword.isBlank()) {
            if (newPassword.length() < 4) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "La contraseña debe tener al menos 4 caracteres"));
            }
            user.setPassword(encoder.encode(newPassword));
        }

        user.setName(newName);
        user.setUsername(newUsername);
        userRepo.save(user);

        // Si el usuario cambió, la sesión de Spring Security quedó apuntando
        // al username anterior: cerramos sesión y le pedimos volver a
        // iniciar sesión con el nuevo usuario para evitar inconsistencias.
        if (usernameChanged) {
            session.invalidate();
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "name", newName,
                "username", newUsername,
                "requireRelogin", usernameChanged
        ));
    }

}