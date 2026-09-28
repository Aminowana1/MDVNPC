# MDVNPC 1.3.1

NPC normales y vendedores, skins persistentes, editor de intercambios y rutinas diarias por mundo.

Base de esta actualización: MDVNPC-1.3.0-source-fixed.zip entregado por el usuario. Requiere Java 21 y Paper/Purpur 1.21.6; LibsDisguises 11.0.18 y PacketEvents 2.14.0. MMOItems y WorldGuard opcionales.

Novedades, configuración y límites de esta entrega: [CAMBIOS-1.3.1.md](CAMBIOS-1.3.1.md). Entrega solo fuente: no se compiló ni se ejecutaron pruebas en esta revisión.

## Rutinas

Ver [guía original](GUIA-RUTINAS.md) y [novedades 1.3](GUIA-RUTINAS-1.3.md). Incluye dormir en camas, caminar en modos meta/aleatorio/ciclo, sentarse en stairs con consumo cosmético y trabajar en un puesto con acceso a tienda restringido por horario y llegada. Los NPC sin rutina conservan su funcionamiento anterior.

Las rutinas y los demás datos de cada NPC se almacenan en `NPCs/<id>/` (`npc.yml`, `routines.yml`, `shop.yml` y `skin-cache.yml`). El reloj opcional administra día y noche por mundo con recuperación del valor anterior de `doDaylightCycle` en `clock-state.yml`. No se fuerzan chunks. La navegación y la caché tienen límites configurables.

## Tiendas y skins

Se conservan los comandos anteriores, la selección normal/shop, las skins persistentes y las correcciones del editor de 1.1.2. Ver [tiendas](README_SHOP.md), la [guía de rutinas 1.3](GUIA-RUTINAS-1.3.md) y la [documentación de la base](docs/README-1.1.2.md).

## Fuente y validación

El proyecto está preparado para Java 21 y Maven. El workflow de GitHub ejecuta las pruebas y genera el JAR; las comprobaciones y límites de esta revisión se detallan en [AUDITORIA.md](AUDITORIA.md).

El workflow `.github/workflows/build.yml` se conserva sin cambios. En GitHub compila con Java 21, ejecuta `mvn clean verify` y publica `target/MDVNPC-*.jar`, sin editar el workflow por versión.

Antes de actualizar, apagar el servidor y respaldar `plugins/MDVNPC/`. La primera carga de 1.3.0 importa automáticamente los archivos globales antiguos a `NPCs/<id>/` y conserva copias `*.legacy-backup`.