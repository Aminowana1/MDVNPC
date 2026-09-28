# MDVNPC 1.2.0

NPC normales y vendedores, skins persistentes, editor de intercambios y rutinas diarias por mundo.

Base: MDVNPC-1.1.2-source.zip entregado por el usuario. Requiere Java 21 y Paper/Purpur 1.21.6; LibsDisguises 11.0.18 y PacketEvents 2.14.0. MMOItems y WorldGuard opcionales.

## Rutinas

Ver [guía completa y comandos](GUIA-RUTINAS.md). Incluye dormir en camas, caminar en modos meta/aleatorio/ciclo, sentarse en stairs con consumo cosmético y trabajar en un puesto con acceso a tienda restringido por horario y llegada. Los NPC sin rutina conservan su funcionamiento anterior.

Las rutinas se almacenan en `routines.yml`. El reloj opcional administra día y noche por mundo con recuperación del valor anterior de `doDaylightCycle` en `clock-state.yml`. No se fuerzan chunks. La navegación y la caché tienen límites configurables.

## Tiendas y skins

Se conservan los comandos anteriores, la selección normal/shop, las skins persistentes y las correcciones del editor de 1.1.2. Ver [tiendas](README_SHOP.md) y [documentación de la base](docs/README-1.1.2.md). La documentación 1.2.0 tiene prioridad en el comportamiento de NPC con rutina.

## Fuente y validación

Esta entrega es solo fuente, por petición del usuario. No incluye JAR ni carpetas target. No se volvió a compilar después de esa petición. Las comprobaciones realizadas antes y sus límites se detallan en [AUDITORIA.md](AUDITORIA.md).

El workflow `.github/workflows/build.yml` se conserva sin cambios. En GitHub compila con Java 21, ejecuta `mvn clean verify` y publica `target/MDVNPC-*.jar`, sin editar el workflow por versión.

Antes de actualizar, apagar el servidor y respaldar `plugins/MDVNPC/`. Conservar los archivos de configuración, tiendas y skins. Las opciones nuevas usan valores predeterminados sin reemplazar la configuración existente.