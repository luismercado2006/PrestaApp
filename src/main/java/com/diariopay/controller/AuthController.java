package com.diariopay.controller;

import com.diariopay.model.User;
import com.diariopay.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
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


}