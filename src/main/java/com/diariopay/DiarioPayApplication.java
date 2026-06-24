package com.diariopay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DiarioPayApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiarioPayApplication.class, args);
    }
}