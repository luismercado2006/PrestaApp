package com.diariopay.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WhatsAppService {

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.whatsapp.from}")
    private String fromNumber;

    public void enviarMensajeMora(String telefono, String nombrePrestatario, double monto, String nombrePrestamista) {
        Twilio.init(accountSid, authToken);

        String mensaje = String.format(
                "⚠️ Hola %s, tu préstamo de $%.0f está en *MORA*. " +
                        "Por favor comunícate para ponerte al día. %s.",
                nombrePrestatario, monto, nombrePrestamista
        );

        Message.creator(
                new PhoneNumber("whatsapp:+" + telefono),
                new PhoneNumber(fromNumber),
                mensaje
        ).create();
    }
}