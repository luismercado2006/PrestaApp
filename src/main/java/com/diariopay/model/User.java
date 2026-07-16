package com.diariopay.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data
@Document(collection = "users")
public class User {
    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    private String password;
    private String name;

    @Indexed(unique = true, sparse = true)
    private String email;

    private String role = "USER";
    private LocalDateTime createdAt = LocalDateTime.now();

    /* ── PREMIUM / SUSCRIPCIÓN ──
       premiumExpiresAt: fecha hasta la cual la cuenta tiene acceso (se fija en
       el registro como createdAt + 1 mes, simulando el periodo de prueba/plan pagado).

       premiumOverride: control manual desde la base de datos.
         - null  -> automático: se usa premiumExpiresAt para decidir si está activa.
         - true  -> el admin activó manualmente la cuenta (aunque la fecha ya venció).
         - false -> el admin desactivó manualmente la cuenta (aunque la fecha no haya vencido).

       Para reactivar una cuenta vencida desde Mongo:
           db.users.updateOne({username:"..."}, {$set:{premiumOverride:true}})
       Para volver al modo automático (que respete la fecha de nuevo):
           db.users.updateOne({username:"..."}, {$unset:{premiumOverride:""}})
       Para desactivar una cuenta manualmente:
           db.users.updateOne({username:"..."}, {$set:{premiumOverride:false}})
    */
    private LocalDateTime premiumExpiresAt;
    private Boolean premiumOverride;

    /* ── PRUEBA GRATUITA CORTA (solo cuentas nuevas) ──
       pruebaExpiraEn: límite del acceso automático de prueba para cuentas creadas
       DESPUÉS de este cambio (2 días desde el registro). Al vencer, la cuenta
       queda bloqueada automáticamente aunque premiumExpiresAt (el "mes completo")
       siga corriendo desde la fecha de creación sin verse afectado.

       Si el dueño paga, el admin simplemente presiona "Activar" en el panel
       (premiumOverride = true) y la cuenta queda activa; el mes contratado sigue
       contando desde el día en que se creó la cuenta (premiumExpiresAt no cambia).

       Las cuentas registradas ANTES de este cambio no tienen este campo (queda
       null), por lo que conservan el comportamiento clásico: 1 mes completo de
       acceso automático, sin bloqueo a los 2 días. No requieren ninguna migración.
    */
    private LocalDateTime pruebaExpiraEn;

    public boolean isPremiumActive() {
        if (premiumOverride != null) {
            return premiumOverride;
        }
        if (pruebaExpiraEn != null) {
            // Cuenta nueva (post-cambio): solo acceso automático durante la prueba corta.
            return LocalDateTime.now().isBefore(pruebaExpiraEn);
        }
        // Cuenta antigua (sin pruebaExpiraEn): comportamiento clásico, 1 mes automático.
        return LocalDateTime.now().isBefore(getEffectivePremiumExpiresAt());
    }

    /** Fecha efectiva de vencimiento: usa premiumExpiresAt si existe,
     *  o createdAt + 1 mes para cuentas antiguas que no tenían este campo. */
    public LocalDateTime getEffectivePremiumExpiresAt() {
        if (premiumExpiresAt != null) return premiumExpiresAt;
        if (createdAt != null) return createdAt.plusMonths(1);
        return LocalDateTime.now().plusMonths(1);
    }
}