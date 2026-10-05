# MDVNPC 1.10.0 — Baile y alturas de las poses

Actualización sobre el ZIP 1.9.0 del usuario. Se mantienen los trabajos, el editor,
las seis canciones y las animaciones de los músicos.

## Baile

Solo los NPC con rasgo **Fiestero** que están en un goal **Sentarse** salen a bailar
cuando tienen músicos trabajando cerca. El baile sigue limitado a unos 3 bloques
del músico. Si falta suelo plano o espacio, el NPC espera sentado.

Los destinos ya no se ajustan hacia arriba a mesas/escalones. Los pasos sobre la pista
mantienen la misma altura y revisan obstáculos y suelo; los saltos son un gesto vertical
que vuelve al punto del suelo. Los participantes se reparten y dejan espacio entre ellos.

El ciclo predeterminado es **60 segundos bailando → regresar a la silla → 30 segundos
sentado → volver a bailar si sigue habiendo música y espacio**. El tiempo de aproximación
y regreso no consume esos 30 segundos de descanso. El horario de la rutina sigue mandando:
si termina el goal, se cancela el baile y se continúa con el siguiente.

Para asignar el rasgo, abre `/mdvnpc edit <id>` → **Rasgo** → **Fiestero**, o usa:

```text
/mdvnpc rasgo <id> fiestero
```

## Configuración global

Añade o cambia estas líneas **dentro de la sección `routines` que ya existe** en
`plugins/MDVNPC/config.yml`:

```yaml
routines:
  dancing: true
  dance-duration-seconds: 60
  dance-seated-seconds: 30
  seat-offset-y: 0.5
  name-offset-seated-y: 0.0
  name-offset-sleeping-y: 0.0
```

No reemplaces toda la sección: conserva sus demás opciones. Aplica los cambios con
`/mdvnpc reload`.

| Opción | Significado |
| --- | --- |
| `dance-duration-seconds` | Duración del baile desde que llega a la pista. Entero de 1 a 600. |
| `dance-seated-seconds` | Descanso completo desde que vuelve a sentarse. Entero de 1 a 600. |
| `seat-offset-y` | Altura del asiento respecto al bloque de escalera. Predeterminado: 0.5. |
| `name-offset-seated-y` | Ajuste adicional del nombre mientras está sentado. |
| `name-offset-sleeping-y` | Ajuste adicional del nombre mientras está acostado. |

Los tres offsets se expresan en bloques, admiten decimales de -4 a 4 y afectan a todos
los NPC. Un offset del nombre positivo lo sube y uno negativo lo baja. Por ejemplo,
`name-offset-seated-y: -0.3` baja el nombre 0.3 bloques respecto a su altura habitual.
Al levantarse se restaura el ajuste previo del nombre; el texto, prefijo y visibilidad
siguen siendo los configurados para ese NPC.

Si LibsDisguises usa nombres nativos, se muestra un rótulo temporal durante la pose
para poder aplicar un offset distinto de cero. Se elimina al levantarse y se restaura
el nombre original. Los nombres ocultos siguen ocultos. No requiere cambiar la
configuración global de LibsDisguises.

Las antiguas opciones `dance-min-seconds` y `dance-max-seconds` se sustituyen por
`dance-duration-seconds`. Si solo tienes esas opciones antiguas, se aplica el nuevo
predeterminado de 60 segundos.

## Actualizar y compatibilidad

Apaga el servidor, respalda `plugins/MDVNPC/`, sustituye el JAR anterior por
`MDVNPC-1.10.0.jar` y arranca. Conserva `NPCs/` y los archivos actuales; no necesitas
recrear NPC ni borrar `config.yml`.

Los valores nuevos usan los predeterminados del JAR aunque no estén escritos en tu
archivo. **Si tu archivo conserva `seat-offset-y: 0.0`, cámbialo a `0.5` para aplicar la
nueva altura:** una opción ya guardada se respeta al actualizar.

Requisitos: Java 21, Paper/Purpur 1.21.6, LibsDisguises 11.0.18 y PacketEvents 2.14.0.

## Rendimiento y comprobación

El baile reutiliza el reloj de rutinas. La navegación Paper se reserva para los
trayectos; los pasos pequeños de baile no abren una búsqueda de ruta nueva cada vez.
La pista y la separación se calculan de forma local, sin consultar jugadores/NPC de
todo el mundo ni cargar chunks. Un espacio pequeño admite menos bailarines a la vez.
El rótulo temporal, cuando hace falta, añade una entidad de texto por NPC en esa pose
y reutiliza las actualizaciones existentes; no crea un temporizador propio.

La compilación y las pruebas de regresión se documentan en `dist/VERIFICACION.txt`.
Las pruebas automatizadas usan MockBukkit/Mockito; la apariencia en un cliente conectado
y el consumo real de CPU/RAM/TPS deben comprobarse en el servidor.
