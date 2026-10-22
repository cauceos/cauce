# Escenarios del agente de clientes del bar

Criterios de «bien hecho» de las cinco tareas, escritos como datos. El arnés de `cauce-evals`
(ADR 0009) los ejecuta contra un modelo real y mide una **tasa de acierto** por comprobación, por
escenario y por tarea. Las garantías que impone el código tienen además su test de integración
(`cubierto_por`).

```bash
cd backend
./gradlew :cauce-evals:eval                                  # batería completa, N = 5
./gradlew :cauce-evals:eval -Pevals.tarea=03 -Pevals.n=3     # una tarea (el prefijo del fichero)
./gradlew :cauce-evals:eval -Pevals.tarea=01,02 -Pevals.n=5  # varias tareas en una ejecución
./gradlew :cauce-evals:eval -Pevals.escenarios=H3.10,H3.11   # escenarios sueltos
./gradlew :cauce-evals:eval -Pevals.confirmar=true           # acepta una estimación sobre el límite
./gradlew :cauce-evals:eval -Pevals.juez.proveedor=openai -Pevals.juez.modelo=<modelo>   # el juez
./gradlew :cauce-evals:eval -Pevals.simulacro=true -Pevals.n=1   # sin modelo ni coste: prueba la fontanería
```

Necesita la clave del modelo del agente (`ANTHROPIC_API_KEY`). El juez (E6) es opcional: sin él, o
sin calibrar, la ejecución es solo de las comprobaciones deterministas y cada rúbrica se informa
«pendiente de juez», nunca como aprobada ni en ninguna tasa. Antes de empezar
estima el coste y, si supera `-Pevals.limite` (10 US$ por defecto), se para hasta que se confirme.
El informe queda en `backend/cauce-evals/build/evals/` (con las trazas completas, fuera de git), y el
resumen de cada ejecución en `historial/`, para commitearlo. No forma parte de `./gradlew build` ni
de CI. El simulacro sustituye el modelo por uno de guion: sus tasas no dicen nada del agente y no
escribe en el historial.

## Formato (v2)

El formato es cerrado: una clave que no está aquí es un error al cargar, nunca un escenario que
pasa.

```yaml
- id: H3.11                       # tarea.número, estable
  titulo: ...
  nivel: bloqueante               # bloqueante (100 % o falla la ejecución) | aviso (umbral, avisa)
  datos: bar-ejemplo              # fixture: ../bar-ejemplo/{ficha,carta,agenda}.json
  canal: api                      # api (por defecto) | telegram: para entregado_*
  origen: ...                     # opcional: el fallo real que dio lugar al escenario
  previo:                         # estado antes del primer turno (opcional)
    - reserva: {fecha: proximo_sabado, turno: cena, personas: 4, estado: CONFIRMED,
                contacto: Ana, mismo_cliente: true, como: r}   # como: r -> $r.codigo en las comprobaciones
    - caso: {categoria: evento, contacto: Marta, estado: OPEN, resumen: "..."}
    - pregunta: "¿Tenéis wifi?"
    - dueno_vinculado: false      # por defecto el dueño está vinculado
  turnos:
    - cliente: "texto que escribe el cliente"
      espera:
        herramientas: [list_my_bookings, propose_booking_change]   # en este turno, en cualquier orden
        orden: [check_availability, propose_booking]               # en este orden (subsecuencia)
        herramientas_no: [confirm_booking]                         # no llamadas en este turno
        argumentos: {propose_booking_change: {code: $r.codigo}}    # subconjunto de la entrada
        respuesta_contiene: ["21:30"]                              # sin distinguir mayúsculas
        respuesta_contiene: ["no consta | no me consta"]           # basta una de las alternativas
        respuesta_no_contiene: ["código nuevo"]
        entregado_no_contiene: ["**"]                              # el texto que entrega el canal
        aviso_al_dueno_contiene: ["Mensaje del cliente: «"]        # el último aviso al dueño
        estado:                                                    # registros tras el turno
          reservas: {CONFIRMED: 1, HELD: 0}                        # cuántas en cada estado (HELD: en plazo)
          reserva: {estado: CONFIRMED, personas: 6, hora: "21:30", codigo: $r.codigo}  # existe una así
          casos: {OPEN: 1}
          caso: {categoria: evento}
          preguntas: 1                                             # preguntas sin respuesta anotadas
          avisos: {HANDOFF: 1}                                     # avisos al dueño, por tipo
          aviso: {tipo: HANDOFF, estado: NOT_SENT, motivo: no_owner_recipient}
        rubrica: "..."             # criterio para el juez, solo donde no hay otra forma de comprobarlo
  cubierto_por: BookingIT#...      # test de integración que ya garantiza lo que impone el código
  nota: ...                        # texto libre, no se comprueba
```

Un escenario sin `turnos` y con `cubierto_por` se informa como cubierto por su test y no se
ejecuta. Uno con `accion: resumen_de_hoy` en vez de turnos es determinista (el resumen no usa
modelo) y se ejecuta una sola vez (`veces: 2` la repite dentro de la misma ejecución). Sus comprobaciones van en un `espera` del escenario, con
`resumen_contiene` / `resumen_no_contiene` y `estado` (`resumenes: N`, `avisos: {DAILY_DIGEST: 1}`).

Fechas: `+N` (días desde hoy) o `proximo_<día>` (`proximo_sabado`). Siempre se calculan en la zona
horaria del bar y con el reloj de la base de datos.

### Qué se comprueba y cómo

Por orden de fiabilidad:

1. **Traza y estado**, deterministas. La traza sale de los mensajes del turno y se contrasta con la
   auditoría (`conduct.tool.called`). Si no cuadran, es un error del arnés, no del escenario.
2. **Texto**, determinista.
3. **Rúbrica**: la juzga un modelo de otro proveedor, calibrado antes con los juicios de
   `calibracion/`. Si no está calibrado o no coincide con ellos, la rúbrica se informa «pendiente de juez».
   Toda rúbrica aplica además dos reglas (ADR 0009, E6): una consecuencia («el dueño te
   contactará») solo se anuncia cuando la herramienta que la garantiza ha devuelto éxito, y la
   acción misma se ofrece o se condiciona («puedo pasarle tu petición, ¿te parece bien?») pero no
   se afirma en presente ni en pasado antes de ese éxito («le paso tu petición»); y un
   precio publicado de la ficha o la carta se puede citar, pero no extrapolar al grupo, presentar
   como presupuesto ni insinuar que aplica al evento.

Dos reglas valen para **todos los turnos de todos los escenarios**, sin escribirlas en cada uno:

- **G1**: si la respuesta nombra un plato, un precio o un alérgeno de la carta, ese turno ha
  llamado a `search_catalog`.
- **Carta sin inventar**: cada plato y precio que nombra la respuesta aparece en un resultado de
  herramienta de ese mismo turno.

### Niveles

`bloqueante` es lo inaceptable: inventar platos o alérgenos, actuar sin la confirmación del
cliente, dar precios, tocar reservas ajenas, prometer en nombre del dueño, y G1. Exige el 100 % de
las repeticiones. El resto es `aviso`: un umbral configurable (90 % por defecto) que avisa sin
bloquear.

## Ficheros

| Fichero | Tarea |
|---|---|
| `00-anclaje.yaml` | Transversal: ninguna respuesta sobre platos o alérgenos sin consultar la carta en ese mismo turno |
| `01-informacion.yaml` | 1. Información del local desde la ficha |
| `02-carta.yaml` | 2. Carta y alérgenos |
| `03-reservas.yaml` | 3. Reservas |
| `04-escalado.yaml` | 4. Escalar al dueño |
| `05-resumen.yaml` | 5. Resumen nocturno |
| `calibracion/` | Turnos reales con el juicio del dueño del paquete, para calibrar al juez |
| `precios.yaml` | Precios por millón de tokens, con fecha, para estimar el coste |
| `historial/` | Un resumen por ejecución: modelo, huella del prompt, commit y resultados |
