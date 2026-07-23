package com.diariopay.controller;

import com.diariopay.model.CajaSession;
import com.diariopay.model.Loan;
import com.diariopay.model.Payment;
import com.diariopay.repository.CajaSessionRepository;
import com.diariopay.repository.LoanRepository;
import com.diariopay.repository.PaymentRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "Gastos" — control de caja del día.
 *
 * Dos modos:
 *  - "prestamos": el valor en caja baja con lo que se presta y sube con lo
 *    que se cobra (montoActual = inicial - préstamos otorgados + pagos - gastos extra).
 *  - "simple": el valor en caja solo sube con lo que se cobra
 *    (montoActual = inicial + pagos - gastos extra).
 */
@RestController
@RequestMapping("/api/caja")
public class CajaSessionController {

    @Autowired private CajaSessionRepository cajaRepo;
    @Autowired private PaymentRepository     paymentRepo;
    @Autowired private LoanRepository        loanRepo;

    // ─── Caja abierta actual (o null) ───────────────────────────────
    @GetMapping("/actual")
    public ResponseEntity<?> actual(HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        Optional<CajaSession> abierta = cajaRepo.findByUserIdAndEstado(uid, "abierta");
        if (abierta.isEmpty()) return ResponseEntity.ok(Map.of("abierta", false));

        return ResponseEntity.ok(construirRespuesta(abierta.get(), uid));
    }

    // ─── Detalle de una caja específica (abierta o ya cerrada) ─────
    @GetMapping("/{id}")
    public ResponseEntity<?> detalle(@PathVariable String id, HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        Optional<CajaSession> opt = cajaRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("No encontrado");

        return ResponseEntity.ok(construirRespuesta(opt.get(), uid));
    }

    // ─── Iniciar caja ────────────────────────────────────────────────
    @PostMapping("/iniciar")
    public ResponseEntity<?> iniciar(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        if (cajaRepo.findByUserIdAndEstado(uid, "abierta").isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "error", "Ya tienes una caja abierta. Finalízala antes de iniciar otra."));
        }

        String modo = (String) body.getOrDefault("modo", "simple");
        if (!"prestamos".equals(modo)) modo = "simple";

        CajaSession s = new CajaSession();
        s.setUserId(uid);
        s.setModo(modo);
        s.setMontoInicial(toDouble(body.get("montoInicial")));
        s.setEstado("abierta");
        s.setIniciadaEn(LocalDateTime.now());
        cajaRepo.save(s);

        return ResponseEntity.ok(construirRespuesta(s, uid));
    }

    // ─── Editar el valor inicial mientras la caja está abierta ─────
    @PutMapping("/{id}/monto")
    public ResponseEntity<?> editarMonto(@PathVariable String id,
                                         @RequestBody Map<String, Object> body,
                                         HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        CajaSession s = buscarAbierta(id, uid);
        if (s == null) return ResponseEntity.status(404).body("No encontrado o ya cerrada");

        s.setMontoInicial(toDouble(body.get("montoInicial")));
        cajaRepo.save(s);
        return ResponseEntity.ok(construirRespuesta(s, uid));
    }

    // ─── Registrar gasto extra ───────────────────────────────────────
    @PostMapping("/{id}/gasto")
    public ResponseEntity<?> agregarGasto(@PathVariable String id,
                                          @RequestBody Map<String, Object> body,
                                          HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        CajaSession s = buscarAbierta(id, uid);
        if (s == null) return ResponseEntity.status(404).body("No encontrado o ya cerrada");

        double monto = toDouble(body.get("monto"));
        if (monto <= 0) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "El monto debe ser mayor a 0"));
        }

        CajaSession.GastoExtra g = new CajaSession.GastoExtra();
        g.setId(UUID.randomUUID().toString());
        g.setMonto(monto);
        g.setNota((String) body.getOrDefault("nota", ""));
        g.setFecha(LocalDateTime.now());
        s.getGastosExtra().add(g);
        cajaRepo.save(s);

        return ResponseEntity.ok(construirRespuesta(s, uid));
    }

    // ─── Registrar el conteo de plata en físico (arqueo) ────────────
    @PutMapping("/{id}/efectivo")
    public ResponseEntity<?> registrarEfectivoFisico(@PathVariable String id,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        CajaSession s = buscarAbierta(id, uid);
        if (s == null) return ResponseEntity.status(404).body("No encontrado o ya cerrada");

        s.setEfectivoFisico(toDouble(body.get("efectivoFisico")));
        cajaRepo.save(s);
        return ResponseEntity.ok(construirRespuesta(s, uid));
    }

    // ─── Eliminar un gasto extra ─────────────────────────────────────
    @DeleteMapping("/{id}/gasto/{gastoId}")
    public ResponseEntity<?> eliminarGasto(@PathVariable String id,
                                           @PathVariable String gastoId,
                                           HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        CajaSession s = buscarAbierta(id, uid);
        if (s == null) return ResponseEntity.status(404).body("No encontrado o ya cerrada");

        s.getGastosExtra().removeIf(g -> g.getId().equals(gastoId));
        cajaRepo.save(s);
        return ResponseEntity.ok(construirRespuesta(s, uid));
    }

    // ─── Finalizar caja ───────────────────────────────────────────────
    @PostMapping("/{id}/finalizar")
    public ResponseEntity<?> finalizar(@PathVariable String id, HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        CajaSession s = buscarAbierta(id, uid);
        if (s == null) return ResponseEntity.status(404).body("No encontrado o ya cerrada");

        Map<String, Object> calculo = construirRespuesta(s, uid);
        s.setEstado("cerrada");
        s.setFinalizadaEn(LocalDateTime.now());
        s.setMontoFinal((Double) calculo.get("montoActual"));
        cajaRepo.save(s);

        return ResponseEntity.ok(construirRespuesta(s, uid));
    }

    // ─── Eliminar una caja del historial (solo cajas ya cerradas) ────
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarCaja(@PathVariable String id, HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        Optional<CajaSession> opt = cajaRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid))
            return ResponseEntity.status(404).body("No encontrado");

        CajaSession s = opt.get();
        if (!"cerrada".equals(s.getEstado())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "Solo se pueden eliminar cajas ya cerradas. Finaliza esta caja antes de borrarla."
            ));
        }

        cajaRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─── Historial de cajas cerradas ─────────────────────────────────
    @GetMapping("/historial")
    public ResponseEntity<?> historial(HttpSession session) {
        String uid = uid(session);
        if (uid == null) return unauthorized();

        List<CajaSession> cerradas = cajaRepo.findByUserIdOrderByIniciadaEnDesc(uid).stream()
                .filter(s -> "cerrada".equals(s.getEstado()))
                .limit(30)
                .toList();
        return ResponseEntity.ok(cerradas);
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    /**
     * Saldo pendiente (capital + interés, según el tipo de préstamo) de un
     * único préstamo, restando lo ya abonado. Usa exactamente la misma
     * fórmula que StatsController.calcularSaldoConInteres (pestaña de
     * estadísticas, tarjeta "Total (capital + interés)"), para que ambos
     * números siempre concuerden.
     *
     * Importante para préstamos "grande" (revolventes): no tienen un total
     * fijo (el interés se cobra mes a mes sobre el saldo), así que lo que
     * realmente se adeuda ahora mismo es el capital pendiente MÁS el interés
     * del ciclo actual — no solo el capital.
     */
    private double saldoPendiente(Loan loan) {
        List<Payment> pagos = paymentRepo.findByLoanIdAndArchivadoFalse(loan.getId());
        double paidTotal = pagos.stream().mapToDouble(Payment::getAmount).sum();
        String tipo = loan.getLoanType() != null ? loan.getLoanType() : "normal";

        switch (tipo) {
            case "grande": {
                double paidCapital = pagos.stream()
                        .filter(p -> "capital".equals(p.getPaymentType()) || "normal".equals(p.getPaymentType()))
                        .filter(p -> p.getAmount() > 0)
                        .mapToDouble(Payment::getAmount).sum();
                double saldoCapital = Math.max(loan.getAmount() - paidCapital, 0);
                double intMes = saldoCapital * loan.getInterest() / 100.0;
                return saldoCapital + intMes;
            }
            case "metodo": {
                double P = loan.getAmount();
                double r = loan.getInterest() / 100.0;
                int n = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
                double cuota = r == 0 ? P / n : P * r / (1 - Math.pow(1 + r, -n));
                double total = cuota * n;
                return Math.max(total - paidTotal, 0);
            }
            case "extra": {
                int n = loan.getTotalInstallments() > 0 ? loan.getTotalInstallments() : 1;
                double total = loan.getInstallmentAmount() * n;
                return Math.max(total - paidTotal, 0);
            }
            default: {
                double total = loan.getAmount() + (loan.getAmount() * loan.getInterest() / 100.0);
                return Math.max(total - paidTotal, 0);
            }
        }
    }

    private CajaSession buscarAbierta(String id, String uid) {
        Optional<CajaSession> opt = cajaRepo.findById(id);
        if (opt.isEmpty() || !opt.get().getUserId().equals(uid)) return null;
        CajaSession s = opt.get();
        if (!"abierta".equals(s.getEstado())) return null;
        return s;
    }

    /** Calcula en vivo cuánto queda en caja a partir de los pagos y préstamos del período. */
    private Map<String, Object> construirRespuesta(CajaSession s, String uid) {
        LocalDateTime desde = s.getIniciadaEn();
        LocalDateTime hasta = "cerrada".equals(s.getEstado()) && s.getFinalizadaEn() != null
                ? s.getFinalizadaEn() : LocalDateTime.now();

        double pagos = paymentRepo.findByUserIdAndDateBetween(uid, desde, hasta).stream()
                .mapToDouble(Payment::getAmount).sum();

        double prestamosOtorgados = 0;
        double capitalActivoConInteres = 0;
        if ("prestamos".equals(s.getModo())) {
            List<Loan> prestamosActivos = loanRepo.findByUserId(uid).stream()
                    .filter(l -> "active".equals(l.getStatus()) || "overdue".equals(l.getStatus()))
                    .toList();

            // Solo el capital de los préstamos que se otorgaron DURANTE esta
            // caja (entre "desde" y "hasta"): es la plata que efectivamente
            // salió físicamente de esta caja. Los préstamos de cajas
            // anteriores ya se descontaron cuando se otorgaron en su momento,
            // así que no deben volver a restarse aquí.
            prestamosOtorgados = prestamosActivos.stream()
                    .filter(l -> l.getCreatedAt() != null
                            && !l.getCreatedAt().isBefore(desde)
                            && !l.getCreatedAt().isAfter(hasta))
                    .mapToDouble(Loan::getAmount).sum();

            // "Capital prestado activo" (para mostrar): capital + interés que
            // el cliente todavía debe por cada préstamo activo/en mora, restando
            // lo ya abonado. Se reduce cada vez que se registra un pago.
            capitalActivoConInteres = prestamosActivos.stream()
                    .mapToDouble(this::saldoPendiente)
                    .sum();
        }

        double gastosExtra = s.getGastosExtra().stream()
                .mapToDouble(CajaSession.GastoExtra::getMonto).sum();

        double montoActual = s.getMontoInicial() + pagos - prestamosOtorgados - gastosExtra;

        // Descuadre = diferencia entre lo que debería haber en caja
        // (montoActual, calculado) y lo que el usuario contó físicamente
        // (efectivoFisico). Positivo = falta plata; negativo = sobra plata.
        // null mientras no se haya registrado ningún conteo.
        Double efectivoFisico = s.getEfectivoFisico();
        Double descuadre = efectivoFisico != null ? (montoActual - efectivoFisico) : null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                 s.getId());
        m.put("abierta",            "abierta".equals(s.getEstado()));
        m.put("modo",               s.getModo());
        m.put("montoInicial",       s.getMontoInicial());
        m.put("iniciadaEn",         s.getIniciadaEn());
        m.put("finalizadaEn",       s.getFinalizadaEn());
        m.put("pagosRecibidos",     pagos);
        m.put("prestamosOtorgados", prestamosOtorgados);
        m.put("capitalActivoConInteres", capitalActivoConInteres);
        m.put("gastosExtra",        s.getGastosExtra());
        m.put("gastosExtraTotal",   gastosExtra);
        m.put("montoActual",        montoActual);
        m.put("montoFinal",         s.getMontoFinal());
        m.put("efectivoFisico",     efectivoFisico);
        m.put("descuadre",          descuadre);
        return m;
    }

    private String uid(HttpSession session) {
        return (String) session.getAttribute("userId");
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body("Unauthorized");
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }
}