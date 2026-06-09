# reparaya-bot-service

Microservicio de integración con WhatsApp Business Cloud API (Meta).
Gestiona el flujo conversacional con ciudadanos y publica eventos `report.created` en Kafka.

## Puerto: `8081`

## Flujo conversacional

```
Ciudadano → WhatsApp → Meta Cloud API → webhook → bot-service
                                                       ↓
                                              Sesión en Redis
                                                       ↓
                                         Flujo multi-paso:
                                         1. Saludo/inicio
                                         2. Selección de categoría (1-5)
                                         3. Descripción del problema (+ foto opcional)
                                         4. Ubicación (compartir ubicación WhatsApp)
                                                       ↓
                                         Kafka: report.created
                                                       ↓
                                    worker-service → report-service
```

## Configuración del Webhook en Meta Developers

### 1. Levantar el bot-service
```powershell
mvn spring-boot:run
```

### 2. Exponer con ngrok
```powershell
ngrok http 8081
# Copia la URL: https://abc123.ngrok-free.app
```

### 3. Configurar en Meta Developers
1. Ve a https://developers.facebook.com → tu app → WhatsApp → Configuration
2. **Webhook URL**: `https://abc123.ngrok-free.app/api/bot/webhook`
3. **Verify Token**: `reparaya-webhook-2026`
4. Suscribir al campo: `messages`

### 4. Configurar el número de prueba
En Meta Developers → WhatsApp → API Setup:
- Agrega tu número personal como número de prueba
- El bot solo responde a números aprobados en modo desarrollo

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/bot/webhook` | Verificación del webhook por Meta |
| POST | `/api/bot/webhook` | Recepción de mensajes de WhatsApp |
| POST | `/api/bot/simulate` | Simular mensaje (desarrollo) |
| POST | `/api/bot/simulate/location` | Simular ubicación (desarrollo) |

## Prueba sin WhatsApp real

```bash
# Paso 1 — Iniciar conversación
curl -X POST http://localhost:8081/api/bot/simulate \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "+51987654321", "text": "HOLA"}'

# Paso 2 — Seleccionar categoría
curl -X POST http://localhost:8081/api/bot/simulate \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "+51987654321", "text": "1"}'

# Paso 3 — Descripción
curl -X POST http://localhost:8081/api/bot/simulate \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "+51987654321", "text": "Bache profundo en Av. España"}'

# Paso 4 — Ubicación
curl -X POST http://localhost:8081/api/bot/simulate/location \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "+51987654321", "latitud": -8.1116, "longitud": -79.0352}'
```

## Credenciales Meta (configurar en .env)

| Variable | Valor |
|---|---|
| `WHATSAPP_PHONE_NUMBER_ID` | `1131751816692532` |
| `WHATSAPP_BUSINESS_ACCOUNT_ID` | `1131440080054091` |
| `WHATSAPP_ACCESS_TOKEN` | Token de acceso (renovar cada 24h en dev) |
| `WHATSAPP_VERIFY_TOKEN` | `reparaya-webhook-2026` |
