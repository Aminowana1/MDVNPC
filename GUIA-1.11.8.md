# MDVNPC 1.11.8 — corrección de desniveles físicos

## Problema corregido

En 1.11.7 un NPC podía quedarse girando o recalculando la misma ruta cuando el camino
mezclaba alturas que no coinciden exactamente con la celda Y del pathfinder. Era visible
sobre slabs y alfombras y también al entrar/salir de `DIRT_PATH`, `MUD` o `SOUL_SAND`.
Un puesto de trabajo sobre carpet podía además ser rechazado por el editor porque
`Material#isSolid()` no representa correctamente todas las superficies caminables.

## Qué cambia

`RoutineNavigator` separa ahora dos conceptos: el nodo entero que necesita Paper para
buscar la ruta y la altura física real donde deben quedar los pies del NPC. Al reproducir
la ruta sondea una franja estrecha delante del centro del cuerpo, detecta el próximo
soporte por su forma de colisión y empieza a subir antes de chocar, incluso si dos nodos
nativos tienen la misma Y. El descenso sigue asentándose sobre el soporte real después
de despejar el borde anterior.

También se evita perseguir verticalmente una Y nativa obsoleta al llegar al centro de un
waypoint. Las diferencias pequeñas se consideran completadas cuando el NPC ya está
apoyado en una superficie física válida.

Antes de `Pathfinder.findPath(...)`, un destino con altura física fraccionaria se convierte
a su celda transitable. Por ejemplo, el NPC conserva como destino visual la parte superior
de carpet/path/mud, pero Paper recibe el nodo de aire caminable que corresponde encima.

## Superficies cubiertas

La lógica usa la forma de colisión, no una lista cerrada de materiales. Por eso cubre,
entre otras combinaciones:

- bottom/top/double slabs y transiciones con bloques completos;
- stairs y sus distintos peldaños;
- `DIRT_PATH`;
- `MUD`;
- `SOUL_SAND`;
- carpets y otras capas finas con colisión;
- pavimentos mixtos con pequeñas subidas y bajadas consecutivas.

Se mantiene el máximo normal de subida de un bloque. Vallas, muros, techos bajos,
hazards y obstáculos siguen pasando por las comprobaciones de colisión existentes.

## Puestos de trabajo y puntos WALK

El editor ahora considera transitable un bloque cuando tiene una forma de colisión segura,
en vez de exigir `Material#isSolid()`. Esto permite guardar directamente un WORK/WALK
sobre una alfombra o una losa. Si se añade una superficie parcial bajo un NPC antiguo y
sus pies quedan ligeramente incrustados, el inicio de ruta puede corregir sólo ese pequeño
desfase. Esa recuperación está limitada a una superficie parcial y a aproximadamente medio
bloque, por lo que no sirve para atravesar un bloque completo.

## Goals afectados

No se implementó un parche separado para cada modo. Meta, ciclo, aleatorio, WORK,
aproximación a cama/asiento y retornos terminan usando `RoutineNavigator.move(...)`;
por tanto la corrección se aplica a todos esos caminos.

## Compatibilidad

No cambia el formato de `npc.yml`, `routines.yml`, tiendas ni skins. La 1.11.8 puede
sustituir a 1.11.7 conservando la carpeta de datos.

## Validación

Se añadieron regresiones de fuente para: slab y carpet entre nodos de Paper con la misma
Y, camino mixto path/mud/soul-sand/carpet/slab, destinos fraccionarios, selección de
superficies finas y recuperación segura de un NPC restaurado dentro de carpet.

El entorno donde se preparó esta entrega no dispone de Maven ni de la caché de
dependencias, así que esas pruebas no se presentan como ejecutadas. Ejecutar:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

y después probar en Purpur/Paper 1.21.6 un trayecto real en ambos sentidos con una
mezcla de full blocks, slabs, stairs, path, mud, soul sand y carpets.
