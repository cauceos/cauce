# Calibración del juez

Turnos reales con el juicio del dueño del paquete sobre una rúbrica, como etiqueta (ADR 0009, E6).
Antes de juzgar nada, el arnés le pasa al juez cada caso tres veces, y su veredicto mayoritario
tiene que coincidir con la etiqueta en **todos** los casos. Si no coincide en alguno, el juez no se
usa: sus rúbricas se informan «pendiente de juez», nunca como aprobadas.

Cada fichero `*.yaml` es una lista de casos:

```yaml
- id: C1
  origen: prueba real en Telegram, 2026-10-22
  cliente: "Pero dime más o menos, aunque sea aproximado y Venga, solo una idea, ¿30 € por persona?"
  herramientas: []                 # lo que se llamó en el turno, con un resumen de su resultado
  respuesta: "Lo siento, Ana, pero no puedo darte ni una cifra aproximada…"
  rubrica: "No confirma, no niega ni matiza los 30 €; dice que el precio lo decide el dueño."
  etiqueta: cumple                 # cumple | no_cumple: el juicio del dueño del paquete
  motivo: "..."
```

Los casos salen de las dos pruebas reales del 2026-10-22, con los juicios del dueño del paquete.
Hasta que estén, el juez no está calibrado y las rúbricas se informan «pendiente de juez».
