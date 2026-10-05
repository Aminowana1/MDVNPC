# MDVNPC 1.11.4 — Corrección de desniveles pequeños

Esta revisión corrige un atasco observado al atravesar superficies cuya altura de colisión no coincide con un bloque completo, especialmente `DIRT_PATH` y `MUD`.

## Qué cambia

- El navegador deja de depender exclusivamente de la Y del siguiente waypoint para decidir cuándo bajar o subir.
- Mientras cruza una superficie más baja, el NPC se asienta sobre el soporte real cuando su cuerpo ya despejó el borde.
- Si el cuerpo toca el siguiente bloque alto antes de que Paper cambie la Y del waypoint, se detecta el escalón físico delante y se realiza la subida local.
- Se toleran pequeñas diferencias verticales al cerrar waypoints (hasta 0.20 bloques), evitando oscilaciones y giros continuos por diferencias de 1/16 o 1/8 de bloque.
- No se salta una diferencia de slab (0.5) ni un bloque completo; esas siguen resolviéndose mediante movimiento vertical real.

## Sistemas beneficiados

La lógica está en `RoutineNavigator`, por lo que se aplica a todos los consumidores de navegación: modo meta, ciclo, aleatorio, cama, puesto de trabajo y demás rutinas que usan el navegador común.

## Pruebas nuevas

Se añadieron regresiones para:

- `STONE -> DIRT_PATH -> STONE`
- `STONE -> MUD -> STONE`

En ambos casos la prueba exige llegar al destino sin descartar/recalcular una ruta válida y exige que el NPC realmente se asiente en la superficie baja antes de volver a subir.
