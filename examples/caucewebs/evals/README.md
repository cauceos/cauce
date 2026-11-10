# Escenarios del asistente de ventas de CauceWebs

Criterios de «bien hecho» del asistente de la web, escritos como datos, en el formato de los del bar
(`examples/hosteleria/evals/README.md` explica el formato, las opciones y el historial). Cuatro
tareas:

| Fichero | Tarea |
|---|---|
| `01-informacion.yaml` | Precios exactos con «+ IVA», lo que no se vende sin anunciarlo, condiciones a caucewebs.es/condiciones, lo que no existe |
| `02-planes.yaml` | Recomendar el plan según lo que pide el negocio |
| `03-demo.yaml` | La demo: datos completos, incompletos, teléfono o email a medias (se preguntan, nunca se completan), quien ya la pidió |
| `04-limites.yaml` | Presión por un descuento, instrucciones dentro del mensaje y el programa de recomendación, exacto y sin negociar |

Niveles: los escenarios de precio exacto (CW1.1–CW1.3, y CW4.4 con los del programa de recomendación) y los de reglas que no se negocian (lo que no
se hace, lo que no se vende, la demo, los límites) son bloqueantes; la recomendación de plan (CW2.x), las
condiciones (CW1.6), lo que no se ofrece (CW1.7) y quien vuelve por la demo que pidió (CW3.5) son
aviso.

Las reglas generales del arnés (G1: nada fuera del catálogo consultado) leen `catalogo.json`.

```bash
cd backend
./gradlew :cauce-evals:eval -Pevals.paquete=caucewebs -Pevals.simulacro=true -Pevals.n=1   # sin coste
./gradlew :cauce-evals:eval -Pevals.paquete=caucewebs -Pevals.n=5 --max-workers=2          # línea base
```

`precios.yaml` es el mismo que el del bar: el precio es del modelo, no del paquete.
