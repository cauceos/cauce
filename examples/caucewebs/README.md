# Paquete de tienda: CauceWebs

La primera tienda del canal web (ADR 0014, enmienda del 2026-11-10): CauceWebs vende webs para bares
y restaurantes. Su catálogo son planes con precio y lo que incluyen; no hay stock ni pedidos, y la
venta es **pedir una demo**, que abre un caso `demo` para el dueño. Todo lo que es «de CauceWebs»
está aquí, como datos e instrucciones; el código de Cauce no sabe qué es un plan:

| Fichero | Qué es |
|---|---|
| `agente-clientes.prompt.md` | Instrucciones del asistente de ventas de la web |
| `agente-dueno.prompt.md` | Instrucciones del canal del dueño (recibe los avisos de demo) |
| `herramientas.json` | Herramientas que se conceden a cada agente |
| `respaldo.json` | Texto que recibe la persona si su mensaje no se puede procesar (ADR 0005, C3) |
| `no-soportado.json` | Texto que recibe la persona si manda algo que Cauce no lee |
| `retencion-cache.json` | Caché del prompt de cada agente (ADR 0010, S9) |
| `asistente.json` | El modo asistente de tienda del chat de caucewebs.es (ADR 0014, WC8): burbuja, señales por ruta (`/` y `/pagar`), saludos, la variante de la fase y los interruptores (ver abajo): `PUT /v1/web-chat-channels/{id}/assistant` |
| `caucewebs/ficha.json` | Ficha del negocio, con las categorías de caso `demo` y `otro`: `PUT /v1/tenants/{id}/business-profile` |
| `caucewebs/catalogo.json` | Lo único que se vende ahora: los planes de web, la tienda y los vídeos para redes, con precios sin IVA y lo que incluyen: `PUT .../catalog` |
| `comprobar-precios.mjs` | Compara el catálogo y la ficha con los precios publicados en caucewebs.es (ver abajo) |
| `evals/` | Escenarios de «bien hecho» del asistente de ventas (ver `evals/README.md`). Línea base vigente: `2026-11-11T1115Z`, 100 % en 24 escenarios (`evals/linea-base.md`) |
| `prueba-oculta.md` | La prueba en la página oculta de caucewebs.es antes de publicar, su registro de fallos y cuándo se publica; la prepara `scripts/prueba-caucewebs.ps1` |

Sin agenda: CauceWebs no reserva nada. Las reglas que no se negocian, en el prompt y en los
escenarios: ningún precio, condición ni función que no esté en la ficha o el catálogo; lo que no
se vende es «ahora mismo no lo ofrecemos», sin anunciarlo como futuro; las condiciones remiten a
caucewebs.es/condiciones; un contacto incompleto se pregunta, nunca se completa; ningún descuento
fuera del programa de recomendación publicado.

Los datos salen de la web pública de CauceWebs. Si cambia un precio o una condición allí, se cambia
aquí en el mismo momento: el agente solo sabe lo que dicen estos ficheros.

## Al cambiar un precio: paso obligatorio

Antes de commitear cualquier cambio de precio (en la web o en el paquete), compara los dos:

```bash
node examples/caucewebs/comprobar-precios.mjs                                    # contra caucewebs.es
node examples/caucewebs/comprobar-precios.mjs --web https://staging.caucewebs.es  # contra otra copia de la web
```

Lee las páginas públicas (precios, webs, tiendas, vídeos, preguntas, condiciones, recomienda) y avisa de cada diferencia: un precio de plan que no coincide con la tabla de `/precios/`,
una cifra del programa de recomendación de `/recomienda/` (el 20 % del bar nuevo y su precio del
primer año en cada plan, los 49 € y el 50 % de quien recomienda) que la ficha no dice tal cual, un
importe o un porcentaje que el paquete escribe (en el catálogo o en la ficha) y que no sale en ninguna
página. Sale con 1 si hay alguna diferencia, o si no encuentra un
precio porque la página cambió de forma: entonces se revisa a mano y se ajusta el script. No se
commitea con diferencias.

Lo único que se vende ahora son las webs, la tienda y los vídeos para redes. De cualquier otro producto
el agente dice «ahora mismo no lo ofrecemos», lo anota y nunca lo anuncia como futuro.

El programa de recomendación es una condición publicada, no una rebaja: está en la ficha
(`recomienda_bar_nuevo`, `recomienda_quien_trae`) con las cifras exactas de caucewebs.es/recomienda,
y el agente lo cuenta solo cuando viene a cuento, nunca como descuento que se negocia.

`ReferenceCauceWebsPackIT` comprueba que la ficha, el catálogo y los dos agentes se cargan tal cual.

## El chat en caucewebs.es (W7)

La página habla con el widget con `cauce(...)` (ver `frontend/web-widget/README.md`):

- en la portada, `cauce('view', 'plan-plus')` cuando un plan entra en pantalla (para la señal de comparar);
- en `/pagar?plan=…`, `cauce('cart', [{ref: 'plan-plus', quantity: 1}])` con el plan elegido (la señal de
  carrito parado: elegido y sin pagar durante 3 minutos);
- en las dos, `cauce('onAddToCart', ref => location.href = '/pagar?plan=' + encodeURIComponent(ref))`, el
  botón «Contratar» de las tarjetas;
- al confirmarse el pago, `cauce('purchase', {orderId, value})`. Mientras `purchase_attribution` esté
  apagado (como sale) no se envía; encendido, solo con el consentimiento del visitante
  (`cauce('consent', true)`), y Cauce lo atribuye al chat si llega en los 7 días siguientes al último
  mensaje.

### El lanzamiento, en dos fases (ADR 0014, enmienda del 2026-11-11)

Una fase detrás de otra, nunca a la vez: con el tráfico de la web, un A/B simultáneo no da resultados
fiables. Se compara antes y después, con una sola variante por fase, que da nombre a los contadores
`cauce.web.*`.

| Fase | `proactive_opening` | Variante | Qué ve el visitante |
|---|---|---|---|
| 1, como sale `asistente.json` | `false` | `burbuja` | La burbuja; el chat se abre solo si lo pide |
| 2, con la referencia de la 1 | `true` | `apertura` | Además, el chat se abre una vez por sí solo en una señal de su ruta |

Para pasar a la fase 2 se cambian esos dos valores en `asistente.json`, se commitea y se hace el mismo `PUT`.
Los escenarios de la apertura sola (`evals/05-tienda.yaml`) se juegan ya en la fase 1.

**Cuánto dura cada fase.** La fase 1 dura al menos 4 semanas **y** al menos 30 conversaciones. Si a
las 8 semanas no ha llegado a las dos cosas, se pasa a la fase 2 igualmente y la comparación se marca
como orientativa. La fase 2 dura lo mismo, con la misma regla. Una fase empieza el día en que su
configuración se aplica a las páginas públicas. La prueba en la página oculta va antes y no cuenta.
Mientras dura una fase no se tocan los precios, los anuncios ni la portada de caucewebs.es. Si algún
cambio no puede esperar, se anota con su fecha y la comparación pasa a ser orientativa.

| Fase | Empieza | Termina | Conversaciones | ¿Orientativa? | Cambios durante la fase |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |

**Qué se compara** (ADR 0014, enmienda del 2026-11-11). Se saca de la base de datos y no de los
contadores, que se pierden cuando la instancia se reinicia. La métrica principal son las demos
pedidas por semana. Los datos de cada fase se obtienen con esta consulta, en Adminer o con `psql`
como `cauce`, poniendo las fechas de la fase y el id del chat web (`CW_WEB_CHAT_ID` de
`.prueba-caucewebs.env`):

```sql
WITH fase AS (SELECT DATE '2026-12-01' AS desde, DATE '2026-12-29' AS hasta,
                     '00000000-0000-0000-0000-000000000000'::uuid AS canal),
     conv AS (SELECT c.id FROM conversations c, fase
              WHERE c.channel_config_id = fase.canal
                AND c.started_at >= fase.desde AND c.started_at < fase.hasta)
SELECT
  (SELECT count(*) FROM conv) AS conversaciones,
  (SELECT count(*) FROM handoffs h WHERE h.category = 'demo'
     AND h.conversation_id IN (SELECT id FROM conv)) AS demos,
  (SELECT count(*) FROM web_events e, fase WHERE e.kind = 'ADD_TO_CART' AND e.channel_config_id = fase.canal
     AND e.created_at >= fase.desde AND e.created_at < fase.hasta) AS tarjetas_al_carrito,
  (SELECT count(DISTINCT m.conversation_id) FROM messages m, jsonb_array_elements(m.parts) p
     WHERE m.conversation_id IN (SELECT id FROM conv) AND m.role = 'USER'
       AND p ->> 'type' = 'page_context' AND p ->> 'greeting' IS NOT NULL) AS abiertas_por_el_chat;
```

Con esos números y las semanas de la fase salen las demos por semana y por conversación, las
conversaciones por semana, las tarjetas al carrito por conversación y, solo en la fase 2, la parte de
conversaciones que abrió el chat. El coste por conversación es `GET /v1/tenants/{cliente}/usage?from=…&to=…`
dividido entre las conversaciones. Sirve de control: la fase 2 no puede subirlo sin traer más demos.

Los demás interruptores, tal como sale el fichero:

- `proactive_requires_consent: false`. El motor lo trae encendido. caucewebs.es lo apaga solo para la
  apertura, porque el widget, sin consentimiento, no guarda en el navegador más que las marcas de
  abierto y cerrado.
- `opening_counter: false`: no se cuentan las aperturas que acaban sin mensaje.
- `purchase_attribution: false`: la compra atribuida a 7 días está apagada, y encendida exigiría el
  consentimiento.

Los refs son los del catálogo (`plan-basico`, `plan-basico-dominio`, `plan-plus`, `plan-tienda`).

