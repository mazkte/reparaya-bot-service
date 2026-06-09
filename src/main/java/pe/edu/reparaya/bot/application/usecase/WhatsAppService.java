package pe.edu.reparaya.bot.application.usecase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Servicio de envío de mensajes via WhatsApp Business Cloud API (Meta).
 */
@Slf4j
@Service
public class WhatsAppService {

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL =
            "https://graph.facebook.com/v21.0/{phoneNumberId}/messages";

    /**
     * Envía un mensaje de texto al número indicado.
     */
    public void enviarMensaje(String to, String texto) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);

            Map<String, Object> body = Map.of(
                    "messaging_product", "whatsapp",
                    "recipient_type",    "individual",
                    "to",                to,
                    "type",              "text",
                    "text",              Map.of("body", texto)
            );

            ResponseEntity<String> response = restTemplate.exchange(
                    API_URL,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class,
                    phoneNumberId
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Mensaje enviado a {}", to);
            } else {
                log.warn("Error enviando mensaje a {}: {}", to, response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Error enviando mensaje WhatsApp a {}: {}", to, e.getMessage());
        }
    }
}
