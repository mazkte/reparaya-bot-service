package pe.edu.reparaya.bot.infrastructure.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.reparaya.bot.application.usecase.BotUseCase;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/bot")
@RequiredArgsConstructor
@Tag(name = "Bot WhatsApp", description = "Webhook de WhatsApp Business API y simulación")
public class WhatsAppWebhookController {

    private final BotUseCase botUseCase;

    @Value("${whatsapp.verify-token}")
    private String verifyToken;

    // ── VERIFICACIÓN DEL WEBHOOK (GET) ────────────────────────
    // Meta llama a este endpoint para verificar el webhook

    @GetMapping("/webhook")
    @Operation(summary = "Verificación del webhook por Meta")
    public ResponseEntity<String> verificarWebhook(
            @RequestParam("hub.mode")         String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge")    String challenge) {

        log.info("Verificación de webhook: mode={}", mode);

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("Webhook verificado correctamente");
            return ResponseEntity.ok(challenge);
        }

        log.warn("Verificación de webhook fallida");
        return ResponseEntity.status(403).body("Forbidden");
    }

    // ── RECEPCIÓN DE MENSAJES (POST) ──────────────────────────
    // Meta envía los mensajes entrantes aquí

    @PostMapping("/webhook")
    @Operation(summary = "Recepción de mensajes de WhatsApp")
    public ResponseEntity<String> recibirMensaje(@RequestBody Map<String, Object> payload) {
        log.debug("Webhook recibido: {}", payload);

        try {
            // Parsear la estructura de Meta Cloud API
            List<?> entries = (List<?>) payload.get("entry");
            if (entries == null || entries.isEmpty()) return ResponseEntity.ok("OK");

            for (Object entry : entries) {
                Map<?, ?> entryMap = (Map<?, ?>) entry;
                List<?> changes = (List<?>) entryMap.get("changes");
                if (changes == null) continue;

                for (Object change : changes) {
                    Map<?, ?> changeMap  = (Map<?, ?>) change;
                    Map<?, ?> value      = (Map<?, ?>) changeMap.get("value");
                    if (value == null) continue;

                    List<?> messages = (List<?>) value.get("messages");
                    if (messages == null) continue;

                    for (Object msg : messages) {
                        Map<?, ?> msgMap = (Map<?, ?>) msg;
                        procesarMensajeEntrante(msgMap);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error procesando webhook: {}", e.getMessage(), e);
        }

        // Siempre responder 200 a Meta para evitar reintentos
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    @SuppressWarnings("unchecked")
    private void procesarMensajeEntrante(Map<?, ?> msgMap) {
        String from = (String) msgMap.get("from");
        String type = (String) msgMap.get("type");

        if (from == null || type == null) return;

        // Formatear número con +
        String phone = from.startsWith("+") ? from : "+" + from;

        switch (type) {
            case "text" -> {
                Map<?, ?> textObj = (Map<?, ?>) msgMap.get("text");
                String body = textObj != null ? (String) textObj.get("body") : "";
                botUseCase.procesarMensaje(new WhatsAppMessage(phone, body, null));
            }
            case "image" -> {
                Map<?, ?> imageObj = (Map<?, ?>) msgMap.get("image");
                String caption  = imageObj != null ? (String) imageObj.get("caption") : "";
                String mediaId  = imageObj != null ? (String) imageObj.get("id") : null;
                botUseCase.procesarMensaje(new WhatsAppMessage(phone, caption, mediaId));
            }
            case "location" -> {
                Map<?, ?> locObj = (Map<?, ?>) msgMap.get("location");
                if (locObj != null) {
                    double lat = ((Number) locObj.get("latitude")).doubleValue();
                    double lng = ((Number) locObj.get("longitude")).doubleValue();
                    botUseCase.procesarUbicacionWhatsApp(phone, lat, lng);
                }
            }
            default -> log.debug("Tipo de mensaje no manejado: {}", type);
        }
    }

    // ── SIMULACIÓN (para pruebas sin WhatsApp real) ───────────

    @PostMapping("/simulate")
    @Operation(summary = "Simular mensaje de WhatsApp (solo desarrollo)")
    public ResponseEntity<String> simular(@RequestBody SimulateRequest request) {
        log.info("Simulando mensaje de {}: {}", request.phoneNumber(), request.text());
        botUseCase.procesarMensaje(
                new WhatsAppMessage(request.phoneNumber(), request.text(), null));
        return ResponseEntity.ok("Mensaje procesado");
    }

    @PostMapping("/simulate/location")
    @Operation(summary = "Simular envío de ubicación (solo desarrollo)")
    public ResponseEntity<String> simularUbicacion(@RequestBody SimulateLocationRequest request) {
        botUseCase.procesarUbicacionWhatsApp(
                request.phoneNumber(), request.latitud(), request.longitud());
        return ResponseEntity.ok("Ubicación procesada");
    }

    // ── Records ───────────────────────────────────────────────

    public record WhatsAppMessage(
            String from,
            String text,
            String mediaUrl
    ) {}

    public record SimulateRequest(
            String phoneNumber,
            String text
    ) {}

    public record SimulateLocationRequest(
            String phoneNumber,
            double latitud,
            double longitud
    ) {}
}
