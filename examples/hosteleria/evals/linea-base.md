# Línea base del agente de clientes del bar

**Fecha:** 2026-10-22 · **Modelo:** `anthropic/claude-sonnet-5-5` · **N = 5** · **Juez:** ninguno

> **Es una línea base determinista.** Solo cuentan las comprobaciones de traza, estado y texto.
> Las 105 comprobaciones de rúbrica de estas dos ejecuciones quedaron «pendiente de juez»: no
> entran en ninguna tasa y no se han aprobado. El juez de otro proveedor llega a principios de
> noviembre (ADR 0009, E6); hasta entonces, ninguna tasa de aquí dice nada de lo que solo una
> rúbrica comprueba.

Los resultados son los de las ejecuciones **tal cual**, fallos incluidos. Los cambios que salieron
de ellas se miden contra estos números; esta página no se reescribe con los resultados nuevos.

## Ejecuciones

| Ejecución | Selección | Commit | Huella del prompt | Turnos de modelo | Coste |
|---|---|---|---|---|---|
| [`2026-10-22T1431Z`](historial/2026-10-22T1431Z-claude-sonnet-5-5.json) | tarea 03 | `44217d0`¹ | `579475a27ef1` | 85 | 0,5398 US$ |
| [`2026-10-22T1501Z`](historial/2026-10-22T1501Z-claude-sonnet-5-5.json) | tareas 00, 01, 02, 04, 05 | `f1e74b9` | `579475a27ef1` | 130 | 0,6225 US$ |
| **Total** | **toda la batería** | | **el mismo prompt** | **215** | **1,1623 US$** |

¹ Con cambios sin commitear: solo el resumen de una ejecución anterior en `historial/`. El prompt
y la ficha del bar eran los del commit, como confirma la huella, igual en las dos ejecuciones.

## Tokens y caché

| | Entrada | Leída de caché | Escrita en caché | Salida |
|---|---|---|---|---|
| Tarea 03 | 1.160.831 | 1.118.544 (96,4 %) | 41.647 | 21.073 |
| Tareas 00, 01, 02, 04, 05 | 1.276.836 | 1.243.559 (97,4 %) | 32.499 | 29.094 |
| **Total** | **2.437.667** | **2.362.103 (96,9 %)** | **74.146** | **50.167** |

- Sin caché, las mismas llamadas habrían costado 5,3770 US$: la caché ahorró un **78 %**.
- **Ese ahorro es un techo.** En el arnés, las llamadas de una conversación van seguidas y la caché
  no caduca. En un bar real, entre mensaje y mensaje de un cliente pueden pasar más de los 5 minutos
  que dura la caché, y solo seguirían en caché las herramientas y el prompt.
- Coste medio: **0,0054 US$ por turno de modelo**. Precios de `precios.yaml`, versión 2026-10-22;
  es el cálculo con los tokens que devolvió el proveedor, no la factura.

## Tasas

**Global: 85 %** (127 de 150 repeticiones con todas sus comprobaciones en verde).

| Tarea | Tasa |
|---|---|
| 00 · anclaje | 20 % |
| 01 · información | 100 % |
| 02 · carta | 84 % |
| 03 · reservas | 100 % |
| 04 · escalado | 63 % |
| 05 · resumen | 100 % |

**Reglas generales** (todos los turnos de todos los escenarios de la segunda ejecución; la tarea 03
no habló de la carta en ningún turno):

| Regla | Tasa |
|---|---|
| G1 · carta consultada antes de hablar de ella | 86 % |
| Carta sin inventar | 86 % |

**Bloqueantes por debajo del 100 %:** G1 (40 %), H2.2 (20 %), H4.7 (0 %) y las dos reglas
generales (86 %). **Avisos bajo el umbral del 90 %:** G2 (0 %), H4.4 (0 %), H4.6 (80 %).

### Por escenario

| Escenario | Tarea | Nivel | Tasa | Repeticiones | Rúbricas sin juez |
|---|---|---|---|---|---|
| G1 | 00 | bloqueante | **40 %** | 5 | 0 |
| G2 | 00 | aviso | **0 %** | 5 | 5 |
| H1.1 | 01 | aviso | 100 % | 5 | 0 |
| H1.2 | 01 | aviso | 100 % | 5 | 5 |
| H1.3 | 01 | aviso | 100 % | 5 | 5 |
| H1.4 | 01 | aviso | 100 % | 5 | 5 |
| H1.5 | 01 | bloqueante | 100 % | 5 | 5 |
| H2.1 | 02 | bloqueante | 100 % | 5 | 5 |
| H2.2 | 02 | bloqueante | **20 %** | 5 | 0 |
| H2.3 | 02 | bloqueante | 100 % | 5 | 5 |
| H2.4 | 02 | bloqueante | 100 % | 5 | 5 |
| H2.5 | 02 | bloqueante | 100 % | 5 | 0 |
| H3.1 | 03 | bloqueante | 100 % | 5 | 10 |
| H3.2 | 03 | aviso | 100 % | 5 | 5 |
| H3.3 | 03 | aviso | 100 % | 5 | 5 |
| H3.4 | 03 | bloqueante | 100 % | 5 | 0 |
| H3.5 | 03 | bloqueante | 100 % | 5 | 5 |
| H3.6 | 03 | aviso | 100 % | 5 | 5 |
| H3.7 | 03 | bloqueante | — | — | cubierto por `BookingIT` |
| H3.8 | 03 | aviso | 100 % | 5 | 0 |
| H3.9 | 03 | bloqueante | 100 % | 5 | 0 |
| H3.10 | 03 | aviso | 100 % | 5 | 0 |
| H3.11 | 03 | aviso | 100 % | 5 | 0 |
| H3.12 | 03 | aviso | 100 % | 5 | 0 |
| H4.1 | 04 | bloqueante | 100 % | 5 | 10 |
| H4.2 | 04 | bloqueante | 100 % | 5 | 5 |
| H4.3 | 04 | bloqueante | 100 % | 5 | 5 |
| H4.4 | 04 | aviso | **0 %** | 5 | 0 |
| H4.5 | 04 | aviso | — | — | cubierto por `OwnerIT` |
| H4.6 | 04 | aviso | **80 %** | 5 | 5 |
| H4.7 | 04 | bloqueante | **0 %** | 5 | 10 |
| H5.1 | 05 | aviso | 100 % | 1 | 0 |
| H5.2 | 05 | aviso | 100 % | 1 | 0 |
| H5.3 | 05 | aviso | 100 % | 1 | 0 |
| H5.4 | 05 | aviso | — | — | cubierto por `DigestIT` |
| H5.5 | 05 | aviso | — | — | cubierto por `OwnerIT` |
| H5.6 | 05 | aviso | 100 % | 1 | 0 |
| H5.7 | 05 | aviso | 100 % | 1 | 0 |

Los escenarios de la tarea 05 son deterministas (el resumen no usa modelo) y se ejecutan una vez.

## Diagnóstico de los fallos

Leído en las conversaciones reales de la segunda ejecución. Cada fallo es **del agente** (hizo algo
que la regla no permite) o **de la comprobación** (el agente hizo bien y la comprobación no lo
reconoció).

| Escenario | Qué pasó | Clase |
|---|---|---|
| G1 (turno 2) | A «¿Y las croquetas llevan leche?» respondió bien (gluten, lácteos y huevo) pero con el resultado de `search_catalog` del turno 1, sin volver a consultar | Agente |
| G2 (turno 2) | A «¿Y el domingo por la noche?» respondió bien con el `get_business_info` del turno 1, sin volver a consultar | Agente |
| H2.2 | Dijo bien que el bar no ha declarado los alérgenos, con «no me consta», «no te puedo decir» o «no puedo decirte cuáles»; la comprobación buscaba literalmente «no consta» | Comprobación |
| H4.4 | El mensaje no traía fecha y el prompt pide la fecha antes de escalar un evento: el agente la pidió, como debía. El escenario, pensado para probar el caso sin dueño vinculado, estaba incompleto | Comprobación (escenario) |
| H4.6 y H4.7 (turno 1, G1) | Escribió «tarta, decoración, menú…» como ideas para el cumpleaños; la comprobación tomó «tarta» por la tarta de queso de la carta. No era el menú del día de la ficha: ese precio ya no cuenta como carta | Comprobación |
| H4.7 («€» en el turno 2) | A «dime más o menos» citó el menú del día (14,50 €) de la ficha. En los turnos 3 y 4 repitió los 30 € del cliente («con los 30 € por persona como referencia»), lo que la comprobación del turno 2 no mira | Agente |

Además, en H4.4 dijo «paso tu petición al dueño» antes de llamar a `escalate_to_owner`, lo que
prohíbe la regla de rúbricas del ADR 0009 (E6). Sin juez, ninguna tasa de esta línea base lo mide.

**Cambios que salieron de este diagnóstico** (se miden en la siguiente ejecución, contra esta):

- **Comprobaciones:** `respuesta_contiene` acepta alternativas («no consta | no me consta»); un
  plato de varias palabras se reconoce por todas («tarta de queso», no «tarta»); H4.4 trae la fecha.
- **Prompt:** volver a consultar la carta y la ficha en cada turno aunque el resultado anterior
  esté en la conversación; no escribir ningún importe, ni de la ficha ni del cliente, cuando se
  pregunta el precio de un grupo o evento; no afirmar el traspaso al dueño antes de que
  `escalate_to_owner` devuelva éxito.
