package pe.edu.reparaya.bot.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import pe.edu.reparaya.bot.application.usecase.SesionConversacion.EstadoSesion;
import pe.edu.reparaya.bot.infrastructure.web.controller.WhatsAppWebhookController.WhatsAppMessage;
import pe.edu.reparaya.shared.events.ReporteCreatedEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static pe.edu.reparaya.bot.shared.Constants.DEFAULT_LATITUDE;
import static pe.edu.reparaya.bot.shared.Constants.DEFAULT_LONGITUDE;

/**
 * Caso de uso del bot de WhatsApp.
 * Gestiona el flujo conversacional en múltiples pasos
 * y publica el evento report.created cuando el ciudadano completa el reporte.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotUseCase {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, Object>  redisTemplate;
    private final WhatsAppService                whatsAppService;

    private static final String TOPIC_REPORTE_CREATED = "report.created";
    private static final String SESSION_KEY_PREFIX    = "bot:session:";
    private static final Duration SESSION_TTL         = Duration.ofMinutes(30);

    // Menú de categorías
    private static final Map<String, String> CATEGORIAS = Map.of(
            "1", "VIALIDAD",
            "2", "ALUMBRADO",
            "3", "AGUA_POTABLE",
            "4", "ALCANTARILLADO",
            "5", "OTRO"
    );

    /**
     * Procesa un mensaje entrante de WhatsApp.
     */
    public void procesarMensaje(WhatsAppMessage mensaje) {
        String phone = mensaje.from();
        String texto = mensaje.text() != null ? mensaje.text().trim() : "";

        log.info("Mensaje recibido de {}: {}", phone, texto);

        SesionConversacion sesion = obtenerSesion(phone);

        // Comando de reinicio en cualquier momento
        if (texto.equalsIgnoreCase("HOLA") || texto.equalsIgnoreCase("INICIO")
                || texto.equalsIgnoreCase("REINICIAR")) {
            iniciarConversacion(phone);
            return;
        }

        switch (sesion.estado()) {
            case INICIO, COMPLETADO -> iniciarConversacion(phone);
            case ESPERANDO_CATEGORIA  -> procesarCategoria(sesion, texto);
            case ESPERANDO_DESCRIPCION -> procesarDescripcion(sesion, texto, mensaje.mediaUrl());
            case ESPERANDO_UBICACION  -> procesarUbicacion(sesion, texto);
        }
    }

    // ── Pasos del flujo ───────────────────────────────────────

    private void iniciarConversacion(String phone) {
        SesionConversacion sesion = SesionConversacion.nueva(phone);
        guardarSesion(phone, new SesionConversacion(
                phone, EstadoSesion.ESPERANDO_CATEGORIA,
                null, null, null, null, null));

        whatsAppService.enviarMensaje(phone,
                """
                👋 Bienvenido a *ReparaYa* — Municipalidad de Trujillo.

                Voy a ayudarte a reportar un problema de infraestructura.

                ¿Qué tipo de problema deseas reportar?

                1️⃣ Vialidad (baches, pistas, veredas)
                2️⃣ Alumbrado público
                3️⃣ Agua potable
                4️⃣ Alcantarillado
                5️⃣ Otro

                Responde con el número de tu opción.
                """);
    }

    private void procesarCategoria(SesionConversacion sesion, String texto) {
        String categoria = CATEGORIAS.get(texto);
        if (categoria == null) {
            whatsAppService.enviarMensaje(sesion.phoneNumber(),
                    "❌ Opción inválida. Por favor responde con un número del 1 al 5.");
            return;
        }

        SesionConversacion actualizada = sesion.conCategoria(categoria);
        guardarSesion(sesion.phoneNumber(), actualizada);

        whatsAppService.enviarMensaje(sesion.phoneNumber(),
                "✅ Categoría: *" + getNombreCategoria(categoria) + "*\n\n"
                + "Ahora describe el problema con el mayor detalle posible.\n"
                + "Puedes adjuntar una foto si lo deseas.");
    }

    private void procesarDescripcion(SesionConversacion sesion, String texto, String mediaUrl) {
        if (texto.isBlank() && mediaUrl == null) {
            whatsAppService.enviarMensaje(sesion.phoneNumber(),
                    "❌ Por favor describe el problema.");
            return;
        }

        String descripcion = texto.isBlank() ? "Sin descripción adicional" : texto;
        SesionConversacion actualizada = sesion.conDescripcion(descripcion);
        if (mediaUrl != null) {
            actualizada = actualizada.conMedia(mediaUrl);
        }
        guardarSesion(sesion.phoneNumber(), actualizada);

        whatsAppService.enviarMensaje(sesion.phoneNumber(),
                "📍 Casi listo. Ahora comparte tu *ubicación* para que podamos encontrar el problema.\n\n"
                + "En WhatsApp toca el clip 📎 → Ubicación → Enviar ubicación actual.");
    }

    private void procesarUbicacion(SesionConversacion sesion, String texto) {
        // La ubicación puede venir como coordenadas en el mensaje
        // o como texto (dirección manual como fallback)
        Double latitud  = sesion.latitud();
        Double longitud = sesion.longitud();

        // Si ya tiene coordenadas de la localización de WhatsApp
        if (latitud != null && longitud != null) {
            completarReporte(sesion);
            return;
        }

        // Fallback: pedir que escriba la dirección
        SesionConversacion actualizada = new SesionConversacion(
                sesion.phoneNumber(), EstadoSesion.COMPLETADO,
                sesion.categoria(), sesion.descripcion(),
                DEFAULT_LATITUDE, DEFAULT_LONGITUDE,
                sesion.mediaUrl()
        );
        guardarSesion(sesion.phoneNumber(), actualizada);
        completarReporte(actualizada);
    }

    /**
     * Maneja mensajes de localización de WhatsApp.
     */
    public void procesarUbicacionWhatsApp(String phone, double latitud, double longitud) {
        SesionConversacion sesion = obtenerSesion(phone);
        if (sesion.estado() == EstadoSesion.ESPERANDO_UBICACION) {
            SesionConversacion actualizada = sesion.conUbicacion(latitud, longitud);
            guardarSesion(phone, actualizada);
            completarReporte(actualizada);
        }
    }

    private void completarReporte(SesionConversacion sesion) {
        // Publicar evento en Kafka
        ReporteCreatedEvent event = ReporteCreatedEvent.of(
                sesion.phoneNumber(),
                sesion.categoria(),
                sesion.latitud(),
                sesion.longitud(),
                sesion.descripcion(),
                sesion.mediaUrl()
        );

        kafkaTemplate.send(TOPIC_REPORTE_CREATED, sesion.phoneNumber(), event);
        log.info("Evento report.created publicado para {}", sesion.phoneNumber());

        // Limpiar sesión
        redisTemplate.delete(SESSION_KEY_PREFIX + sesion.phoneNumber());

        whatsAppService.enviarMensaje(sesion.phoneNumber(),
                "✅ ¡Tu reporte ha sido registrado exitosamente!\n\n"
                + "📋 *Categoría:* " + getNombreCategoria(sesion.categoria()) + "\n"
                + "📝 *Descripción:* " + sesion.descripcion() + "\n\n"
                + "Te notificaremos cuando una empresa sea asignada y cuando el trabajo sea completado.\n\n"
                + "Gracias por contribuir a mejorar Trujillo 🏙️");
    }

    // ── Redis helpers ─────────────────────────────────────────

    private SesionConversacion obtenerSesion(String phone) {
        Object sesion = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + phone);
        if (sesion instanceof SesionConversacion s) return s;
        return SesionConversacion.nueva(phone);
    }

    private void guardarSesion(String phone, SesionConversacion sesion) {
        redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + phone, sesion, SESSION_TTL);
    }

    private String getNombreCategoria(String categoria) {
        return switch (categoria) {
            case "VIALIDAD"       -> "Vialidad";
            case "ALUMBRADO"      -> "Alumbrado público";
            case "AGUA_POTABLE"   -> "Agua potable";
            case "ALCANTARILLADO" -> "Alcantarillado";
            default               -> "Otro";
        };
    }
}
