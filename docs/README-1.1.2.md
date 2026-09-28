# MDVNPC 1.1.2

NPC normales y vendedores con interfaz nativa de aldeano, editor paginado, diálogos, skins persistentes y soporte opcional de MMOItems.

**Base de esta corrección:** MDVNPC-1.1.1-source.zip. **Objetivo:** Paper/Purpur 1.21.6, Java 21, LibsDisguises 11.0.18 y PacketEvents 2.14.0. WorldGuard y MMOItems opcionales.

## Entrega y validación

Compilación con Maven 3.9.11 y Java 21: **44 pruebas aprobadas, sin fallos, errores ni omitidas**. El JAR está compilado y cubierto por pruebas automatizadas; no está certificado mediante juego real con clientes ni con tus versiones de MMOItems/WorldGuard. Ver [auditoría y límites](AUDITORIA.md) y [changelog](CHANGELOG.md).

## Actualización

1. Apagar el servidor y respaldar la carpeta `plugins/MDVNPC/`.
2. Sustituir el JAR por `MDVNPC-1.1.2.jar`. Conservar `npcs.yml`, `config.yml` y `shops.yml`.
3. Iniciar. `skins.yml` se crea para guardar las skins resueltas por nombre; no borrar ese archivo al actualizar.
4. Comprobar primero en una copia del servidor los casos indicados en la auditoría.

Una textura y firma explícitas en `npcs.yml` conservan prioridad. Una skin por nombre necesita resolverse una vez; luego queda congelada en la caché para reinicios y recargas. `/mdvnpc skin <id> <nombre>` fuerza su actualización. No se guardan skins vacías cuando falla Internet.

## Uso

- `/mdvnpc create herrero Steve shop &6Herrero`
- `/mdvnpc mode herrero shop`
- `/mdvnpc shop herrero`, o Shift + clic derecho como administrador: editor de ofertas.
- Fila superior: resultado; fila central: costo 1 obligatorio; tercera fila: costo 2 opcional. Cada columna es un intercambio. Fila inferior: guardar y páginas.
- Las tres filas editables funcionan como inventario: colocar mueve el objeto desde el cursor, clic lo retira y Shift lo transfiere entre inventarios. Se permite arrastrar y dividir pilas. Los botones no se pueden retirar.
- `/mdvnpc reload`: valida, guarda y recarga. Las columnas incompletas se conservan como borradores y no se muestran al comprador. Los objetos dejados en el editor quedan allí al cerrar; se retiran con clic o Shift.

Se conservan `list`, `status`, `movehere`, `delete`, `rename`, `skin`, `enable`, los comandos por interacción y diálogos de proximidad. Más opciones y ejemplos en [guía de tiendas](README_SHOP.md); documentación histórica de la base en [docs/README-original.md](docs/README-original.md), con precedencia de esta guía y de la auditoría ante diferencias.

## Configuración nueva

```yaml
look-update-interval-ticks: 10
update-interval-ticks: 10
rotation-threshold-degrees: 3.0
worldguard-spawn-bypass: true
shop-allow-cancelled-interaction: false
```

Las claves ausentes usan valores por defecto: no hace falta reemplazar tu configuración. `shop-allow-cancelled-interaction: false` respeta cancelaciones de otros plugins; establecer `true` restaura la excepción de lobby de 1.1.0. La excepción de spawn de WorldGuard sigue activada por defecto y ahora puede desactivarse. Consultar sus límites en la auditoría.

## Compilación y GitHub

```text
mvn --batch-mode --no-transfer-progress clean verify
```

Resultado: `target/MDVNPC-1.1.2.jar`. Subir el contenido de esta carpeta a la raíz del repositorio, incluyendo `.github/`. Actions publica `MDVNPC-jar` usando `target/MDVNPC-*.jar`; **solo se cambia la versión del pom.xml, no build.yml**. El workflow de la base 1.1.0 se conservó byte por byte. Las bibliotecas de prueba no se incluyen en el JAR.



