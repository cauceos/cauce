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
| `asistente.json` | El modo asistente de tienda del chat de caucewebs.es (ADR 0014, WC8): burbuja, señales por ruta (`/` y `/pagar`), saludos y variantes: `PUT /v1/web-chat-channels/{id}/assistant` |
| `caucewebs/ficha.json` | Ficha del negocio, con las categorías de caso `demo` y `otro`: `PUT /v1/tenants/{id}/business-profile` |
| `caucewebs/catalogo.json` | Lo único que se vende ahora: los planes de web, la tienda y los vídeos para redes, con precios sin IVA y lo que incluyen: `PUT .../catalog` |
| `comprobar-precios.mjs` | Compara el catálogo y la ficha con los precios publicados en caucewebs.es (ver abajo) |
| `evals/` | Escenarios de «bien hecho» del asistente de ventas (ver `evals/README.md`) |

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
- al confirmarse el pago, `cauce('purchase', {orderId, value})`, que Cauce atribuye al chat si llega en los
  7 días siguientes al último mensaje.

Los refs son los del catálogo (`plan-basico`, `plan-basico-dominio`, `plan-plus`, `plan-tienda`).

