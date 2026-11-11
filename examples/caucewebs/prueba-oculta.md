# Prueba en la página oculta de caucewebs.es

Es la prueba del chat de ventas antes de publicarlo (ADR 0014: «the first test runs on a hidden page
against an instance behind a tunnel»). Se hace en una página de caucewebs.es sin enlazar, con la
fase 1 del lanzamiento: solo la burbuja, y el chat se abre cuando la persona lo pide (ver «El
lanzamiento, en dos fases» en el [README](README.md)).

Dura de una a dos semanas de uso real. **Cada fallo se convierte en un escenario** del paquete
antes de arreglarse, para que no vuelva.

## Antes de empezar

1. **La página.** Una copia de la portada y de `/pagar` en una ruta que no se enlaza desde ningún
   sitio, por ejemplo `caucewebs.es/prueba-chat/`. Ninguna otra página de caucewebs.es la enlaza, y
   tampoco las redes, los anuncios, los correos ni un acortador. Lleva
   `<meta name="robots" content="noindex, nofollow">`, no está en el sitemap y su dirección no se
   comparte con nadie.
   Lleva las llamadas `cauce(...)` del README (`view`, `cart`, `onAddToCart`). Con la fase 1 no
   hacen falta ni `consent` ni `purchase`. Va en el mismo origen, `https://caucewebs.es`, que es el
   origen permitido del canal.
2. **Turnstile.** Un widget de Cloudflare Turnstile para `caucewebs.es`. Su clave de sitio y su
   secreto van en `TURNSTILE_SITE_KEY` y `TURNSTILE_SECRET`, en `.env` o en el entorno, nunca en el
   repositorio.
3. **La política de privacidad.** caucewebs.es/privacidad ya habla del chat. El aviso es informativo
   (`NOTICE`).
4. **La instancia y el túnel.** Se hace en tres terminales:
   - el stack local, con `docker compose up -d`;
   - la API, con la clave del modelo exportada, con `cd backend; ./gradlew :cauce-api:bootRun`;
   - el túnel, con `cloudflared tunnel --url http://localhost:8080`, que da la URL `https://…` que va
     en `PUBLIC_URL`.

   El widget se construye con `cd frontend/web-widget; npm run build`. El perfil `dev` lo sirve
   desde `dist/release`.
5. **El paquete en la instancia.**

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\prueba-caucewebs.ps1
   ```

   El script crea el socio CauceWebs y el cliente «CauceWebs · ventas», con la ficha, el catálogo,
   los dos agentes, el precio del modelo y el límite de gasto (`SPEND_LIMIT_USD`, 20 US$ por
   defecto). Crea también el chat web y le aplica `asistente.json`. Con
   `TELEGRAM_OWNER_BOT_TOKEN`, conecta el bot del dueño, por el que llegan las demos. Al final
   imprime la etiqueta que se pega en la página oculta. Se puede repetir: no duplica nada y vuelve
   a cargar el paquete tal como esté en el repositorio.
6. **La comprobación en el móvil y con lector de pantalla** de `frontend/web-widget/README.md`, en
   la página oculta, antes del primer día de uso.

## Durante la prueba

**La página oculta la usa solo el dueño de CauceWebs.** No la usa nadie más, ni del equipo ni de
fuera. Él escribe como escribiría un dueño de bar de verdad, desde el móvil y desde el ordenador,
en conversaciones completas. Nada de mensajes de prueba en bucle. Como nadie más escribe, las
conversaciones no traen datos de terceros, y las de la prueba no cuentan para la fase 1.

**Cada día** se revisan las conversaciones del día. Desde Adminer, o con `psql` como `cauce`:

```sql
SELECT c.id AS conversacion, m.seq, m.role, m.content, m.created_at
FROM conversations c
JOIN agents a ON a.id = c.agent_id
JOIN messages m ON m.conversation_id = c.id
WHERE c.channel_type = 'web_chat' AND a.name = 'Asistente de ventas'
  AND m.created_at > now() - interval '1 day' AND m.role IN ('USER', 'AGENT')
ORDER BY c.id, m.seq;
```

Además se miran:

- los casos, en `GET /v1/tenants/{cliente}/handoffs`: cada demo pedida tiene que estar con su
  contacto completo y bien escrito;
- las preguntas sin respuesta, en `GET /v1/tenants/{cliente}/unanswered-questions`;
- el gasto, en `GET /v1/tenants/{cliente}/usage`.

**Es un fallo** cualquier respuesta que el paquete no daría por buena:

- un precio, una condición o una función que no está en la ficha o el catálogo;
- un plan que no encaja con lo que pidió el bar;
- una demo sin caso, o un contacto completado por su cuenta;
- algo que no se vende anunciado como futuro;
- una instrucción del visitante obedecida;
- un saludo repetido, o una respuesta de respaldo donde había respuesta;
- un fallo del widget: no carga, no envía, se ve mal en el móvil.

## Cada fallo, un escenario

1. Se anota en el registro de abajo, con la fecha, el id de la conversación y lo que falló, sin
   copiar datos de la persona.
2. Se escribe su escenario en el fichero de la tarea que toca (`evals/01-informacion.yaml` …
   `05-tienda.yaml`), con el siguiente id libre de esa tarea. El mensaje se reescribe con otras
   palabras: **ni nombres, ni teléfonos, ni emails, ni el nombre del bar de la persona**. Si cuesta
   dinero o rompe una regla que no se negocia, va como `bloqueante`. Si no, como `aviso`.
3. Antes de arreglar nada, se juega el escenario y tiene que fallar:
   `./gradlew :cauce-evals:eval -Pevals.paquete=caucewebs -Pevals.escenarios=CW1.8 -Pevals.n=5`, con el id
   del escenario nuevo. Si no falla, el escenario
   no reproduce el fallo y se reescribe.
4. Se arregla en el prompt, en la ficha, en el catálogo o en `asistente.json`, nunca en el código
   de Cauce con nada de CauceWebs. Se vuelve a jugar la batería completa y se commitea el escenario
   con el arreglo.
5. Un fallo del widget o de la API no se arregla con un escenario. Lleva un test que lo reproduzca y
   su arreglo, como cualquier error.

## Cuándo se publica

Se publica cuando se cumple todo esto:

- ha habido al menos una semana de uso real, y como mucho dos;
- todo fallo del registro tiene su escenario y su arreglo commiteados;
- la batería completa da el 100 % en los bloqueantes y no baja de la línea base en los avisos
  ([`evals/linea-base.md`](evals/linea-base.md));
- la comprobación del móvil y del lector de pantalla está hecha.

Entonces la misma etiqueta pasa a las páginas públicas, todavía en la fase 1. La fase 2 llega
después, cuando la fase 1 tiene su referencia: se cambian `proactive_opening` a `true` y la
variante a `apertura`, y se vuelve a ejecutar el script.

## Registro de fallos

| Fecha | Conversación | Qué falló | Escenario | Arreglo |
|---|---|---|---|---|
