# MDVNPC 1.11.0

El editor de cada goal permite activar **Atender durante este goal**: comandos y
compras siguen disponibles mientras el NPC se sienta, duerme o camina. El ajuste
se aplica a todas las variantes del goal y viene desactivado en los goals anteriores.
Consulta [la guía de esta entrega](GUIA-1.11.0.md).

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
