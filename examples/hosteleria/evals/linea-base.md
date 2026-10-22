# Línea base del agente de clientes del bar

**Vigente:** la ejecución [`2026-10-22T1543Z`](historial/2026-10-22T1543Z-claude-sonnet-5-5.json),
batería completa con N = 5: **97 %** global y **todos los bloqueantes al 100 %**.

**Modelo:** `anthropic/claude-sonnet-5-5` · **Juez:** ninguno

> **Es una línea base determinista.** Solo cuentan las comprobaciones de traza, estado y texto.
> Las comprobaciones de rúbrica (105 por batería) quedaron «pendiente de juez»: no entran en
> ninguna tasa y no se han aprobado. El juez de otro proveedor llega a principios de noviembre
> (ADR 0009, E6); hasta entonces, ninguna tasa de aquí dice nada de lo que solo una rúbrica
> comprueba.

Los resultados son los de las ejecuciones tal cual, fallos incluidos. Cuando cambia la línea base
vigente, la anterior se conserva más abajo con su diagnóstico y los cambios que salieron de ella.

## Línea base vigente · 2026-10-22T1543Z

| Commit | Huella del prompt | Turnos de modelo | Coste |
|---|---|---|---|
| `810cf9a`¹ | `e41d7a6698af` | 215 | 1,2327 US$ |

¹ Con cambios sin commitear: solo el resumen de la ejecución anterior en `historial/`. El prompt
era el del commit, como confirma la huella.

| Entrada | Leída de caché | Escrita en caché | Salida |
|---|---|---|---|
| 2.644.602 | 2.569.030 (97,1 %) | 74.134 | 53.070 |

- Sin caché, las mismas llamadas habrían costado 5,8199 US$: la caché ahorró un **79 %**.
- **Ese ahorro es un techo.** En el arnés, las llamadas de una conversación van seguidas y la caché
  no caduca. En un bar real, entre mensaje y mensaje de un cliente pueden pasar más de los 5 minutos
  que dura la caché, y solo seguirían en caché las herramientas y el prompt.
- Coste medio: **0,0057 US$ por turno de modelo**. Precios de `precios.yaml`, versión 2026-10-22;
  es el cálculo con los tokens que devolvió el proveedor, no la factura.

### Tasas

**Global: 97 %** (146 de 150 repeticiones con todas sus comprobaciones en verde).

| Tarea | Tasa |
|---|---|
| 00 · anclaje | 100 % |
| 01 · información | 100 % |
| 02 · carta | 100 % |
| 03 · reservas | 93 % |
| 04 · escalado | 100 % |
| 05 · resumen | 100 % |

| Regla general | Tasa |
|---|---|
| G1 · carta consultada antes de hablar de ella | 100 % |
| Carta sin inventar | 100 % |

**Bloqueantes por debajo del 100 %:** ninguno. **Avisos bajo el umbral del 90 %:** H3.8 (20 %).

| Escenario | Tarea | Nivel | Tasa | Repeticiones | Rúbricas sin juez |
|---|---|---|---|---|---|
| G1 | 00 | bloqueante | 100 % | 5 | 0 |
| G2 | 00 | aviso | 100 % | 5 | 5 |
| H1.1 | 01 | aviso | 100 % | 5 | 0 |
| H1.2 | 01 | aviso | 100 % | 5 | 5 |
| H1.3 | 01 | aviso | 100 % | 5 | 5 |
| H1.4 | 01 | aviso | 100 % | 5 | 5 |
| H1.5 | 01 | bloqueante | 100 % | 5 | 5 |
| H2.1 | 02 | bloqueante | 100 % | 5 | 5 |
| H2.2 | 02 | bloqueante | 100 % | 5 | 0 |
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
| H3.8 | 03 | aviso | **20 %** | 5 | 0 |
| H3.9 | 03 | bloqueante | 100 % | 5 | 0 |
| H3.10 | 03 | aviso | 100 % | 5 | 0 |
| H3.11 | 03 | aviso | 100 % | 5 | 0 |
| H3.12 | 03 | aviso | 100 % | 5 | 0 |
| H4.1 | 04 | bloqueante | 100 % | 5 | 10 |
| H4.2 | 04 | bloqueante | 100 % | 5 | 5 |
| H4.3 | 04 | bloqueante | 100 % | 5 | 5 |
| H4.4 | 04 | aviso | 100 % | 5 | 0 |
| H4.5 | 04 | aviso | — | — | cubierto por `OwnerIT` |
| H4.6 | 04 | aviso | 100 % | 5 | 5 |
| H4.7 | 04 | bloqueante | 100 % | 5 | 10 |
| H5.1 | 05 | aviso | 100 % | 1 | 0 |
| H5.2 | 05 | aviso | 100 % | 1 | 0 |
| H5.3 | 05 | aviso | 100 % | 1 | 0 |
| H5.4 | 05 | aviso | — | — | cubierto por `DigestIT` |
| H5.5 | 05 | aviso | — | — | cubierto por `OwnerIT` |
| H5.6 | 05 | aviso | 100 % | 1 | 0 |
| H5.7 | 05 | aviso | 100 % | 1 | 0 |

Los escenarios de la tarea 05 son deterministas (el resumen no usa modelo) y se ejecutan una vez.

### Regresión abierta: H3.8

Con la línea base anterior, H3.8 estaba al 100 %; en esta, al 20 %. A «Somos 12 para cenar el
viernes, me llamo Jorge, 633 333 333», en cuatro de cinco repeticiones el agente consultó la hora
y la ficha, dijo que los grupos de más de 8 los gestiona el dueño y **ofreció** pasarle la petición
(«Puedo pasarle tu petición. ¿Me confirmas estos datos?»), pidiendo que confirmara lo que ya había
dicho o datos opcionales (hora aproximada, si es una celebración). No faltaba ningún dato y no
escaló. No prometió nada.

**Fallo del agente, provocado por el prompt nuevo:** la frase que manda ofrecer o condicionar el
traspaso mientras `escalate_to_owner` no haya devuelto éxito se leyó como «pide permiso antes de
escalar». Corrección aplicada al prompt: con nombre, teléfono y detalles, escalar en ese mismo
turno, sin pedir confirmación ni datos opcionales; ofrecer o condicionar solo mientras falte algún
dato. Los arreglos de G1, G2 y H4.7 no se tocan. Pendiente de medir.

## Línea base anterior · 85 %

Ejecuciones `2026-10-22T1431Z` (tarea 03, commit `44217d0`) y `2026-10-22T1501Z` (tareas 00, 01,
02, 04 y 05, commit `f1e74b9`), con el prompt de huella `579475a27ef1`: **85 %** global (127 de 150),
**1,1623 US$** en 215 turnos, 96,9 % de la entrada leída de caché.

| Tarea | Antes | Ahora |
|---|---|---|
| 00 · anclaje | 20 % | 100 % |
| 01 · información | 100 % | 100 % |
| 02 · carta | 84 % | 100 % |
| 03 · reservas | 100 % | 93 % |
| 04 · escalado | 63 % | 100 % |
| 05 · resumen | 100 % | 100 % |
| **Global** | **85 %** | **97 %** |
| Regla G1 / carta sin inventar | 86 % / 86 % | 100 % / 100 % |
| Coste de la batería | 1,1623 US$ | 1,2327 US$ (+6 %) |

El coste sube porque ahora el agente vuelve a consultar la carta y la ficha en cada turno: la
entrada pasa de 2.437.667 a 2.644.602 tokens (+8 %).

### Diagnóstico de sus fallos

| Escenario | Antes | Qué pasó | Clase |
|---|---|---|---|
| G1 (turno 2) | 40 % | Respondió bien sobre las croquetas con el `search_catalog` del turno 1, sin volver a consultar | Agente |
| G2 (turno 2) | 0 % | Respondió bien el horario del domingo con el `get_business_info` del turno 1 | Agente |
| H2.2 | 20 % | «no me consta», «no te puedo decir»: correcto; la comprobación buscaba «no consta» literal | Comprobación |
| H4.4 | 0 % | El mensaje no traía fecha y el prompt la pide antes de escalar un evento | Comprobación (escenario) |
| H4.6 y H4.7 (G1, turno 1) | 80 % / 0 % | «tarta, decoración, menú…» para el cumpleaños se leyó como la tarta de queso de la carta | Comprobación |
| H4.7 («€», turno 2) | 0 % | Citó el menú del día de la ficha (14,50 €); después repitió los 30 € del cliente | Agente |

### Cambios aplicados

| Commit | Cambio |
|---|---|
| `ac6f749` | `respuesta_contiene` acepta alternativas («no consta \| no me consta»); H2.2 las usa |
| `f3e0b94` | Un plato de varias palabras se reconoce por todas («tarta de queso», no «tarta») |
| `d973f71` | H4.4 trae la fecha que su escalado necesita |
| `810cf9a` | Prompt: volver a consultar carta y ficha en cada turno; ningún importe, ni de la ficha ni del cliente, cuando se pregunta el precio de un grupo o evento; no afirmar el traspaso al dueño antes de que `escalate_to_owner` devuelva éxito |

### Después

- [`2026-10-22T1522Z`](historial/2026-10-22T1522Z-claude-sonnet-5-5.json): solo los seis
  escenarios afectados (G1, G2, H2.2, H4.4, H4.6, H4.7), N = 5: **los seis al 100 %**, 0,3519 US$.
- `2026-10-22T1543Z`: la batería completa con el prompt nuevo, la línea base vigente de arriba.
