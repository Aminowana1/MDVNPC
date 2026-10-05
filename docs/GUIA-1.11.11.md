# MDVNPC 1.11.11 — recuperación de inmovilidad y vallas cubiertas

Esta entrega corrige dos problemas de 1.11.10 y mantiene los valores de
configuración, la búsqueda de Paper y las exclusiones del fallback.

## NPC quietos durante la caminata

El contador de inmovilidad se evalúa antes de que una recuperación de suelo
bloqueada pueda detener la actualización. Los pequeños cambios de Y no
reinician la espera: se comprueba el desplazamiento horizontal acumulado.
Un salto activo conserva el control de sus siguientes posiciones.

La recuperación intenta primero la altura y distancia configuradas. Si la
posición de llegada o el arco están bloqueados, prueba avances menores y
un salto en el sitio. Si falta espacio sobre la cabeza, prueba alturas
menores. Cada opción requiere apoyo y comprobación completa de colisiones;
no atraviesa paredes ni vallas, ni inventa apoyo en el aire.

Se mantienen los 3 segundos entre intentos cuando el NPC no avanza. Un
salto vertical no cuenta como progreso hacia el goal ni concede llegada
a cama, silla o trabajo. Las opciones se limitan a un máximo de cinco
alturas y cinco distancias, reutilizando la caché de formas de una sola
actualización.

```yaml
routines:
  stuck-hop:
    enabled: true
    delay-seconds: 3
    height: 0.6
    distance: 1.0
```

`height` y `distance` son los máximos del intento de recuperación. No hace
falta cambiar las configuraciones existentes. Dormir, estar sentado,
trabajar en el puesto, bailar, las reacciones, las bebidas y las pausas
siguen excluidos.

## Vallas cubiertas o más bajas

Una slab, trampilla o bloque colocado directamente encima de una valla
o una puerta de valla cerrada se considera parte de esa barrera. No se
acepta como suelo, escalón, destino ni apoyo de aterrizaje. La comprobación
incluye las filas inferiores, alturas fraccionarias y vallas cuyo techo
coincide con la altura de los pies o está hasta medio bloque por debajo.
Esto también se comprueba cuando Paper propone un tramo horizontal.

Se mantienen los pasos por puertas de valla abiertas y los pisos de
puentes separados de la valla inferior por otra fila.

## Archivos y métodos

- `RoutineService`: contador horizontal, orden de la recuperación de suelo
  y llamada a `startRecoveryHop`.
- `RoutineNavigator`: `startRecoveryHop` y `queueHop`; el método `startHop`
  conserva su contrato de salto con dimensiones exactas.
- `RoutineTerrain`: `fenceClearAtBase`, barreras proyectadas y clasificación
  de apoyos sobre vallas, incluidos `stand`, `supportHeight`, `fallColumn`,
  `recoveryHopLanding` y selección de superficies.
- Pruebas: `RoutineHopTest`, `RoutineStuckHopTest` y `RoutineTerrainTest`.

Ver `VALIDACION-1.11.11.md` para los resultados y límites de la validación.
