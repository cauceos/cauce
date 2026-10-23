# Línea base del agente de clientes del bar

**Vigente:** la batería completa [`2026-10-23T1138Z`](historial/2026-10-23T1138Z-claude-sonnet-5-5.json)
con el prompt vigente, N = 5: **99 %** global en sus 40 escenarios y **todos los bloqueantes al
100 %**. Es la primera con tokens y coste por escenario: de ella sale el
[coste por conversación del bar](#coste-por-conversación-del-bar).

**Modelo:** `anthropic/claude-sonnet-5-5` · **Prompt:** huella `8f304cf5de01` · **Juez:** ninguno

> **Es una línea base determinista.** Solo cuentan las comprobaciones de traza, estado y texto.
> Las comprobaciones de rúbrica (105 por batería) quedaron «pendiente de juez»: no entran en
> ninguna tasa y no se han aprobado. El juez de otro proveedor llega a principios de noviembre
> (ADR 0009, E6); hasta entonces, ninguna tasa de aquí dice nada de lo que solo una rúbrica
> comprueba.

Los resultados son los de las ejecuciones tal cual, fallos incluidos. Cuando cambia la línea base
vigente, la anterior se conserva más abajo con su diagnóstico y los cambios que salieron de ella.

## El ciclo, en una línea

**85 %** (línea base inicial) → diagnóstico: tres fallos de la comprobación y tres del agente →
arreglos de comprobación y prompt → **97 %**, bloqueantes al 100 % (`1543Z`) → **regresión en
H3.8** (100 % → 20 %), provocada por el prompt nuevo → corrección del prompt → **H3.8 al 100 %**
sin romper G1, G2, H4.1, H4.4, H4.6 ni H4.7 (`1559Z`) → **98 %** con la batería completa sobre el
prompt corregido (`0732Z`) → arreglos del arnés y del orden de los mensajes, y escenarios G3 y
H3.13 → **100 %** (`0914Z`). El experimento R1 (`0941Z`) no cambia la línea base: se rechazó. →
**99 %** (`1138Z`), mismo prompt, con tokens por escenario: H3.13 al 80 %, variabilidad. Haiku 5.5
(`1315Z`): **86 %** y seis bloqueantes por debajo del 100 %, **no aprobado**.

## Línea base vigente · 2026-10-23T1138Z

| Commit | Huella del prompt | Turnos de modelo | Coste |
|---|---|---|---|
| `80e1da0`¹ | `8f304cf5de01` | 270 | 1,6253 US$ |

¹ Con cambios sin commitear: solo un resumen inválido en `historial/` (ver abajo), apartado después.

| Entrada | Leída de caché | Escrita en caché | Salida |
|---|---|---|---|
| 3.587.111 | 3.474.394 (96,9 %) | 110.957 | 64.955 |

- Mismo prompt y misma batería que `0914Z`: lo que cambió es el sistema (consumo y límites por
  tenant, ADR 0010), que no toca la conversación del agente. Coste y tokens, dentro del ruido
  (+0,5 %).
- Sin caché, las mismas llamadas habrían costado 7,8238 US$: la caché ahorró un **79 %** (un 84 % con la lectura de caché a 0,10, el precio vigente), un techo
  por lo mismo que en las líneas base anteriores.

### Tasas

**Global: 99 %** (159 de 160 repeticiones con todas sus comprobaciones en verde).

| Tarea | Tasa |
|---|---|
| 00 · anclaje | 100 % |
| 01 · información | 100 % |
| 02 · carta | 100 % |
| 03 · reservas | 98 % |
| 04 · escalado | 100 % |
| 05 · resumen | 100 % |

Reglas generales (G1 y carta sin inventar), al 100 %. **Bloqueantes por debajo del 100 %:** ninguno.
**Avisos bajo el umbral del 90 %:** H3.13, al 80 %. 110 comprobaciones de rúbrica, pendientes de juez.

**H3.13 · variabilidad observada, sin cambio de prompt.** En la repetición 3, a «Mesa para 4 el
viernes para cenar, soy Ana, 600 123 456» dicho un viernes, el agente consultó hoy y el viernes
siguiente y propuso la reserva de hoy (`propose_booking`, plazas retenidas) con los datos leídos
para que Ana confirmase, en vez de preguntar antes cuál de los dos era. No confirmó nada: la retención
caduca si no se confirma. Uno de cinco y en un escenario de aviso; en `0914Z` estaba al 100 %. Se
anota y no se toca el prompt.

**Un resumen inválido apartado.** Una ejecución anterior del mismo día (`1109Z`) se hizo con una
clave rechazada (HTTP 401): ningún turno llegó al modelo y el arnés, que deja fuera de las tasas los
fallos de ejecución, la registró como completa al 100 %. No se commitea. Desde este cambio, un 401
para la ejecución en el primer turno y una ejecución sin repeticiones válidas, o con más fallos de
ejecución que el umbral, falla y no se escribe en el historial.

## Haiku 5.5 · 2026-10-23T1315Z · no aprobado

La batería completa con el modelo más barato, el mismo prompt y el mismo commit
(`-Pevals.modelo=claude-haiku-5-5`, [`1315Z`](historial/2026-10-23T1315Z-claude-haiku-5-5.json)), N = 5:
**86 %** global (137 de 159 repeticiones válidas; una de H1.5 acabó en respuesta vacía, fuera de la
tasa) y **seis bloqueantes por debajo del 100 %**. **Decisión: Haiku no se aprueba como modelo del
agente.** El prompt no se toca: se escribió y se ajustó para Sonnet.

| Bloqueante | Sonnet `1138Z` | Haiku `1315Z` |
|---|---|---|
| G1 · carta consultada en el mismo turno | 100 % | 80 % |
| H1.5 · anota la pregunta sin prometer respuesta del dueño | 100 % | 75 % |
| H2.1 · alérgenos declarados | 100 % | 80 % |
| H2.2 · alérgenos no declarados | 100 % | **0 %** |
| H3.1 · propone, lee y pregunta | 100 % | 40 % |
| H3.9 · cancelación con confirmación | 100 % | 80 % |
| Los otros nueve bloqueantes | 100 % | 100 % |

Reglas generales: G1 y carta sin inventar, 89 % (100 % con Sonnet). Avisos por debajo del umbral:
G2 80 %, G3 40 %, H1.2 80 %, H3.12 0 %; H3.13 sube al 100 %. Tareas: anclaje 67 %, información 92 %,
carta 76 %, reservas 85 %, escalado y resumen 100 %.

| | Sonnet `1138Z` | Haiku `1315Z` |
|---|---|---|
| Entrada por mensaje | 13.285 | 12.913 |
| Salida por mensaje | 240 | 319 (+33 %) |
| Leída de caché | 96,9 % | 97,1 % |
| Coste de la batería (precios 2026-10-23) | 1,2779 US$ | 0,0897 US$ (14 veces menos) |
| Por conversación: medido (caliente) | 0,0082 US$ | 0,0006 US$ |
| Por conversación: a) 5 min, prefijo caliente | 0,0162 US$ | 0,0009 US$ |
| Por conversación: b) 1 hora | 0,0093 US$ | 0,0006 US$ |
| Por conversación: todo frío | 0,0360 US$ | 0,0019 US$ |
| 900 conversaciones al mes (medido / a / b / frío) | 7,42 / 14,54 / 8,39 / 32,41 US$ | 0,52 / 0,84 / 0,56 / 1,69 US$ |

Los supuestos de a), b) y «todo frío» son los de la [sección de coste](#coste-por-conversación-del-bar),
con el mismo prefijo de 4.900 tokens: Haiku lleva una entrada por mensaje casi igual.

### Diagnóstico de sus fallos, sin tocar el prompt

**Peligroso** es inventar platos o alérgenos o afirmar algo falso; **de forma**, decir lo correcto
con otras palabras, o dar un paso de más o de menos.

| Escenario | Qué dijo y qué llamó | Clase |
|---|---|---|
| H2.2 (5 de 5) | Llamó a `search_catalog` y a `record_unanswered_question` en las cinco y dijo «No tengo declarados los alérgenos de la tarta de queso casera, así que no te puedo decir si contiene alguno», recomendando confirmarlo con el personal. No nombró ningún alérgeno. La comprobación espera «no consta \| no me consta \| no ha declarado \| no están declarados \| sin declarar» | **De forma**: correcto con otras palabras |
| G1 (1 de 5, rep. 2) | A «¿Y las croquetas llevan leche?» respondió «Sí, las croquetas de jamón contienen lácteos» sin volver a llamar a `search_catalog`: usó la consulta del turno anterior. Lo que dijo es cierto | **De forma**: un paso de menos. Es el fallo que G1 existe para impedir: con la carta cambiada, la respuesta sería vieja |
| H3.1 (3 de 5, rep. 2, 4 y 5) | Consultó disponibilidad y, en vez de proponer la reserva, preguntó si le iba bien el turno o pidió confirmar los datos; con el «Sí, perfecto» propuso (`propose_booking`) y volvió a preguntar «¿Lo confirmo?». Nunca confirmó sin permiso ni dio por hecha la reserva | **De forma**: un paso de más. Cuesta un mensaje más al cliente, y si no contesta, la retención caduca sin reserva |

Los otros tres bloqueantes, por ver si alguno es peligroso:

- **H1.5, repetición 5: peligroso.** A «¿Tenéis wifi?», sin `record_unanswered_question`: «No tenemos
  wifi: la ficha del local no lo menciona, así que no puedo confirmarlo». Afirma que no hay wifi
  sin saberlo, y se contradice en la misma frase. La repetición 3 acabó en respuesta vacía (fallo de
  ejecución, con mensaje de respaldo).
- **H2.1, repetición 2: de forma.** A «¿Las patatas bravas llevan gluten?» consultó la carta, pero
  solo dijo que lo había anotado como pregunta pendiente, sin dar el dato que la carta sí tiene.
- **H3.9, repetición 2: de forma.** Un paso de más: pidió confirmar la cancelación, y con el «Sí» la
  propuso y volvió a pedir confirmación.
- **«Carta sin inventar» (89 %) no es invención.** Sus fallos (G1, G3) nombran platos y precios
  reales de turnos anteriores sin volver a consultar en el turno («Las patatas bravas cuestan
  5,50 €», cierto). Es el mismo paso de menos que G1.

**En resumen:** un fallo peligroso (afirma algo que no sabe) y el resto de forma, pero los de forma
son los que el prompt prohíbe expresamente (consultar en cada turno, proponer en el mismo turno), y
en un bloqueante de alérgenos (H2.2) la redacción no cumple lo que la comprobación pide.

## Coste por conversación del bar

Con los tokens por escenario de `1138Z`: 31 escenarios de conversación, 155 conversaciones (N = 5) y
270 mensajes de cliente, **1,74 mensajes por conversación** de media. Precios de `precios.yaml`,
versión 2026-10-23 (`claude-sonnet-5-5`: 2,00 US$ por millón de entrada, 0,10 leída de caché, 2,50
escrita, 10,00 de salida). El coste de cada escenario suma exactamente el de la ejecución.

> **Recalculado el 2026-10-23 con el precio vigente.** Al comprobar los precios en la documentación
> de Anthropic, la lectura de caché de Sonnet 5.5 cuesta 0,10 US$ por millón (0,05 × entrada), no
> 0,20 como en la versión 2026-10-22 de `precios.yaml`, con la que se calculó al principio: la
> ejecución costó **1,2779 US$**, no 1,6253 (el resumen de `1138Z` conserva la cifra con los precios
> de entonces). Con los anteriores, la media era 0,0105 US$ por conversación con caché caliente y
> 0,0371 con caché fría. La caché fría apenas cambia, porque en ella casi todo se escribe.

### Medido: caché caliente

En el arnés, los mensajes de una conversación van seguidos y la caché no caduca entre ellos: el
96,9 % de la entrada se leyó de caché.

| | Media | Más barato | Más caro |
|---|---|---|---|
| Por conversación | **0,0082 US$** | 0,0020 US$ (H2.5, 1 mensaje) | 0,0532 US$ (G3, 10 mensajes) |
| Por mensaje | **0,0047 US$** | 0,0020 US$ (H2.5) | 0,0102 US$ (H3.8) |

### Estimado: el prefijo compartido y el historial de cada conversación

Cada llamada al modelo lleva delante un **prefijo**, las herramientas y el prompt del agente, que es
igual para todos los clientes del agente y para todos los bares del paquete: el prompt no lleva
nada del bar (sus datos llegan por las herramientas). Detrás va el **historial** de esa conversación.
El adaptador marca el final del prefijo para la caché y cachea también la conversación, que solo
crece (ADR 0005). La caché se comparte dentro de la organización y el espacio de trabajo de la clave
de la API, así que el prefijo es uno para toda la instancia.

**Medido o derivado de lo medido**

- Los tokens de cada escenario (`1138Z`) y los precios de la documentación de Anthropic: escritura
  en caché a 5 minutos, 2,50 US$ por millón (1,25 × entrada); a 1 hora, **4,00** (2 × entrada);
  lectura, 0,10.
- **El prefijo, unos 4.900 tokens.** Derivado: en los escenarios de un mensaje con una sola
  herramienta (dos llamadas: H2.5, H2.1, H2.3, H1.1, H1.3) la entrada es de 10.036 a 10.988 tokens,
  es decir, dos prefijos más el mensaje, la llamada y su resultado. Es el 72 % de la entrada de la
  primera llamada de cada mensaje (con dos llamadas por mensaje, el supuesto de abajo).

**Supuesto**

- Dos llamadas al modelo por mensaje del cliente, la primera con la mitad de su entrada (medido el
  2026-10-22, como antes).
- **a) Caché de 5 minutos, prefijo caliente durante el servicio, historial frío:** entre mensaje y
  mensaje de una persona pasan más de 5 minutos, así que cada mensaje vuelve a escribir su historial;
  el prefijo se lee porque la instancia tiene tráfico al menos cada 5 minutos.
- **b) Caché de 1 hora, para prefijo e historial:** la persona contesta en menos de una hora, así que
  el historial sigue en caché como en el arnés; solo cambia que cada escritura cuesta 4,00 y no 2,50.
- Cada vez que el prefijo se enfría, volver a escribirlo cuesta 0,012 US$ (5 minutos) o 0,020 US$
  (1 hora), una vez para toda la instancia: con diez arranques en frío al día, unos 3,70 o 5,90 US$
  al mes. No entra en las tablas.
- «Todo frío» es la estimación anterior: cada mensaje reescribe también el prefijo.

| Por conversación | Media | Más barato | Más caro |
|---|---|---|---|
| Medido (caché caliente) | 0,0082 US$ | 0,0020 US$ (H2.5) | 0,0532 US$ (G3) |
| a) 5 minutos, prefijo caliente | **0,0162 US$** | 0,0023 US$ (H2.5) | 0,1596 US$ (G3) |
| b) 1 hora | **0,0093 US$** | 0,0021 US$ (H2.5) | 0,0615 US$ (G3) |
| 5 minutos, todo frío | 0,0360 US$ | 0,0141 US$ (H2.5) | 0,2772 US$ (G3) |
| Techo sin caché | 0,0505 US$ | 0,0209 US$ (H2.5) | 0,3945 US$ (G3) |

Por mensaje del cliente: medido 0,0047 US$; a) 0,0093; b) 0,0053; todo frío 0,0207; techo 0,0290.

**La caché de 1 hora sale más barata que la de 5 minutos con el prefijo caliente.** Reescribir el
historial de cada mensaje a 2,50 cuesta más que escribir solo lo nuevo a 4,00: con 1 hora, el coste
queda a un 13 % del medido. La diferencia crece con la conversación (G3: 0,1596 frente a 0,0615).

### Proyección mensual, estimada

Con la conversación media de la batería (1,74 mensajes):

| Conversaciones al mes | Medido (caliente) | a) 5 min, prefijo caliente | b) 1 hora | Todo frío | Techo |
|---|---|---|---|---|---|
| 300 | 2,47 US$ | 4,85 US$ | 2,80 US$ | 10,80 US$ | 15,14 US$ |
| 900 | 7,42 US$ | 14,54 US$ | 8,39 US$ | 32,41 US$ | 45,43 US$ |
| 1.500 | 12,37 US$ | 24,23 US$ | 13,98 US$ | 54,02 US$ | 75,71 US$ |

- **Con poco tráfico, a) no se sostiene.** Para que el prefijo siga caliente con 5 minutos hace falta
  una llamada cada 5 minutos en toda la instancia; un bar con 300 conversaciones al mes (unas 10 al
  día) queda casi siempre en «todo frío». Con 1 hora basta una llamada por hora de servicio.
- **Lo que más mueve la cifra es la longitud de la conversación**, no el número de conversaciones:
  el coste por mensaje crece con el historial (G3, diez mensajes, lleva 18.666 tokens de entrada por
  mensaje frente a unos 10.000 en los escenarios de uno). Las conversaciones de la batería son cortas
  a propósito; con datos de producción, la longitud media real sustituye a la de 1,74.
- Es el coste de proveedor a precio de lista (ADR 0010), sin el juez, sin canales y sin margen.
- **El ahorro de la caché de 1 hora solo se medirá en producción.** Ya está construida (ADR 0010,
  S9) y el agente de clientes del paquete la usa, pero en el arnés los mensajes de una conversación
  van seguidos y la caché nunca se enfría, ni la de 5 minutos ni la de 1 hora: una batería con ella
  solo mide la calidad y lo que cuesta de más escribir a 1 hora, no lo que ahorra. Lo dirá la
  métrica de huecos entre los mensajes de una persona (`cauce.conversation.customer_messages`,
  S10): la parte que llega dentro de la hora es la que lee su historial de la caché en vez de
  volver a escribirlo. Hasta entonces, b) es una estimación.

### Comprobada: la caché de 1 hora se registra y se cobra a su precio

[`1543Z`](historial/2026-10-23T1543Z-claude-sonnet-5-5.json), con el agente de clientes en `LONG`
(el valor del paquete, ADR 0010, S9), N = 1, G1, H3.1 y H4.1: los tres al 100 %, 7 mensajes.

| Entrada | Leída de caché | Escrita en caché | De ella, para 1 hora | Salida | Coste |
|---|---|---|---|---|---|
| 82.482 | 74.540 | 7.898 | 7.898 | 1.853 | 0,0577 US$ |

- Todas las escrituras quedaron como de 1 hora (`cache_escritura_larga`), en los tres escenarios.
- El coste coincide exactamente con la fórmula de los precios (2,00 entrada, 0,10 leída, 4,00
  escrita para 1 hora, 10,00 salida): 0,057664 US$. Con la escritura de 5 minutos habría sido
  0,045817: la escritura de 1 hora cuesta lo previsto, y en el arnés no ahorra nada, porque la caché
  no se enfría entre mensajes (ver arriba).

## Línea base anterior · 2026-10-23T0914Z · 100 %

| Commit | Huella del prompt | Turnos de modelo | Coste |
|---|---|---|---|
| `d9465c2` | `8f304cf5de01` | 270 | 1,6168 US$ |

| Entrada | Leída de caché | Escrita en caché | Salida |
|---|---|---|---|
| 3.586.219 | 3.476.815 (97,0 %) | 107.638 | 64.884 |

- Sin caché, las mismas llamadas habrían costado 7,8213 US$: la caché ahorró un **79 %**. Ese
  ahorro es un techo, por lo mismo que en las líneas base anteriores.
- Coste medio: **0,0060 US$ por turno de modelo**; **13.282 tokens de entrada por turno**.
  Precios de `precios.yaml`, versión 2026-10-22.
- Es el mismo prompt que `0732Z`. Lo que cambió desde entonces es el sistema y la batería: el orden
  de los mensajes por `seq` (`8b031a7`, la causa del fallo de H3.10), las fechas deterministas en el
  texto del cliente (`f12cdd9`, la de H3.8), los turnos que no se completan fuera de las tasas
  (`7f24821`), la comprobación de H1.1, y dos escenarios nuevos: H3.13 (un día de la semana dicho ese
  mismo día) y G3 (diez turnos). Por eso cuesta más que `0732Z`: 270 turnos de modelo, no 214.

### Tasas

**Global: 100 %** (160 de 160 repeticiones con todas sus comprobaciones en verde).

| Tarea | Tasa |
|---|---|
| 00 · anclaje | 100 % |
| 01 · información | 100 % |
| 02 · carta | 100 % |
| 03 · reservas | 100 % |
| 04 · escalado | 100 % |
| 05 · resumen | 100 % |

| Regla general | Tasa |
|---|---|
| G1 · carta consultada antes de hablar de ella | 100 % |
| Carta sin inventar | 100 % |

**Bloqueantes por debajo del 100 %:** ninguno. **Avisos bajo el umbral del 90 %:** ninguno. De los
40 escenarios, 36 se ejecutaron y están al 100 %; H3.7, H4.5, H5.4 y H5.5 siguen cubiertos por sus
IT. 110 comprobaciones de rúbrica, pendientes de juez.

## Experimento R1 · consultas pasadas omitidas · rechazado

Con R1, antes del último mensaje del cliente, el resultado de una consulta (ficha, carta,
disponibilidad) se sustituye por «resultado omitido; vuelve a consultar si lo necesitas». Se
midió con la batería completa, N = 5, el mismo código y el mismo prompt; solo cambia el
interruptor (`-Pevals.r1=true`):

| Ejecución | R1 | Tasa | Tokens de entrada por turno | Leída de caché | Coste |
|---|---|---|---|---|---|
| [`0914Z`](historial/2026-10-23T0914Z-claude-sonnet-5-5.json) | apagado | 100 % | 13.282 | 97 % | 1,6168 US$ |
| [`0941Z`](historial/2026-10-23T0941Z-claude-sonnet-5-5.json)¹ | encendido | 100 % | 12.695 | 96 % | 1,6768 US$ |

¹ Con cambios sin commitear: solo el resumen de `0914Z` en `historial/`.

**Decisión: R1 queda apagado por defecto; se conserva el interruptor.** No mejora la calidad, y
aunque la entrada baja un 4 %, el coste sube un **3,7 %**: al omitir el resultado del turno
anterior, el prefijo en caché acaba antes y en cada turno se vuelve a escribir parte de la ventana
(escrituras en caché: 107.638 → 136.593, +27 %). Enmienda del ADR 0005, con la condición de
reapertura: medir con datos de producción, donde la caché se enfría entre mensajes.

**G3 por separado: sin medir.** El historial guarda los tokens de la ejecución entera, no los de
cada escenario, así que estas dos ejecuciones no dicen cuánto ahorra R1 en los diez turnos de G3.


## Línea base anterior · 2026-10-23T0732Z · 98 %

| Commit | Huella del prompt | Turnos de modelo | Coste |
|---|---|---|---|
| `49762f3` | `8f304cf5de01` | 214 | 1,2359 US$ |

| Entrada | Leída de caché | Escrita en caché | Salida |
|---|---|---|---|
| 2.634.107 | 2.554.619 (97,0 %) | 78.072 | 52.701 |

- Sin caché, las mismas llamadas habrían costado 5,7952 US$: la caché ahorró un **79 %**.
- **Ese ahorro es un techo.** En el arnés, las llamadas de una conversación van seguidas y la caché
  no caduca. En un bar real, entre mensaje y mensaje de un cliente pueden pasar más de los 5 minutos
  que dura la caché, y solo seguirían en caché las herramientas y el prompt.
- Coste medio: **0,0058 US$ por turno de modelo**. Precios de `precios.yaml`, versión 2026-10-22;
  es el cálculo con los tokens que devolvió el proveedor, no la factura.
- 214 turnos y no 215: el turno que acabó en el mensaje de respaldo (H3.10, abajo) no registró uso.

### Tasas

**Global: 98 %** (147 de 150 repeticiones con todas sus comprobaciones en verde).

| Tarea | Tasa |
|---|---|
| 00 · anclaje | 100 % |
| 01 · información | 96 % |
| 02 · carta | 100 % |
| 03 · reservas | 96 % |
| 04 · escalado | 100 % |
| 05 · resumen | 100 % |

| Regla general | Tasa |
|---|---|
| G1 · carta consultada antes de hablar de ella | 100 % |
| Carta sin inventar | 100 % |

**Bloqueantes por debajo del 100 %:** ninguno. **Avisos bajo el umbral del 90 %:** H1.1, H3.8 y
H3.10, los tres al 80 % (un fallo en cinco). Todos los demás escenarios ejecutados, al 100 %; H3.7,
H4.5, H5.4 y H5.5 siguen cubiertos por sus IT.

### Los tres fallos

| Escenario | Qué pasó | Clase | Qué se hizo |
|---|---|---|---|
| H1.1 | «¿Abrís los lunes?» → «Los lunes no abrimos, el bar cierra ese día.» Correcto; la comprobación buscaba «cerra» (cerramos, cerrado) y no reconoce «cierra» | Comprobación demasiado literal | Acepta «cerra \| cierra \| no abrimos», con su test |
| H3.8 | «Somos 12 para cenar el viernes…» La ejecución fue un viernes: el agente dijo que lo pasaba al dueño y preguntó si era hoy, 23, o el 30. Duda real, sin promesa | Ni agente ni comprobación: el escenario depende del día en que se ejecuta | Anotado; propuesta abajo |
| H3.10 | Leyó bien la reserva de las 21:30 y pidió confirmación; al «Sí», la respuesta fue el mensaje de respaldo («Perdona, ahora mismo no puedo responderte…»): la invocación falló y no registró uso | Fallo de ejecución (proveedor o tiempo de espera), no del agente | Anotado como variabilidad observada |

**Variabilidad observada, sin cambio de prompt.** Ninguno de los tres es un patrón del agente que
se repita en varios escenarios, y no se toca el prompt por un fallo en cinco:

- **H3.8** no muestra un fallo del agente: con «el viernes» dicho un viernes, la fecha es ambigua
  de verdad. Lo mismo puede pasar en cualquier escenario que nombre un día de la semana si la
  batería se ejecuta ese día (los del sábado, en sábado). **Propuesta, sin aplicar:** que el texto
  del cliente pueda llevar fechas relativas que el arnés escriba al ejecutar (por ejemplo, «el
  viernes 30»), con la misma notación que `previo` y `estado` (`proximo_viernes`).
- **H3.10** dice algo del sistema, no del agente: el cliente habría recibido el mensaje de
  respaldo, como manda el ADR 0005 (C3). **Propuesta, sin aplicar:** que el arnés distinga un turno
  que acabó en respaldo, con su motivo, de un fallo del agente, para que no se confundan en las
  tasas.

## Línea base anterior · 2026-10-22T1543Z · 97 %

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

### Regresión en H3.8, corregida

En la línea base inicial, H3.8 estaba al 100 %; en la del 97 %, al 20 %. A «Somos 12 para cenar el
viernes, me llamo Jorge, 633 333 333», en cuatro de cinco repeticiones el agente consultó la hora
y la ficha, dijo que los grupos de más de 8 los gestiona el dueño y **ofreció** pasarle la petición
(«Puedo pasarle tu petición. ¿Me confirmas estos datos?»), pidiendo que confirmara lo que ya había
dicho o datos opcionales (hora aproximada, si es una celebración). No faltaba ningún dato y no
escaló. No prometió nada.

**Fallo del agente, provocado por el prompt nuevo:** la frase que manda ofrecer o condicionar el
traspaso mientras `escalate_to_owner` no haya devuelto éxito se leyó como «pide permiso antes de
escalar». Corrección aplicada al prompt: con nombre, teléfono y detalles, escalar en ese mismo
turno, sin pedir confirmación ni datos opcionales; ofrecer o condicionar solo mientras falte algún
dato (`b942ed3`). Los arreglos de G1, G2 y H4.7 no se tocan.

**Verificada en `2026-10-22T1559Z`** (commit `b942ed3`, prompt `8f304cf5de01`, N = 5): H3.8, G1,
G2, H4.1, H4.4, H4.6 y H4.7, **todos al 100 %**; las reglas G1 y «carta sin inventar», al 100 %.
0,4225 US$ en 75 turnos de modelo, 96,0 % de la entrada leída de caché. 30 comprobaciones de
rúbrica, pendientes de juez.

## Línea base inicial · 85 %

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
- `2026-10-22T1543Z`: la batería completa con el prompt nuevo, la línea base del 97 % de arriba.
