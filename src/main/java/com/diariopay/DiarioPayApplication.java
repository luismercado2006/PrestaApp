package com.diariopay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class DiarioPayApplication {

    // Se fija ANTES de que arranque Spring, en un bloque estático, para que
    // absolutamente todo el backend (LocalDate.now(), LocalDateTime.now(),
    // el cron del MoraScheduler, timestamps guardados, etc.) use la hora de
    // Colombia (UTC-5, sin horario de verano) sin importar en qué zona
    // horaria esté el servidor donde se despliegue la app.
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Bogota"));
    }

    public static void main(String[] args) {
        SpringApplication.run(DiarioPayApplication.class, args);
    }
}