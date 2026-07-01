package com.diariopay.config;

import com.diariopay.model.User;
import com.diariopay.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Bloquea las rutas /api/** (excepto /api/premium/**) cuando la cuenta del
 * usuario logueado no tiene la suscripción activa (venció el periodo de
 * prueba/plan y nadie la reactivó desde la base de datos).
 *
 * El front interpreta el código 402 y muestra la pantalla de "Premium".
 */
@Component
public class PremiumAccessFilter extends OncePerRequestFilter {

    @Autowired
    private UserRepository userRepo;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        boolean isApi = path.startsWith("/api/");
        boolean isPremiumOwnRoute = path.startsWith("/api/premium/");
        boolean isAdminRoute = path.startsWith("/api/admin/");

        if (!isApi || isPremiumOwnRoute || isAdminRoute) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        String uid = session != null ? (String) session.getAttribute("userId") : null;

        if (uid != null) {
            Optional<User> opt = userRepo.findById(uid);
            if (opt.isPresent() && !opt.get().isPremiumActive()) {
                response.setStatus(402); // Payment Required
                response.setContentType("application/json");
                response.getWriter().write("{\"premiumRequired\":true,\"msg\":\"Tu cuenta no tiene la suscripción activa\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}