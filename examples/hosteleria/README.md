# Paquete de sector: hostelería

Un bar sin programas, atendido por dos agentes (ADR 0007). Todo lo que es «de bar» está aquí, como
datos e instrucciones; el código de Cauce no sabe qué es una tapa:

| Fichero | Qué es |
|---|---|
| `agente-clientes.prompt.md` | Instrucciones del agente que atiende a los clientes |
| `agente-dueno.prompt.md` | Instrucciones del canal del dueño (fase 1: solo recibe avisos) |
| `herramientas.json` | Herramientas que se conceden a cada agente |
| `respaldo.json` | Texto que recibe la persona si su mensaje no se puede procesar (ADR 0005, C3) |
| `no-soportado.json` | Texto que recibe la persona si manda algo que Cauce aún no lee: una nota de voz, una foto (ADR 0006, W5) |
| `retencion-cache.json` | Cuánto pide cada agente que el proveedor guarde su prompt en caché: `LONG` (una hora) para el de clientes, cuyos mensajes llegan con minutos de diferencia; `SHORT` (minutos) para el del dueño (ADR 0010, S9) |
| `bar-ejemplo/ficha.json` | Ficha del bar de referencia: `PUT /v1/tenants/{id}/business-profile` |
| `bar-ejemplo/carta.json` | Carta con los 14 alérgenos UE como atributo declarado: `PUT .../catalog` |
| `bar-ejemplo/agenda.json` | Turnos, capacidad y días cerrados: `PUT .../agenda` |
| `evals/` | Escenarios de «bien hecho» de las cinco tareas (ver `evals/README.md`) |

Otro negocio (una peluquería, un taller) es otro paquete con la misma forma: otra ficha, otra
carta (servicios), otra agenda (sillas en vez de comensales) y otro prompt.

## Cargarlo

`scripts/demo-bar.sh` (o `scripts/demo-bar.ps1` en Windows) crea el negocio, carga ficha, carta y
agenda, crea los dos agentes y, si le das los tokens, conecta los dos bots de Telegram. Se puede
relanzar: reutiliza los ids de `.demo-bar.env` y, si ese fichero falta, busca por nombre el negocio
«Bar La Esquina» (bajo «Demo Hostelería») y sus agentes en la instancia antes de crear nada, así que
nunca hace un segundo negocio en la misma base de datos (si ya hay varios, usa el más antiguo). Vuelve
a cargar los datos y los prompts y, en cada
ejecución, renueva el secreto del webhook de cada bot ya vinculado, lo registra en Telegram y
comprueba con `getWebhookInfo` que quedó puesto. Si algo falla (token, `PUBLIC_URL`, Telegram),
para con el error; la siguiente ejecución lo repara sin crear vinculaciones nuevas.

Los ficheros también se cargan a mano con `curl` (cada uno es el cuerpo exacto del `PUT`), o desde
el playground, pantalla **Business**, que los edita como JSON y muestra cómo los lee el agente.

## Probarlo de extremo a extremo en Telegram

### Qué hace falta

- Una instancia de Cauce en marcha (el quickstart o `./gradlew :cauce-api:bootRun`) con un modelo
  capaz de usar herramientas. Por defecto el script usa `anthropic` / `claude-sonnet-5-5`, que
  necesita `ANTHROPIC_API_KEY` en el entorno de la instancia; cámbialo con `MODEL_PROVIDER` y
  `MODEL_NAME`. Un modelo local pequeño (`qwen2.5:3b`) sirve para la tubería, no para juzgar al
  agente. Con Ollama, arráncalo con `OLLAMA_CONTEXT_LENGTH=32768` (en el `.env` del quickstart, y
  en tu servidor si es tuyo): el prompt y las herramientas del camarero ocupan unos 5.000 tokens y
  cada turno suma la carta y la agenda que consulta. Con los 4096 que Ollama sirve por defecto,
  cada mensaje falla enseguida como `AGENT_MISCONFIGURED`, con el motivo dicho.
- La clave de operador (`CAUCE_API_KEY`; el quickstart la guarda en `.quickstart.env`).
- **Dos bots** creados con [@BotFather](https://t.me/BotFather): uno para los clientes y otro para
  el dueño. Guarda los dos tokens.
- Una URL pública que llegue a la instancia, por ejemplo
  `cloudflared tunnel --url http://localhost:8080` (Telegram tiene que poder llamar al webhook).

### 1. Cargar el bar y conectar los bots

```bash
export CAUCE_API_URL=http://localhost:8080
export CAUCE_API_KEY=ck_...                      # o deja que la lea de .quickstart.env
export TELEGRAM_CUSTOMER_BOT_TOKEN=123:abc...     # bot de clientes
export TELEGRAM_OWNER_BOT_TOKEN=456:def...        # bot del dueño
export PUBLIC_URL=https://<tu-tunel>.trycloudflare.com
scripts/demo-bar.sh
```

En PowerShell, las mismas variables con `$env:NOMBRE = '...'` y `scripts\demo-bar.ps1`.

El script imprime el id del negocio, el enlace del bot de clientes y un enlace
`https://t.me/<bot del dueño>?start=<código>`. El código vale 24 horas y solo una vez.

### 2. Vincular al dueño

Abre el enlace del dueño en el móvil del dueño y pulsa **Iniciar**. El bot responde «Listo: este
chat recibirá los avisos de Bar La Esquina.» Desde ese momento los casos y el resumen nocturno
llegan a ese chat. Comprobación: `GET /v1/tenants/{id}/owner` devuelve el chat vinculado.

Si ese chat con el bot ya existía (por ejemplo, de una prueba anterior), Telegram puede abrir el
enlace y enviar solo `/start`, sin el código: así pasó en la prueba real del 2026-10-22. El bot
contesta pidiendo el código: envíalo como mensaje (el código solo, o `/start CÓDIGO`). Para no
depender de eso, abre el enlace en un chat nuevo con el bot (bórralo antes) o manda el código a
mano. El log de la instancia dice cuál de los casos fue (`Owner bot <config>: ... a bare /start,
with no link code`, `... a link code that matches no code of this bot` o `... an owner chat
linked`), sin escribir nunca el texto ni el código. En el bot del dueño, un chat que no es el dueño
vinculado nunca llega al agente.

### 3. Una conversación por tarea (desde otro móvil, en el bot de clientes)

**Tarea 1 · Información del local**
- «¿Abrís los lunes?» → cerrado.
- «¿Tenéis wifi?» → no lo sabe, lo dice, y la pregunta queda en
  `GET /v1/tenants/{id}/unanswered-questions`. No promete que el dueño vaya a responder: eso solo
  pasa si abre un caso.

**Tarea 2 · Carta y alérgenos**
- «¿Las bravas llevan gluten?» → no; declara huevo.
- «¿Qué alérgenos tiene la tarta de queso?» → no consta (el bar no los declaró); la pregunta se
  registra.
- «Soy celíaco, ¿qué puedo comer?» → solo lo que no lleva gluten, y avisa de que de la tarta no
  consta.
- «¿Tenéis paella?» → no está en la carta.

**Tarea 3 · Reservas**
- «Mesa para 4 el sábado a las 21:30, soy Ana, 600 000 000» → te lee los datos (con la hora
  pedida; las plazas se cuentan por turno) y pregunta si confirmas. Todavía no hay reserva: `GET /v1/tenants/{id}/bookings` la muestra `HELD`.
- «Sí» → confirmada, con su código. Ahora está `CONFIRMED`.
- «Al final seremos 6» → propone el cambio; «sí» → la reserva queda cambiada y conserva su código
  (en la API, la versión nueva queda `CONFIRMED` y la anterior `CANCELLED`, con el mismo código).
- «Cancela mi reserva» → pide confirmación; «sí» → `CANCELLED`.
- «Mesa para 2 el lunes» → el lunes está cerrado.

**Tarea 4 · Escalar al dueño**
- «Quiero celebrar un cumpleaños de 25 personas el 14 de noviembre, ¿cuánto costaría?» → no da
  precio; pide nombre y teléfono; con ellos, pasa el caso. En el chat del dueño llega «Nuevo caso
  para ti [evento] · ref …». El caso está en `GET /v1/tenants/{id}/handoffs`; se cierra con
  `POST /v1/handoffs/{id}/resolve`.
- «La carne llegó fría, soy Luis, 655 555 555» → caso `queja`, sin compensaciones.
- «Somos 12 para cenar el viernes» → no reserva: caso `grupo`.

**Tarea 5 · Resumen nocturno**
- Llega solo a las 22:30 hora de Madrid (`daily_digest_time` de la ficha).
- Para no esperar: `POST /v1/tenants/{id}/daily-digests` (o el botón «Run today’s summary now» del
  playground). Al dueño le llega «Resumen de Bar La Esquina para mañana, …» con las reservas de
  mañana, los casos abiertos y las preguntas sin respuesta. Lanzarlo otra vez no envía un segundo
  resumen el mismo día.

Todos los avisos al dueño, enviados o no y por qué, están en
`GET /v1/tenants/{id}/owner-notifications`.

### Si algo no llega

- **El bot no responde**: `GET /v1/invocations/{id}` del mensaje, o el playground → Invocations.
  Revisa que el webhook está registrado (`https://api.telegram.org/bot<token>/getWebhookInfo`).
- **El dueño no recibe avisos**: si el aviso dice `no_owner_recipient`, el dueño no está
  vinculado; si dice `HTTP 403`, el dueño no ha pulsado Iniciar en su bot.
- **WhatsApp**: el mismo paquete funciona en WhatsApp, pero un aviso al dueño fuera de las 24 h
  desde su último mensaje queda `NOT_SENT` con `outside_reply_window` (no se envían plantillas).
