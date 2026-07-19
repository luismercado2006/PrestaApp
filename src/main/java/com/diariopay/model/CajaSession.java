package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "caja_sessions")
public class CajaSession {
    @Id
    private String id;

    private String userId;

    // "prestamos" -> descuenta lo prestado del valor en caja
    // "simple"    -> solo suma los pagos recibidos
    private String modo;

    private double montoInicial;

    // "abierta" | "cerrada"
    private String estado;

    private LocalDateTime iniciadaEn;
    private LocalDateTime finalizadaEn;

    // Se fija cuando se finaliza la caja (queda como "foto" del cierre)
    private double montoFinal;

    // Plata en físico contada manualmente por el usuario (arqueo de caja).
    // null mientras no se haya registrado ningún conteo.
    private Double efectivoFisico;

    private List<GastoExtra> gastosExtra = new ArrayList<>();

    @Data
    public static class GastoExtra {
        private String id;
        private double monto;
        private String nota;
        private LocalDateTime fecha;
    }
}