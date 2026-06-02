package com.diariopay.controller;

import com.diariopay.model.User;
import com.diariopay.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;

@Controller
public class AuthController {

    @Autowired
    private UserRepository userRepo;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @GetMapping("/")
    public String index(HttpSession session) {
        return session.getAttribute("userId") != null
                ? "redirect:/dashboard"
                : "redirect:/login";
    }

    @GetMapping("/login")
    public String loginPage(HttpSession session) {
        if (session.getAttribute("userId") != null) return "redirect:/dashboard";
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboardPage(HttpSession session, org.springframework.ui.Model model) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return "redirect:/login";
        userRepo.findById(uid).ifPresent(u -> model.addAttribute("user", u));
        return "dashboard";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> doLogin(@RequestBody Map<String, String> body, HttpSession session) {
        String username = body.getOrDefault("username", "").trim().toLowerCase();
        String password = body.getOrDefault("password", "");
        Optional<User> opt = userRepo.findByUsername(username);
        if (opt.isPresent() && encoder.matches(password, opt.get().getPassword())) {
            session.setAttribute("userId", opt.get().getId());
            return Map.of("ok", true);
        }
        return Map.of("ok", false, "msg", "Usuario o contraseña incorrectos");
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
        userRepo.save(user);
        return Map.of("ok", true);
    }

    @GetMapping("/seed")
    @ResponseBody
    public String seed() {
        if (!userRepo.existsByUsername("carlos")) {
            User u = new User();
            u.setUsername("carlos");
            u.setPassword(encoder.encode("1234"));
            u.setName("Carlos");
            userRepo.save(u);
            return "✅ Usuario demo creado: carlos / 1234";
        }
        return "ℹ️ El usuario demo ya existe";
    }
}
