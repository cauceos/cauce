# Línea base del asistente de ventas de CauceWebs

**Vigente:** la batería completa [`2026-11-10T1446Z`](historial/2026-11-10T1446Z-claude-sonnet-5-5.json)
sobre `d1034d7`, N = 5: **100 %** global en sus 20 escenarios, **re-evaluada** con la comprobación
de CW4.4 corregida después (ver abajo); tal como se ejecutó, 99 %. Es la primera línea base del
paquete.

**Modelo:** `anthropic/claude-sonnet-5-5` · **Prompt:** huella `f0b20c7c4d39` · **Juez:** ninguno

> **Es una línea base determinista.** Solo cuentan las comprobaciones de traza, estado y texto. Las
> 125 comprobaciones de rúbrica quedaron «pendiente de juez»: no entran en ninguna tasa y no se han
> aprobado.

Los resultados son los de las ejecuciones tal cual, fallos incluidos. Cuando cambia la línea base
vigente, la anterior se conserva más abajo con su diagnóstico.

## El ciclo, en una línea

**48 %** (`1300Z`, primera ejecución) → diagnóstico: G1 y «carta sin inventar» pensadas para la
carta del bar (precios comparados como texto, «49» frente a «49.00»; palabras de un plan que
cruzaban una coma; planes nombrados por la ficha o por un caso) y el agente repitiendo la cifra o la
oferta falsa del cliente para rechazarla → reglas generales para cualquier catálogo (`a074531`) y
regla de no repetir importes ni promociones del cliente (`348f838`) → **96 %** (`1406Z`): CW4.4 citaba
precios sin consultar en el turno y CW4.3 negaba «meses gratis» nombrándolos → todo importe se
consulta en el turno y la forma correcta de no nombrar la promoción (`d1034d7`) → subconjunto `1425Z`
(CW1.1–CW1.4, CW4.3, CW4.4) → **99 %** (`1446Z`), y **100 %** re-evaluada con la comprobación de
CW4.4 corregida.

## Línea base vigente · 2026-11-10T1446Z

| Commit | Huella del prompt | Turnos de modelo | Coste |
|---|---|---|---|
| `d1034d7` | `f0b20c7c4d39` | 135 | 1,2578 US$ |

| Entrada | Leída de caché | Escrita en caché | Salida |
|---|---|---|---|
| 1.652.015 | 1.495.124 (90,5 %) | 156.091 | 48.231 |

- Sin línea base anterior con la que compararse. `1300Z`, `1406Z` y el subconjunto `1425Z` no entran
  en el historial: midieron un prompt y unas comprobaciones que ya no rigen.
- 12.237 tokens de entrada por turno de modelo, frente a los 15.209 del bar (`1223Z`): el prompt y las
  herramientas son más cortos y no hay agenda.
- La estimación de tokens de entrada del presupuesto de contexto queda en 1,035 de lo facturado de
  media (mínimo 0,946, máximo 1,080) en sus 265 llamadas: la calibración a 2,5 caracteres por token
  también se sostiene con este paquete.

### La re-evaluación de CW4.4

El único fallo de la ejecución fue CW4.4, turno 1, repetición 1: «respuesta contiene "20 %"». La
respuesta daba el descuento como el precio rebajado que publica la web: «el primer año del Plan
Básico te sale a 39,20 € + IVA en vez de 49 € + IVA», con el enlace a caucewebs.es/recomienda. Es un
fallo de la comprobación, no del agente: el descuento está dicho, con un importe publicado.

La comprobación acepta ahora el porcentaje («20 %», «20%», «veinte por ciento») o uno de los precios
rebajados publicados (39,20 €, 47,20 €, 63,20 €), y sigue fallando si la respuesta omite el
descuento (test en `CheckerTest`). Re-evaluada sobre las cinco respuestas del turno 1 de `1446Z`,
con la misma regla del arnés (sin distinguir mayúsculas ni espacios, basta una alternativa), pasan
las cinco; el resto de comprobaciones de la ejecución ya estaba en verde. El resumen del historial
se conserva tal como se escribió (99 %); este documento es el que dice la tasa re-evaluada.

### Tasas

**Global: 100 %** re-evaluada (99 % tal como se ejecutó; 100 repeticiones en 20 escenarios).

| Tarea | Tasa |
|---|---|
| 01 · información | 100 % |
| 02 · planes | 100 % |
| 03 · demo | 100 % |
| 04 · límites | 100 % re-evaluada (95 % tal como se ejecutó) |

Reglas generales (G1 y carta sin inventar), al 100 %. **Bloqueantes por debajo del 100 %:** ninguno,
re-evaluada. **Avisos bajo el umbral del 90 %:** ninguno. 125 comprobaciones de rúbrica, pendientes
de juez.
