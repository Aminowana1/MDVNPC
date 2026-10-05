# MDVNPC 1.11.2 — Escaleras, puertas y sillas

## Caminata por escaleras y losas

La navegación comprueba las cajas que forman la colisión real de cada bloque
(su forma voxel). Una escalera deja de tratarse como un cubo completo: se
comprueban sus peldaños, el espacio para el cuerpo del NPC y el apoyo bajo sus
pies. Las losas también usan su altura real.

El NPC se aproxima al frente del escalón antes de elevarse. La subida, la bajada
y el avance comparten el presupuesto de movimiento de la velocidad del goal;
no se eleva hasta la altura del siguiente bloque al comenzar cada tramo.

Las rutinas de caminar, trabajar, ir a una silla o regresar a ella pueden
cambiar de nivel y subir bloques cuando existe una ruta con apoyo y espacio
suficiente. La limitación de mantenerse en el mismo nivel corresponde al
movimiento dentro de la pista de baile. Los gestos de salto del baile siguen
volviendo a esa pista, sin usarse para subirse a muebles.

## Puertas de madera

Una puerta cerrada se abre para el paso del NPC cuando su uso está permitido.
Después se intenta cerrar cuando el hueco de dos bloques queda libre. La
comprobación de ocupación se limita al paso de la puerta: un NPC sentado o
quieto a un lado ya no impide el cierre por estar cerca.

Las puertas que ya estaban abiertas también se registran cuando la ruta pasa
por ellas. Esto conserva el ajuste existente de `config.yml`:

```yaml
routines:
  close-preopened-doors: true
```

Con `false`, el cierre automático de esta opción no se aplica a las puertas
encontradas abiertas. Las puertas abiertas por la propia rutina siguen su ciclo
normal de apertura y cierre.

Se respetan las entidades que ocupan el hueco, las puertas alimentadas por
redstone, las protecciones y los eventos de otros plugins. Una puerta modificada
después del paso se vuelve a comprobar antes de cerrarse.

## Sillas y recuperación de la pose

Sentarse exige que el NPC quede montado en el soporte invisible de esa silla.
El resultado de la operación de montaje y la referencia al vehículo deben
coincidir; estar dentro de otro vehículo no cuenta como estar sentado.

La rutina revisa el montaje periódicamente. Si el NPC se desmontó y sigue cerca
de la silla y su salida, se vuelve a montar. Al entrar nuevos observadores o
reanudar una rutina suspendida, se refresca el montaje para volver a mostrar la
pose sentada. Un montaje estable no se desmonta y monta en cada actualización.

Si el montaje falla después de haber movido al NPC sobre la silla, se elimina
el soporte y se permite regresar a su salida local comprobada. Si desaparece
el soporte, se mueve lejos o el NPC queda desplazado lejos de su silla, se
abandona esa pose y se vuelve a intentar mediante la navegación habitual.

`routines.seat-offset-y` sigue regulando la altura del asiento, con valor
predeterminado `0.5`. Los nombres mantienen el sistema nativo de LibsDisguises
restaurado en 1.11.1.

## Actualizar el servidor

1. Apaga el servidor y respalda `plugins/MDVNPC/` y el JAR anterior.
2. Sustituye el JAR anterior por `MDVNPC-1.11.2.jar`; conserva un solo JAR de MDVNPC en `plugins/`.
3. Arranca conservando los archivos de configuración, NPC, rutinas y tiendas existentes.

No hace falta recrear los NPC ni sus goals. La base sigue siendo Java 21,
Paper/Purpur 1.21.6, LibsDisguises 11.0.18 y PacketEvents 2.14.0.

Si compilas las fuentes, usa Java 21 y `mvn --batch-mode --no-transfer-progress clean verify`,
o el workflow existente de GitHub. Los resultados concretos de compilación y
pruebas de esta entrega se encuentran en `dist/VERIFICACION.txt`.

## Comprobación dentro del juego

- Observa una rutina que suba y baje escaleras y losas, y otra que necesite subir un bloque para llegar a su destino.
- Comprueba una puerta inicialmente cerrada y otra abierta: ambas deben cerrar tras quedar libre el paso con la opción anterior activa. Repite con un NPC quieto al lado y con una entidad ocupando el hueco.
- Observa un NPC sentado, aléjate hasta suspender su rutina y vuelve. Comprueba también la pose desde otro cliente que acaba de acercarse.
- Retira una silla durante su uso y confirma que el NPC abandona esa pose y puede buscar un destino válido. Comprueba que el baile no ascienda a mesas ni escalones.

Las pruebas automatizadas verifican la geometría, las transiciones y las llamadas
de montaje. Esta entrega no afirma una validación visual con clientes de
Minecraft ni mediciones de CPU, RAM o TPS en un servidor real.
