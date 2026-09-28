Esta guía se complementa con [AUDITORIA.md](AUDITORIA.md) y [CHANGELOG.md](CHANGELOG.md), que detallan las correcciones 1.1.2 y tienen precedencia ante diferencias.

# MDVNPC 1.1.2 — Vendedores y trueques

Fuente Maven: **Purpur/Paper 1.21.6, Java 21, LibsDisguises 11.0.18**. El NPC anterior (incluido Thurg) mantiene por defecto `mode: normal`, su skin, diálogos, comandos y comportamiento. Las tiendas no requieren Shopkeepers: utilizan la interfaz **nativa de intercambios del aldeano** con un comerciante virtual individual para cada jugador.

## Compilación / actualización en GitHub

Subí **el contenido de `MDVNPC-main/`** a la raíz de tu repositorio, con `pom.xml` y `.github/workflows/build.yml` en sus lugares. Actions → `Compilar MDVNPC` → `Run workflow`, o simplemente hacer push. Descargá el artefacto fijo **`MDVNPC-jar`**, cuyo interior contiene `MDVNPC-1.1.2.jar`. Para versiones futuras solo cambiá `<version>` en `pom.xml`: **no vuelvas a editar `build.yml`**; detecta automáticamente `target/MDVNPC-*.jar`. En PC con Java 21 y Maven: `mvn clean verify`.

## Crear y administrar

- `/mdvnpc create herrero Steve shop &6Herrero` — crea NPC vendedor en tu ubicación.
- `/mdvnpc create guardia Steve normal &eGuardia` — crea NPC normal (la palabra `normal` es opcional).
- `/mdvnpc mode herrero shop` o `/mdvnpc mode herrero normal` — cambia un NPC existente. Los intercambios quedan guardados al volver a modo normal.
- `/mdvnpc shop herrero` — abre el editor de ofertas de cualquier NPC vendedor, incluso si el chunk no está cargado.
- **Shift + clic derecho** sobre un NPC vendedor como admin (permiso `mdvnpc.admin`) — abre su editor. El clic derecho común abre la interfaz nativa de intercambios al jugador.

## Editor: 4 filas × 9 columnas

La **columna** es un trueque. En cada página caben 9 trueques, hasta 100 páginas / 900 trueques por NPC.

| Fila | Función |
|---|---|
| 1 (slots 0..8) | Resultado: item que recibe el jugador |
| 2 (slots 9..17) | Primer costo **obligatorio**: lo que entrega |
| 3 (slots 18..26) | Segundo costo **opcional** |
| 4 | Flecha anterior en slot 29, guardar en 31, siguiente en 33 |

**Cómo agregar:** mové los objetos desde tu inventario a las tres filas superiores como en un cofre: clic, clic derecho, arrastre y Shift + clic. El objeto sale del cursor al colocarlo. Guardar, navegar o cerrar conserva las celdas. Las columnas completas se publican como ofertas y las incompletas quedan como borradores en shops.yml. Retirar un objeto actualiza esas celdas al guardar; no se devuelve ni se copia automáticamente. Los botones de navegación están protegidos.

**Importante:** haz pruebas con objetos de una única unidad y luego con cantidades y con MMOItems. No borres `shops.yml` cuando actualices el `.jar`. Si un tipo/ID MMOItems desaparece, esa oferta no se enseña a jugadores hasta que vuelva a estar disponible; en el editor se representa con una barrera informativa sin sobrescribir la referencia al guardarla sin cambios.

## MMOItems y otros ítems

Un ítem reconocido por la API instalada de MMOItems se guarda como `kind: MMOITEMS`, `type: SWORD`, `id: ESPADA_LUNAR`, `amount: 1` (ejemplo). **No se guarda una copia permanente de su ItemStack**. Cada vez que un jugador abre la tienda se vuelve a generar el item desde el tipo/ID, de manera que los cambios hechos en MMOItems se reflejan en nuevas aperturas de la tienda. No hace falta dependencia de MMOItems para compilar o para iniciar el servidor: la API se consulta de forma opcional.

Los ítems vanilla y otros ítems custom que no provengan de MMOItems se guardan como `kind: SNAPSHOT` con `ItemStack` completo mediante serialización Bukkit/Paper, incluidos nombre, lore, model data y metadatos; esos **no se actualizan por ID** si cambia su plugin de origen. Los **costos de MMOItems se comparan como stacks de la plantilla vigente en la interfaz del aldeano**: objetos individualizados con modificadores, gemas o NBT distintos pueden no coincidir con el costo mostrado. Los premios con modificaciones aleatorias de MMOItems, si se quieren aleatorizar en cada compra, precisarían otro modo de entrega; este sistema reconstruye el item base del ID.

## Diálogos después de comerciar

Configurá por cada NPC en `plugins/MDVNPC/npcs.yml`:

```yaml
npcs:
  herrero:
    mode: shop
    shop:
      trade-dialogue:
        enabled: true
        cooldown-seconds: 20
        random: true
        lines:
          - '&6{npc} &f» &7¡Que disfrutes de tu compra, &e{player}&7!'
          - '&6{npc} &f» &7Vuelve cuando necesites equiparte.'
```

Agregá solo el bloque `mode` y `shop` dentro del NPC ya existente; no sustituyas su ubicación, skin y demás datos. `enabled: false` desactiva la frase al comerciar, sin afectar diálogos por proximidad. El cooldown es independiente por **jugador y NPC**, y el mensaje se dispara tras un intercambio en la interfaz virtual, no al abrir ni al seleccionar una oferta. Se mantienen `dialogue.lines` e `interaction` de los NPC normales.

## Mirada configurable

En `config.yml`:

```yaml
update-interval-ticks: 10        # comprobación de diálogos de proximidad
look-update-interval-ticks: 5   # NPC sigue con la mirada cada 5 ticks
rotation-threshold-degrees: 3.0
```

5 ticks ≈ 0,25 s a 20 TPS. Las dos frecuencias pueden ser distintas; se usa una sola tarea compartida, con menor frecuencia base calculada por MCD. Se permite de 1 a 200 ticks. 10–20 ticks reduce trabajo con muchos NPC, 4–5 ofrece mirada más fluida.

## Actualización segura

Apagá el servidor y hacé una copia de `plugins/MDVNPC/` antes de reemplazar el JAR. Instalá `MDVNPC-1.1.2.jar`, inicia, usá `/mdvnpc mode <id> shop` en el NPC que quieras convertir, editá trueques con Shift + clic derecho y testeá un intercambio. Revisá la consola y confirma en `shops.yml` que los MMOItems se guardaron con su tipo e ID. Mantené LibsDisguises, PacketEvents y WorldGuard con sus versiones existentes.

**Validación actualizada:** compilación limpia y pruebas automatizadas; resultado exacto en el log entregado. Consultar `AUDITORIA.md` para los límites de MockBukkit, compatibilidad y pruebas pendientes en servidor real.

