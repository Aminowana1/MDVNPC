# MDVNPC 1.11.13 — Tienda con categoría Herrero

El Herrero conserva los intercambios y las reglas de atención de una tienda.
La animación se ejecuta durante su goal **Trabajo**, después de llegar al puesto
normal que ya configuraste. El puesto sigue siendo su lugar de atención y su
destino de regreso cuando una estación no se puede usar.

## Configuración desde el juego

1. Abre `/mdvnpc edit <id>` y elige **Trabajo del NPC → Tienda**.
2. En **Rutinas y horarios**, crea o edita un goal **Trabajo** y fija su punto
   normal y horario. Ese punto es necesario antes de marcar estaciones.
3. En **Trabajo → Tienda**, elige **Herrero**. También puedes abrir el menú
   de categorías con `/mdvnpc herrero <id>`, o con **Categoría y estaciones**
   en el editor del NPC.
4. Pulsa **Configurar las tres estaciones** y marca los bloques que indique
   el chat: **1. Fundición → 2. Caldero con agua → 3. Yunque**.

Los menús y la selección requieren el permiso `mdvnpc.admin`. Marca cada
estación con clic izquierdo o derecho usando la mano principal. Escribe
`cancelar` para salir de la selección. Selecciona las estaciones en el mismo
mundo del NPC. La selección no consume ni modifica los bloques.

También puedes cambiar una sola estación desde su botón. El clic derecho
sobre ese botón quita la estación. Volver a **Vendedor** desactiva la animación
y conserva los intercambios y las coordenadas de las estaciones.

## Cómo preparar las estaciones

- **Fundición:** marca cualquier bloque como centro de los efectos. Puedes
  destruir ese bloque después: sus coordenadas quedan guardadas y los efectos
  siguen apareciendo en ese espacio, incluso si queda aire. El NPC busca una
  posición con **un bloque entre él y la estación**. Deja libre ese espacio y
  una superficie donde pueda pararse y llegar caminando.
- **Caldero:** marca un caldero que tenga agua. Debe conservar el agua durante
  la animación. Deja al menos un lado transitable junto al caldero.
- **Yunque:** marca un yunque normal, astillado o dañado. Deja al menos un lado
  transitable junto al yunque.

El NPC intenta acercarse por los lados disponibles usando el navegador de
rutinas de Paper. Una estación inaccesible, situada en otro mundo o en un chunk
descargado no provoca un traslado forzado hasta ella.

## Secuencia de trabajo

La secuencia predeterminada es:

1. Camina desde el puesto a la **fundición**, lanza varios `RAW_IRON` visuales
   y aparecen fuego, humo y sonidos de fundición durante **8 segundos**.
2. Lleva un **lingote de hierro** al **caldero**, lo lanza dentro y aparecen
   humo, burbujas y sonidos de enfriamiento durante **5 segundos**.
3. Camina al **yunque** con una **MACE vanilla** en la mano. Hace gestos de
   golpe y reproduce sonidos y partículas durante **120 segundos**.
4. Vuelve al **caldero** y lanza una **espada de hierro**, con efectos de
   enfriamiento durante **5 segundos**.
5. Vuelve al **yunque**, golpea con la MACE durante otros **120 segundos** y
   comienza de nuevo por la fundición.

Los desplazamientos se suman a esos tiempos. Los objetos lanzados son
**objetos visuales**: desaparecen al quemarse o enfriarse, no se pueden recoger
por jugadores ni tolvas y no generan recursos reales. La animación tampoco
consume agua del caldero, minerales, lingotes, espadas ni herramientas.

## Tiempos configurables

Estos ajustes van en `config.yml`, en la sección global `blacksmith`:

```yaml
blacksmith:
  smelt-seconds: 8
  quench-seconds: 5
  anvil-seconds: 120
  travel-timeout-seconds: 30
  retry-seconds: 30
```

`anvil-seconds` se aplica a **cada** una de las dos sesiones del yunque.
`quench-seconds` se aplica tanto al lingote como a la espada.
`travel-timeout-seconds` limita el viaje a una estación, y `retry-seconds`
define cuánto espera en su puesto antes de volver a intentar la animación.
La altura, distancia e intervalo del salto de recuperación mantienen los
ajustes existentes de `routines.stuck-hop`.

## Regreso y pausas

Si faltan estaciones, se retira el agua del caldero, desaparece el yunque,
no existe un lado donde pararse o no logra llegar, el NPC vuelve caminando
a su **puesto normal** y atiende como un vendedor. Después del tiempo de
reintento puede volver a probar las estaciones. Sin una ruta transitable
hasta el puesto, también depende de la recuperación habitual de las rutinas.

Mientras un jugador tiene abierto su comercio, el Herrero detiene el recorrido
y atiende la compra. Al cerrar el comercio, regresa al puesto antes de volver
a intentar la animación. Los golpes y las bebidas ofrecidas interrumpen la
animación para ceder la pose a la reacción o bebida correspondiente.

Al acabar el horario de Trabajo continúa el siguiente goal. Al cambiar de
actividad, recargar el plugin, retirar el NPC o suspenderlo por falta de
observadores, se retiran los objetos visuales y se libera la animación.

## Comprobación en el servidor

La comprobación visual en un servidor Paper con LibsDisguises queda pendiente.
Prueba el recorrido completo, abre y cierra el comercio durante el trabajo,
retira temporalmente el agua o el yunque y confirma el regreso al puesto.
Comprueba también que los objetos visuales no se pueden recoger y que el NPC
abandona las estaciones cuando termina su horario.
