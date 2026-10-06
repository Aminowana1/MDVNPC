# MDVNPC 1.11.15

Corrige el fallo de arranque de 1.11.14 cuando Java no puede proporcionar el
algoritmo `L32X64MixRandom`. El pescador usa ahora `java.util.Random`, disponible
en el módulo básico de Java. La elección de puntos sigue siendo aleatoria y
se conserva la configuración existente. Consulta [la guía](GUIA-1.11.15.md).

## Actualización 1.11.14

Las tiendas admiten la categoría **Pescador**. Durante Trabajo el NPC pesca
desde un punto de tierra elegido al azar, camina al muelle, sale en un bote
nativo hasta un punto de agua, pesca allí y regresa para bajar y repetir.
Los puntos guardan la dirección de la mirada al marcarlos y permiten varias
opciones de tierra y de bote. Conserva la tienda y usa su puesto normal si
no puede ejecutar la animación. Configúralo con `/mdvnpc pescador <id>`
después de fijar el punto base de Trabajo.
Un golpe mientras está en el bote pausa la actividad durante la reacción;
el NPC permanece montado y después continúa la pesca o navegación.

El Herrero reproduce un `clink` metálico corto únicamente junto a cada
gesto de golpe del yunque. Consulta [la guía de Pescador y sonido](GUIA-1.11.14.md).
La comprobación visual dentro del servidor continúa pendiente.

## Actualización 1.11.13

Las tiendas admiten la categoría **Herrero** con estaciones configurables de
fundición, caldero con agua y yunque. Durante su goal Trabajo el NPC recorre las
estaciones con sonidos, partículas y objetos visuales que no se pueden recoger.
Conserva sus intercambios y vuelve al puesto normal cuando la animación no puede
ejecutarse. Los menús permiten elegir Vendedor/Herrero y marcar las estaciones
desde el juego, también con `/mdvnpc herrero <id>`.
Consulta [la guía de Herrero](GUIA-1.11.13.md).

## Actualización 1.11.12

El fallback siempre inicia el impulso de salto tras el intervalo de inmovilidad,
incluso flotando, incrustado o sin un aterrizaje seguro. Usa la altura y distancia
configuradas hacia la mirada actual. Los bloques recortan el movimiento durante
el choque: una pared puede detener el avance mientras continúa la subida.
Las vallas y sus cubiertas siguen bloqueando el paso. Al terminar, vuelve la
recuperación habitual del suelo y se repite el intento si sigue sin avanzar.
Consulta [la guía](GUIA-1.11.12.md) y [la validación](VALIDACION-1.11.12.md).

## Actualización 1.11.11

Corrige la recuperación de NPC quietos que quedaba oculta tras una
recuperación de suelo bloqueada. El salto puede acortar el avance, hacerse
en el sitio o reducir su altura cuando falta espacio. También impide usar
slabs, trampillas o bloques directamente sobre una valla como suelo o
escalón, incluso si la valla está a un nivel inferior.
Consulta [la guía](GUIA-1.11.11.md) y [la validación](VALIDACION-1.11.11.md).

## Actualización 1.11.10

Las vallas ya no se pueden usar como escalón ni cruzar durante una subida
o un salto de recuperación. Durante la caminata de los goals, 3 segundos
sin movimiento real permiten intentar un salto sutil de 0.6 bloques de alto
y 1 bloque hacia la mirada del NPC. La altura, distancia e intervalo son
configurables. Dormir, estar sentado o trabajar en el puesto excluyen el salto.
Se conservan las modificaciones de la base 1.11.9 y sus límites de altura.
Consulta [la guía de esta entrega](GUIA-1.11.10.md) y
[su validación](VALIDACION-1.11.10.md).

## Actualización 1.11.8

Corrige el atasco/giro en círculos del replay de rutas cuando el suelo mezcla alturas
físicas parciales. La navegación ya no depende sólo de la Y que devuelven los nodos
de Paper: sigue la colisión real del piso, anticipa el pequeño escalón antes del choque
y tolera las diferencias entre la celda nativa y la altura real de los pies.

La corrección cubre slabs, stairs, `DIRT_PATH`, `MUD`, `SOUL_SAND`, alfombras y
otras capas finas con colisión, tanto al subir como al bajar. Los destinos se traducen
a la celda transitable que Paper espera y el editor permite seleccionar superficies
transitables por colisión aunque `Material#isSolid()` sea falso, como carpet. También
hay recuperación acotada si un NPC antiguo queda ligeramente incrustado en una
superficie parcial añadida bajo sus pies, sin atravesar bloques completos.

Todo está centralizado en `RoutineNavigator`/`RoutineTerrain`, por lo que se aplica a
WALK meta/ciclo/aleatorio, WORK, cama/asiento y retornos sin cambiar los datos de las
rutinas. Consulta [la guía de esta entrega](GUIA-1.11.8.md).

## Actualización 1.11.3

El pathfinding de rutinas ahora amplía la búsqueda de Paper sólo cuando hace falta
(16 → 24 → 32), acepta rodeos que temporalmente se alejan del objetivo y detecta
atascos reales antes de recalcular. La ejecución de waypoints distingue paredes
laterales de escalones, desliza esquinas diagonales estrechas y conserva soporte
correcto sobre stairs, slabs, alfombras y desniveles de hasta un bloque. Dormir y
sentarse pueden probar otro lado del mueble únicamente después de agotar la ruta
del acceso actual. Los viajes largos ya no vencen mientras el NPC siga avanzando.
Consulta [la guía de esta entrega](GUIA-1.11.3.md).

## Actualización 1.11.2

La caminata usa la forma real de escaleras y losas y se acerca al escalón antes de
elevar al NPC. Las rutinas normales pueden subir bloques; el baile mantiene su
pista al mismo nivel. Las puertas de madera se cierran después del paso, aunque
ya estuvieran abiertas, sin quedar retenidas por un NPC que está al lado.
Las sillas comprueban y recuperan su pasajero y refrescan el montaje al volver
los observadores. Consulta [la guía de esa entrega](GUIA-1.11.2.md).

## Actualización 1.11.1

Se restauran los nombres nativos de LibsDisguises de la versión 1.9: sin rótulos
independientes que persigan al NPC ni offsets de nombre al sentarse o dormir.
La recuperación de rutinas evita saltos frente a observadores y las poses validan
la proximidad antes de sentarse, acostarse o regresar a su punto de salida.
Consulta [la guía de esta entrega](GUIA-1.11.1.md).

## Actualización 1.11.0

El editor de cada goal permite activar **Atender durante este goal**: comandos y
compras siguen disponibles mientras el NPC se sienta, duerme o camina. El ajuste
se aplica a todas las variantes del goal y viene desactivado en los goals anteriores.
Consulta [la guía de atención por goal](GUIA-1.11.0.md).

## Actualización 1.10.1

Los nombres de NPC evitan el rótulo de armor stand cuando LD usa ese modo y mantienen
sus offsets al sentarse/dormir. Los asientos refuerzan su invisibilidad y limpieza.
Se añaden «La Vela del Mesón» (30s) y «Jiga del Puerto» (32s), con ambas partes completas.
Los músicos desplazados durante Trabajo regresan al mismo puesto y vuelven a tocar.
`/mdvnpc status <id>` permite consultar la canción y posibles pausas de un músico.
Consulta [la guía de esta entrega](GUIA-1.10.1.md).

## Actualización 1.10.0

El baile usa una pista plana con separación entre participantes y desplazamientos continuos,
sin subir a mesas o escalones. Los NPC Fiestero alternan 60 segundos de baile y 30 segundos
sentados; el descanso comienza después de volver a la silla. Se añaden ajustes globales
de altura del nombre al sentarse/acostarse y el asiento tiene un offset predeterminado de 0.5.
Consulta [la guía de esta entrega](GUIA-1.10.md). Se conservan músicos, canciones y editor.

## Actualización 1.9.0

Los músicos muestran un instrumento durante el trabajo: bambú para flauta y armadura de
caballo de hierro para guitarra. Mueven la cabeza, gesticulan al tocar y emiten notas
ascendentes para jugadores cercanos. Las animaciones usan el reloj musical compartido y
restauran el equipo al finalizar o al interrumpirse. Consulta [la guía de esta entrega](GUIA-1.9.md).
Se conserva el editor y las seis canciones de 1.8.0; los informes anteriores son históricos.

## Actualización 1.8.0

Editor de NPC con selección de trabajo Normal/Tienda/Músico, instrumento Flauta/Guitarra y cambio
de nombre desde un yunque gráfico. Abre `/mdvnpc edit <id>`; también se accede desde Rutinas.
El baile junto a músicos queda reservado al rasgo **Fiestero** y se corrige la espera de navegación
al levantarse de una silla. Se añaden tres canciones originales de 30 segundos a las tres anteriores.
Consulta [la guía de esta entrega](GUIA-1.8.md). Java 21 y Paper/Purpur 1.21.6; compilación y pruebas
locales documentadas en `dist/VERIFICACION.txt`. Los informes siguientes son históricos.

## Historial anterior

Prefijo de diálogo por NPC desde el editor, voces en secuencias, ruido espontáneo del ruidoso, reacción a golpes y miradas configurables por contexto. [Guía y cambios de esta entrega](PERSONALIDAD-1.5.0.md). Base: MDVNPC-1.4.2-source.zip del usuario. Entrega fuente, sin compilación ni pruebas Java locales. Los informes que siguen corresponden a versiones anteriores.

Corrección de las pruebas de mirada que impedían completar GitHub Actions: [CAMBIOS-1.4.2.md](CAMBIOS-1.4.2.md). Conserva el selector gráfico y todas las funciones de 1.4.1. Entrega fuente, sin compilación local.

Selector gráfico de rasgos: `/mdvnpc routine <id>` → **Rasgo del NPC**. Permite asignar cualquiera de los cinco rasgos o quitarlo con **Sin rasgo**, guarda al seleccionar y muestra el actual. Funciona también sin goals. Ver [CAMBIOS-1.4.1.md](CAMBIOS-1.4.1.md). Entrega solo fuente, sin compilar.

NPC normales y vendedores, skins persistentes, editor de intercambios y rutinas diarias por mundo.

Base de esta actualización: MDVNPC-1.3.1-source.zip entregado por el usuario. Requiere Java 21 y Paper/Purpur 1.21.6; LibsDisguises 11.0.18 y PacketEvents 2.14.0. MMOItems y WorldGuard opcionales.

Novedades, comandos, configuración y límites de esta entrega: [RASGOS-1.4.0.md](RASGOS-1.4.0.md). Entrega solo fuente: no se compiló ni se ejecutaron pruebas Java en esta revisión. Los informes anteriores son históricos.

## Rutinas

Ver [guía original](GUIA-RUTINAS.md) y [novedades 1.3](GUIA-RUTINAS-1.3.md). Incluye dormir en camas, caminar en modos meta/aleatorio/ciclo, sentarse en stairs con consumo cosmético y trabajar en un puesto con acceso a tienda restringido por horario y llegada. Los NPC sin rutina conservan su funcionamiento anterior.

Las rutinas y los demás datos de cada NPC se almacenan en `NPCs/<id>/` (`npc.yml`, `routines.yml`, `shop.yml` y `skin-cache.yml`). El reloj opcional administra día y noche por mundo con recuperación del valor anterior de `doDaylightCycle` en `clock-state.yml`. No se fuerzan chunks. La navegación y la caché tienen límites configurables.

## Tiendas y skins

Se conservan los comandos anteriores, la selección normal/shop, las skins persistentes y las correcciones del editor de 1.1.2. Ver [tiendas](README_SHOP.md), la [guía de rutinas 1.3](GUIA-RUTINAS-1.3.md) y la [documentación de la base](docs/README-1.1.2.md).

## Fuente y validación

El proyecto está preparado para Java 21 y Maven. El workflow de GitHub ejecuta las pruebas y genera el JAR; las comprobaciones y límites de esta revisión se detallan en [AUDITORIA.md](AUDITORIA.md).

El workflow `.github/workflows/build.yml` se conserva sin cambios. En GitHub compila con Java 21, ejecuta `mvn clean verify` y publica `target/MDVNPC-*.jar`, sin editar el workflow por versión.

Antes de actualizar, apagar el servidor y respaldar `plugins/MDVNPC/`. La primera carga de 1.3.0 importa automáticamente los archivos globales antiguos a `NPCs/<id>/` y conserva copias `*.legacy-backup`.
