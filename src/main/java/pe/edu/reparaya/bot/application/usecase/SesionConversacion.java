package pe.edu.reparaya.bot.application.usecase;

import java.io.Serializable;

/**
 * Estado de la conversación WhatsApp del ciudadano.
 * Se persiste en Redis con TTL de 30 minutos.
 *
 * Flujo conversacional:
 * INICIO → ESPERANDO_CATEGORIA → ESPERANDO_DESCRIPCION → ESPERANDO_UBICACION → COMPLETADO
 */
public record SesionConversacion(
        String phoneNumber,
        EstadoSesion estado,
        String categoria,
        String descripcion,
        Double latitud,
        Double longitud,
        String mediaUrl
) implements Serializable {

    public enum EstadoSesion {
        INICIO,
        ESPERANDO_CATEGORIA,
        ESPERANDO_DESCRIPCION,
        ESPERANDO_UBICACION,
        COMPLETADO
    }

    public static SesionConversacion nueva(String phoneNumber) {
        return new SesionConversacion(
                phoneNumber, EstadoSesion.INICIO,
                null, null, null, null, null
        );
    }

    public SesionConversacion conCategoria(String categoria) {
        return new SesionConversacion(phoneNumber, EstadoSesion.ESPERANDO_DESCRIPCION,
                categoria, descripcion, latitud, longitud, mediaUrl);
    }

    public SesionConversacion conDescripcion(String descripcion) {
        return new SesionConversacion(phoneNumber, EstadoSesion.ESPERANDO_UBICACION,
                categoria, descripcion, latitud, longitud, mediaUrl);
    }

    public SesionConversacion conUbicacion(Double latitud, Double longitud) {
        return new SesionConversacion(phoneNumber, EstadoSesion.COMPLETADO,
                categoria, descripcion, latitud, longitud, mediaUrl);
    }

    public SesionConversacion conMedia(String mediaUrl) {
        return new SesionConversacion(phoneNumber, estado,
                categoria, descripcion, latitud, longitud, mediaUrl);
    }
}
