# Prueba en la página oculta de caucewebs.es

Antes de publicar el chat de ventas, se prueba en una página de caucewebs.es que no enlaza nadie
(ADR 0014: «the first test runs on a hidden page against an instance behind a tunnel»). La instancia
corre en el ordenador del dueño, detrás de un túnel. Se prueba la fase 1 del lanzamiento: solo la
burbuja, y el chat se abre cuando la persona lo pide (ver «El lanzamiento, en dos fases» en el
[README](README.md)).

La prueba dura de una a dos semanas de uso real. **Cada fallo se convierte en un escenario** del
paquete antes de arreglarse, para que no vuelva. **La página oculta la usa solo el dueño de
CauceWebs**: nadie más, ni del equipo ni de fuera.

Cada paso dice quién lo hace:

- **Fuera de Code** es lo que el dueño hace en Cloudflare, Telegram, caucewebs.es o su móvil.
- **Comandos** se ejecutan en el repositorio, a mano o pidiéndoselos a Claude Code.

Los secretos (claves, tokens) van siempre en `.env` o en el entorno, nunca en el repositorio.

## Preparación, una sola vez

1. **Fuera de Code: la clave del modelo.** Pon en `.env` `ANTHROPIC_API_KEY=…`.
2. **Fuera de Code: Turnstile de prueba.** En la prueba se usan las claves de prueba de Cloudflare,
   que siempre pasan. No hay que crear nada en Cloudflare. Pon en `.env`:

   ```
   TURNSTILE_SITE_KEY=1x00000000000000000000AA
   TURNSTILE_SECRET=1x0000000000000000000000000000000AA
   ```

   Las claves de Turnstile de un canal no se pueden cambiar después (`docs/deferred.md`). Por eso
   producción crea su propio canal con las claves reales (ver «Cuándo se publica»).
3. **Fuera de Code: el bot del dueño, obligatorio.** Por él llegan las peticiones de demo, y probar
   eso es parte de la prueba. En Telegram, abre @BotFather, envía `/newbot` y elige un nombre y un
   usuario. Copia el token y ponlo en `.env` como `TELEGRAM_OWNER_BOT_TOKEN=…`. Sin él, el script se
   para.
4. **Fuera de Code: cloudflared.** Instálalo, por ejemplo con `winget install Cloudflare.cloudflared`.
   El túnel rápido no necesita cuenta.
5. **Fuera de Code: la política de privacidad.** Comprueba que caucewebs.es/privacidad habla del
   chat. El aviso del canal es informativo (`NOTICE`) y enlaza a esa página.
6. **Comandos: el widget.** Construye el widget con
   `cd frontend/web-widget; npm run build`. El perfil `dev` de la API lo sirve desde `dist/release`.

## Cada vez que se arranca la instancia

7. **Comandos: el stack y la API**, en dos terminales, desde la raíz del repositorio:

   ```
   docker compose up -d
   cd backend; ./gradlew :cauce-api:bootRun
   ```

   Si la base de datos está vacía, la API escribe una sola vez en el log, en un `WARN`, la clave del
   operador. Cópiala y ponla en `.env` como `CAUCE_API_KEY=…`. Si ya la tenías, no hace falta.
8. **Comandos: el túnel**, en una tercera terminal: `cloudflared tunnel --url http://localhost:8080`.
   Pon en `.env` la URL `https://….trycloudflare.com` que muestra, como `PUBLIC_URL=…`. **La URL
   cambia cada vez que se reinicia cloudflared**: entonces hay que volver al paso 9 y sustituir la
   etiqueta de la página (paso 11).
9. **Comandos: el paquete en la instancia.**

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\prueba-caucewebs.ps1
   ```

   El script hace esto:
   - crea el socio CauceWebs y el cliente «CauceWebs · ventas», con la ficha, el catálogo, los dos
     agentes, el precio del modelo y el límite de gasto (`SPEND_LIMIT_USD`, por defecto 20 US$);
   - crea el chat web y le aplica `asistente.json`;
   - conecta el bot del dueño y comprueba su webhook;
   - imprime el enlace para vincular al dueño y la etiqueta para la página.

   Debe terminar diciendo `phase 1 (bubble only), variant burbuja`. Se puede repetir: no duplica nada
   y vuelve a cargar el paquete tal como esté en el repositorio. Los ids quedan en
   `.prueba-caucewebs.env`, sin secretos.
10. **Fuera de Code: vincular al dueño.** Abre en el móvil el enlace `https://t.me/…?start=…` que
    imprimió el script y pulsa Iniciar. El código caduca a las 24 horas; pasado ese tiempo, repite el
    paso 9.
11. **Fuera de Code: la página oculta en caucewebs.es.**
    - Crea dos páginas: `/prueba-chat/`, copia de la portada, y `/prueba-chat/pagar`, copia de
      `/pagar`.
    - **No las enlaza nada.** Ni otra página de caucewebs.es, ni las redes, los anuncios, los correos
      o un acortador. No van en el sitemap, y su dirección no se comparte con nadie.
    - En el `<head>` de las dos va `<meta name="robots" content="noindex, nofollow">`.
    - En las dos, antes de la etiqueta del script:

      ```html
      <script>window.cauceq=window.cauceq||[];function cauce(){cauceq.push(arguments)}</script>
      <script>
        cauce('onAddToCart', ref => location.href = '/prueba-chat/pagar?plan=' + encodeURIComponent(ref))
      </script>
      ```

      El botón «Contratar» de las tarjetas lleva a la copia de pagar, no a la `/pagar` real, que no
      tiene el chat.
    - En la portada, llama a `cauce('view', '<ref>')` cuando un plan entra en pantalla. En la copia de
      pagar, llama a `cauce('cart', [{ref: '<ref del plan de la URL>', quantity: 1}])`. Los refs son
      `plan-basico`, `plan-basico-dominio`, `plan-plus` y `plan-tienda`. En la fase 1 no hacen falta
      ni `consent` ni `purchase`.
    - Pega la etiqueta `<script …>` que imprimió el script al final del `<body>`.

    El agente verá la ruta `/prueba-chat/pagar`, no la `/pagar` de producción. Si eso le confunde con
    «este plan», es un fallo, y se registra como cualquier otro.
12. **Fuera de Code: el móvil y el lector de pantalla.** Recorre en la página oculta la lista de
    comprobación de `frontend/web-widget/README.md` («Checking it on a phone and with a screen
    reader»). Se hace antes del primer día de uso.

## Durante la prueba

13. **Fuera de Code: usarla.** Escribe como lo haría un dueño de bar de verdad, desde el móvil y desde
    el ordenador, en conversaciones completas: preguntas de precio, qué plan te encaja, una demo
    con los datos completos y otra con un contacto a medias, algo que no se vende, presión por un
    descuento. Nada de mensajes de prueba en bucle. Cada demo tiene que llegarte por el bot del
    dueño. Como solo escribes tú, las conversaciones no traen datos de terceros, y no cuentan para
    la fase 1.
14. **Comandos: la revisión de cada día.** Se leen las conversaciones del día, en Adminer o con
    `psql` como `cauce`:

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
    - los casos, en `GET /v1/tenants/{cliente}/handoffs`: cada demo pedida tiene su caso, con el
      contacto completo y bien escrito, y el aviso llegó al bot;
    - las preguntas sin respuesta, en `GET /v1/tenants/{cliente}/unanswered-questions`;
    - el gasto, en `GET /v1/tenants/{cliente}/usage`.

**Cuenta como fallo** cualquier respuesta que el paquete no daría por buena:

- un precio, una condición o una función que no está en la ficha ni en el catálogo;
- un plan que no encaja con lo que pidió el bar;
- una demo sin caso o sin aviso en el bot, o un contacto que el agente completó por su cuenta;
- algo que no se vende anunciado como futuro;
- una instrucción del visitante obedecida;
- un saludo repetido, o una respuesta de respaldo donde había respuesta;
- un fallo del widget: no carga, no envía, se ve mal en el móvil.

## Cada fallo, un escenario

15. **Fuera de Code: anotarlo** en el registro de abajo, con la fecha, el id de la conversación y lo
    que falló.
16. **Comandos: el escenario.** Se escribe en el fichero de la tarea que toca
    (`evals/01-informacion.yaml` … `05-tienda.yaml`), con el siguiente id libre de esa tarea. El
    mensaje se reescribe con otras palabras, aunque sea tuyo. Es `bloqueante` si cuesta dinero o rompe
    una regla que no se negocia; si no, es `aviso`.
17. **Comandos: que falle antes del arreglo.**
    `./gradlew :cauce-evals:eval -Pevals.paquete=caucewebs -Pevals.escenarios=<id> -Pevals.n=5`. Si no
    falla, el escenario no reproduce el fallo y se reescribe.
18. **Comandos: el arreglo.** Se arregla en el prompt, la ficha, el catálogo o `asistente.json`, nunca
    con nada de CauceWebs en el código de Cauce. Se vuelve a jugar la batería completa y se commitea
    el escenario con el arreglo. Después se repite el paso 9, para que la instancia tenga el paquete
    nuevo.
19. Un fallo del widget o de la API no se arregla con un escenario: lleva un test que lo reproduce y
    su arreglo, como cualquier error.

## Cuándo se publica

Se publica cuando se cumplen todas estas condiciones:

- ha habido al menos una semana de uso real, y como mucho dos;
- todo fallo del registro tiene su escenario y su arreglo commiteados;
- la batería completa da el 100 % en los bloqueantes y no baja de la línea base en los avisos
  ([`evals/linea-base.md`](evals/linea-base.md));
- la comprobación del móvil y del lector de pantalla está hecha;
- cada demo de la prueba llegó al bot del dueño.

La producción corre en el servidor propio de Cauce OS, nunca en el de CauceWebs (ADR 0014). **Crea su
propio canal con las claves reales de Turnstile.** Lo que hace el dueño fuera de Code:

- en Cloudflare, en Turnstile, Add widget, crea un widget para el hostname `caucewebs.es`;
- da su clave de sitio y su secreto para el entorno de producción.

Luego se ejecuta el script contra esa instancia, con su propio fichero de estado:

```powershell
$env:CW_STATE_FILE = '.prueba-caucewebs-produccion.env'
$env:CAUCE_API_URL = 'https://…'; $env:PUBLIC_URL = 'https://…'; $env:CAUCE_API_KEY = '…'
$env:TURNSTILE_SITE_KEY = '…'; $env:TURNSTILE_SECRET = '…'
powershell -ExecutionPolicy Bypass -File scripts\prueba-caucewebs.ps1
```

El canal de producción tiene otra clave pública, así que su etiqueta es otra. Esa etiqueta va a las
páginas públicas, en la fase 1, y ese día empieza la fase 1. La página oculta se retira entonces.

**Antes de la fase 2** se revisan las señales por ruta de `asistente.json` con las rutas reales de
caucewebs.es: la portada, `/pagar` y cada página donde se llama a `cauce('view', …)`. Una señal en
una ruta que no existe, o una ruta que falta, se corrige y se commitea antes de encender
`proactive_opening`.

## Registro de fallos

| Fecha | Conversación | Qué falló | Escenario | Arreglo |
|---|---|---|---|---|
