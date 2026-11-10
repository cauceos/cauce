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
| `caucewebs/ficha.json` | Ficha del negocio, con las categorías de caso `demo` y `otro`: `PUT /v1/tenants/{id}/business-profile` |
| `caucewebs/catalogo.json` | Planes, precios sin IVA y lo que incluyen; lo previsto lleva `estado: previsto` y no tiene precio: `PUT .../catalog` |
| `evals/` | Escenarios de «bien hecho» del asistente de ventas (ver `evals/README.md`) |

Sin agenda: CauceWebs no reserva nada. Las reglas que no se negocian, en el prompt y en los
escenarios: ningún precio, condición ni función que no esté en la ficha o el catálogo; lo previsto
se dice como previsto; las condiciones remiten a caucewebs.es/condiciones; un contacto incompleto
se pregunta, nunca se completa; ningún descuento.

Los datos salen de la web pública de CauceWebs. Si cambia un precio o una condición allí, se cambia
aquí en el mismo momento: el agente solo sabe lo que dicen estos ficheros.

`ReferenceCauceWebsPackIT` comprueba que la ficha, el catálogo y los dos agentes se cargan tal cual.
