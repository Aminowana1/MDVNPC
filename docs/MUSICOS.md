# MDVNPC 1.6.0 — Músicos

Reemplaza el JAR con el servidor apagado, conservando una copia de la carpeta del plugin.
Mantiene Java 21 y las dependencias/versiones del proyecto original (Paper/Purpur 1.21.6,
LibsDisguises y PacketEvents). No necesita MythicMobs ni un paquete de recursos para la música.

## Asignación

Con permiso `mdvnpc.admin`, sobre NPC existentes:

```text
/mdvnpc trabajo flautista musico flauta
/mdvnpc trabajo guitarrista musico guitarra
```

Si no existen, créalos primero con `/mdvnpc create flautista Steve` y
`/mdvnpc create guitarrista Alex` (puedes elegir otras skins).

Asigna a cada uno una rutina de trabajo usando el editor existente o estos comandos.
Completa la selección del puesto en el suelo antes de ejecutar el siguiente:

```text
/mdvnpc routine flautista goal 1 trabajo 07:00 22:00 2.4
/mdvnpc routine guitarrista goal 1 trabajo 07:00 22:00 2.4
```

Sitúa los puestos a un máximo de 8 bloques entre sí. Ambos deben haber llegado al puesto
y estar dentro de su horario. Sin rutina de trabajo no tocan. Un músico solo también toca.
Para inspeccionar la rutina: `/mdvnpc routine flautista status`.
Para quitar el oficio: `/mdvnpc mode flautista normal`; para volver a vender, `mode ... shop`.
Los músicos no abren tiendas ni ejecutan comandos por clic. Las ofertas antiguas se conservan.

El trabajo/instrumento se guarda en `NPCs/<id>/npc.yml`, bajo `npcs.<id>.mode`, como
`musician_flute` o `musician_guitar`, usando el repositorio existente del plugin.

## Música y alcance

- Tourdion: melodía de flauta del texto aportado, conservando tonos y delays; guitarra
  coordinada con melodía punteada y apoyos armónicos. El último compás sostiene 18 ticks.
- Ronda del Jabalí Alegre: melodía del mismo texto, adaptada a ambos instrumentos.
- Danza del Farol: composición original de estilo medieval/taberna, con arpegios de guitarra.

Cada sesión elige aleatoriamente una de las tres canciones al empezar y al terminar la
anterior, con una pausa de un segundo entre canciones. Pueden repetirse. Las partes usan
el mismo reloj del servidor. Al cambiar la composición del grupo, los grupos afectados
reinician juntos una canción en la siguiente actualización (hasta aproximadamente un segundo).
Los grupos estables mantienen su canción. Se agrupan por vecinos conectados, en el mismo
mundo; una cadena de músicos puede formar un conjunto mayor de 8 bloques.

El sonido se envía individualmente a jugadores dentro de 14 bloques de cada NPC, con
distancia tridimensional comprobada en cada envío. Un jugador que entra puede tardar
hasta un segundo en oírlo. El control de volumen del cliente es «Tocadiscos/Bloques musicales».
La sincronización es por ticks del servidor; lag de servidor/red puede afectar la escucha.

Opcional: añade a `config.yml` existente (los valores predeterminados funcionan sin editarlo):

```yaml
music:
  group-radius: 8
  audio-radius: 14
```

Aplica con `/mdvnpc reload`. Audio limitado a 12–15 bloques y agrupación a 2–16 bloques.

## Rendimiento y ciclo de vida

Las rutinas registran los músicos al llegar a WORK y los eliminan al salir, desaparecer
o descargarse. Un único temporizador atiende todas las sesiones, y se cancela cuando
no quedan músicos registrados. No hay búsquedas globales de jugadores o NPC añadidas
por el servicio musical. Una cuadrícula espacial agrupa solo músicos trabajando cada
20 ticks; las audiencias locales se guardan durante esos 20 ticks. Las partituras se
cargan una vez y se indexan por tick. Solo se envían notas de la parte asignada al NPC.
Las comprobaciones de horario, validez, mundo y distancia impiden notas nuevas al salir
del trabajo o del alcance. Los sonidos ya enviados terminan su breve reproducción natural.
Recargar/desactivar cancela tareas y vacía sesiones/cachés. Los demás sistemas conservan
su planificación original; esta mejora no elimina el coste preexistente de sus rutinas.

## Compilación y verificación

Con Java 21 y Maven: `mvn clean verify`. Las partituras están en `src/main/resources/music`.
El ZIP incluye código, pruebas y documentación; el JAR no incluye dependencias del servidor.
Las pruebas automatizadas comprueban el reloj compartido, reproducción individual, silencio
fuera del trabajo/alcance y lectura de las tres partituras. La escucha real y una prueba de
carga con cientos de jugadores requieren un servidor Paper/Purpur; no se afirma haberlas realizado.
