# Escenarios del agente de clientes del bar

Criterios de «bien hecho» de las cinco tareas, escritos como datos para que el arnés de
`cauce-evals` los ejecute contra un modelo real cuando exista (ADR 0007, B12). Hasta entonces
son la lista de comprobación de la prueba manual, y las garantías que impone el código ya
tienen su test de integración (se indica en cada escenario con `cubierto_por`).

## Formato

Cada fichero `*.yaml` es una lista de escenarios:

```yaml
- id: H2.2                      # tarea.número, estable
  titulo: ...
  obligatorio: true             # si falla, el agente no se despliega
  datos: bar-ejemplo            # fixture: ../bar-ejemplo/{ficha,carta,agenda}.json
  previo:                       # estado adicional antes del primer turno (opcional)
    - reserva: {fecha: +3, turno: cena, personas: 50, estado: CONFIRMED}   # fechas relativas a hoy
  turnos:
    - cliente: "texto que escribe el cliente"
      espera:
        herramientas: [search_catalog]          # llamadas en ESTE turno (traza C5), en cualquier orden
        herramientas_no: [confirm_booking]      # no llamadas en este turno
        argumentos: {search_catalog: {without_value: gluten}}   # subconjunto de argumentos
        estado: {reservas_confirmadas: 1}       # registros tras el turno
        respuesta_contiene: ["no consta"]       # comprobación textual, sin distinguir mayúsculas
        respuesta_no_contiene: ["huevo"]
        rubrica: "..."                           # criterio para un juez LLM, cuando haga falta
  cubierto_por: BookingApiIT#aBooking_...      # test de integración que ya garantiza lo que impone el código
```

Las comprobaciones se ordenan por fiabilidad: primero la traza de herramientas y el estado
(deterministas), después el texto, y la rúbrica solo para lo que no se puede comprobar de
otra forma.

## Ficheros

| Fichero | Tarea |
|---|---|
| `00-anclaje.yaml` | Transversal: ninguna respuesta sobre platos o alérgenos sin consultar la carta en ese mismo turno |
| `01-informacion.yaml` | 1. Información del local desde la ficha |
| `02-carta.yaml` | 2. Carta y alérgenos |
| `03-reservas.yaml` | 3. Reservas |
| `04-escalado.yaml` | 4. Escalar al dueño |
| `05-resumen.yaml` | 5. Resumen nocturno |
