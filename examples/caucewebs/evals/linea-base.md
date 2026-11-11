# Línea base del asistente de ventas de CauceWebs

**Vigente:** la batería completa [`2026-11-11T1115Z`](historial/2026-11-11T1115Z-claude-sonnet-5-5.json)
sobre `25f71b6`, N = 5: **100 %** global en sus 24 escenarios, tal como se ejecutó. Incluye los
escenarios del modo asistente de tienda (`05-tienda.yaml`). Sustituye a `1446Z` (más abajo).

**Modelo:** `anthropic/claude-sonnet-5-5` · **Prompt:** huella `1314d2b273b1` · **Juez:** ninguno

> **Es una línea base determinista.** Solo cuentan las comprobaciones de traza, estado y texto. Las
> 145 comprobaciones de rúbrica quedaron «pendiente de juez»: no entran en ninguna tasa y no se han
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
CW4.4 corregida → W7 añade `05-tienda.yaml` (24 escenarios): **100 %** sobre `1fce521` (`1006Z`), pero
la referencia deja de valer con el lanzamiento en fases (`25acdc7`) → **98 %** sobre `0e1041e`
(`1037Z`): CW3.1 y CW3.3, al cerrar la demo, ofrecían «vídeos para redes» sin consultar el catálogo,
una frase sacada del propio prompt → el prompt deja de enumerar productos en la demo (`25f71b6`) →
**100 %** (`1115Z`).

## Línea base vigente · 2026-11-11T1115Z

| Commit | Huella del prompt | Turnos de modelo | Coste |
|---|---|---|---|
| `25f71b6` | `1314d2b273b1` | 155 | 1,5127 US$ |

| Entrada | Leída de caché | Escrita en caché | Salida |
|---|---|---|---|
| 2.585.908 | 2.413.864 (93,3 %) | 171.010 | 58.521 |

- Respecto a `1446Z`: 4 escenarios más (los de la tienda) y 20 turnos de modelo más. El coste sube de
  1,2578 a 1,5127 US$. Los tokens de entrada por turno de modelo pasan de 12.237 a 16.683, por lo que
  W7 añadió al prompt y a las herramientas (`show_products`) y por el contexto de página. La caché leída sube del
  90,5 % al 93,3 %.
- `1006Z` y `1037Z` no entran en el historial: `1006Z` midió una configuración del pack que ya no
  rige, y `1037Z` un prompt que se ha cambiado. Diagnóstico de `1037Z`: misma huella de prompt que
  `1006Z`, y el cambio del arnés no toca CW3 (no lleva contexto de página). En `1006Z` el agente ya
  ofrecía «web, tienda online o vídeos» (CW3.3 #1 y #2), pero escrito «vídeos», que no es el nombre de
  ningún producto del catálogo. En `1037Z` lo escribió con el nombre completo en 3 de 10 cierres.
- La estimación de tokens de entrada del presupuesto de contexto queda en 1,029 de lo facturado de
  media (mínimo 0,947, máximo 1,071) en sus 362 llamadas.

### Tasas

**Global: 100 %** (120 repeticiones en 24 escenarios).

| Tarea | Tasa |
|---|---|
| 01 · información | 100 % |
| 02 · planes | 100 % |
| 03 · demo | 100 % |
| 04 · límites | 100 % |
| 05 · tienda | 100 % |

Reglas generales (G1 y carta sin inventar), al 100 %. **Bloqueantes por debajo del 100 %:** ninguno.
**Avisos bajo el umbral del 90 %:** ninguno. 145 comprobaciones de rúbrica, pendientes de juez.

## Línea base anterior · 2026-11-10T1446Z

La primera del paquete, sobre `d1034d7`, con 20 escenarios: **100 %** re-evaluada (99 % tal como se
ejecutó). Prompt con huella `f0b20c7c4d39`.

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

#### La re-evaluación de CW4.4

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

#### Tasas

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
