# MDVNPC 1.10.1 — Nombres, asientos y canciones

Se conserva el baile de 1.10.0, sus tiempos de 60/30 segundos, los offsets y el editor.

## Destello de soportes

LibsDisguises puede usar armor stands para los nombres. Al recrear esos rótulos, el
cliente recibe la entidad y después su invisibilidad. MDVNPC usa un TextDisplay para
los nombres de sus NPC cuando ese modo está activo, ocultando el nombre de LD antes
de que aparezca el disfraz. El texto no tiene un modelo de armor stand y se conserva
al sentarse, bailar y levantarse. Los otros modos de nombres de LD siguen funcionando
como antes y no se cambia su configuración global.

Los soportes de asiento mantienen invisibilidad persistente y reparan sus flags si
se alteran. La limpieza del asiento y del nombre se completa incluso si falla una
animación, un montaje o un desmontaje. No requiere cambiar opciones para activarlo.

Los ajustes `routines.name-offset-seated-y`, `routines.name-offset-sleeping-y` y
`routines.seat-offset-y` de [la guía de 1.10](GUIA-1.10.md) se conservan.

## Música

Se añaden dos composiciones originales:

- **La Vela del Mesón:** 30 segundos, aire frigio de taberna en 3/4.
- **Jiga del Puerto:** 32 segundos, aire de danza en 6/8.

Ambas tienen melodía de flauta y melodía/arpegio de guitarra coordinadas. Funcionan
en dúo y con un único músico. Se incluyen automáticamente en la elección aleatoria;
el repertorio tiene ocho canciones.

Para asignar el trabajo, abre `/mdvnpc edit <id>` → **Trabajo del NPC** → **Músico**
y elige **Flauta** o **Guitarra**. También puedes usar:

```text
/mdvnpc trabajo flautista musico flauta
/mdvnpc trabajo guitarrista musico guitarra
```

Sustituye esos ID por los tuyos y configura una rutina de **Trabajo** con horario y
puesto desde el editor. Los temas nuevos entran automáticamente en el repertorio.

La revisión de las seis canciones anteriores confirma que todas tienen notas para
ambos instrumentos. Ninguna contiene una parte de flauta completamente vacía. Las
pruebas reproducen cada partitura completa en dúo y con cada instrumento solo.

Si un músico que estaba trabajando se desplaza fuera de su puesto, la rutina ahora
lo guía de vuelta y lo reincorpora al conjunto al llegar. Antes podía seguir marcado
como trabajando, pero quedarse sin audio ni gestos por estar demasiado lejos del
puesto. Se conserva el mismo destino y se respeta su horario. Esto corrige ese caso
de recuperación; no confirma por sí solo la causa del silencio observado en tu servidor.

Si un músico vuelve a quedarse en silencio, consulta su estado mientras sucede:

```text
/mdvnpc status flautista
/mdvnpc status guitarrista
```

Usa los ID reales de tus NPC. El estado indica la canción, el conjunto, los oyentes
dentro del radio y si está fuera del puesto/horario o haciendo una pausa por bebida
o reacción a un golpe. El sonido conserva su radio local por NPC (14 bloques por
defecto); un jugador puede estar cerca de la guitarra y fuera del alcance de la flauta.
`/mdvnpc status` sin ID conserva el resumen general.

## Actualizar y rendimiento

Apaga el servidor, respalda `plugins/MDVNPC/`, sustituye el JAR por `MDVNPC-1.10.1.jar`
y arranca. Conserva todos los NPC y la configuración; no necesitas recrearlos.

Los nombres usan los relojes existentes y solo envían cambios cuando cambian el texto
o la posición. No se añade un temporizador por NPC ni escaneos globales de jugadores.
En el modo ARMORSTANDS de LD, un TextDisplay reemplaza el soporte del nombre.
Los nuevos temas se indexan una vez al iniciar y mantienen el reloj musical compartido.

Java 21, Paper/Purpur 1.21.6, LibsDisguises 11.0.18 y PacketEvents 2.14.0.
Las pruebas automatizadas y la compilación se detallan en `dist/VERIFICACION.txt`.
La apariencia debe comprobarse también en un cliente real; no se ha medido CPU/RAM/TPS.
