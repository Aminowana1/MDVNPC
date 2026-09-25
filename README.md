# MDVNPC 1.0.0

NPC estáticos para MDVCRAFT: skins de jugador con LibsDisguises, mirada, mensajes por proximidad, comandos al hacer clic y persistencia YAML. Proyecto Maven con Java 21, preparado para Purpur 1.21.6 y LibsDisguises 11.0.18.

## Compilar en GitHub

1. Crea un repositorio y sube **el contenido de esta carpeta**, incluyendo `.github/workflows/build.yml`. `pom.xml` debe quedar en la raíz del repositorio.
2. Abre **Actions → Compilar MDVNPC**. El workflow corre con cada push; también permite **Run workflow**.
3. Cuando termine, descarga el artefacto **MDVNPC-1.0.0**. Descomprímelo y usa `MDVNPC-1.0.0.jar`.

Compilación local con JDK 21 y Maven 3.9 o superior:

```shell
mvn --batch-mode --no-transfer-progress clean verify
```

El JAR aparece en `target/MDVNPC-1.0.0.jar`. Las dependencias de servidor usan `provided`: no se empaquetan Paper, LibsDisguises ni PacketEvents dentro de MDVNPC. La API de PacketEvents se declara para compilar las firmas de LibsDisguises; MDVNPC no registra listeners de paquetes propios.

## Instalación y Thurg

1. Apaga el servidor. Conserva una copia de la carpeta de Citizens.
2. Retira Citizens si ningún otro plugin lo necesita; si lo conservas, elimina o desactiva su Thurg para no ver dos NPC superpuestos.
3. Copia `MDVNPC-1.0.0.jar` a `plugins/`. Conserva LibsDisguises 11.0.18 y la versión de PacketEvents compatible con tu instalación de LibsDisguises. MDVQuest debe estar instalado para que funcione el comando de Thurg.
4. Inicia el servidor normalmente con Java 21. No uses gestores de carga en caliente para instalar el JAR.
5. Acércate a **-12.5, 205, -90.5**, en el mundo con UUID **eafd8793-7fca-495e-9950-68b276c0f21f**.

Thurg viene activado con nombre `&2Thurg`, skin firmada original de `HAL0_W1ZARD`, sus diez frases, mirada a 6 bloques y clic derecho que ejecuta desde consola `mdvquest npc <p>` cada 2 segundos como máximo por jugador. Habla al jugador cercano tras 2 segundos y luego cada 40 segundos. Son pausas explícitas de MDVNPC, editables; no se depende de las unidades internas de Citizens.

Si lo pruebas en otro mundo, ejecuta **`/mdvnpc movehere thurg`** allí. El UUID configurado tiene prioridad y el plugin no sustituye silenciosamente un mundo por otro. Si el mundo o el chunk no están cargados, el NPC queda pendiente hasta su carga.

## Archivos editables

- `plugins/MDVNPC/config.yml`: frecuencia compartida, filtro de jugadores y mensajes administrativos.
- `plugins/MDVNPC/npcs.yml`: todos los NPC, posiciones, skins, nombres, mirada, frases y comandos.
- `npcs.yml.bak`: copia anterior, generada al editar mediante comandos.

Los archivos iniciales solo se copian cuando faltan. Reiniciar o actualizar el JAR no repone NPC eliminados ni sobrescribe cambios. Un YAML inválido durante `/mdvnpc reload` se rechaza antes de retirar los NPC activos. Los comandos de edición validan el archivo antes de guardarlo. Edita los YAML sin ejecutar comandos administrativos simultáneamente.

## Administración

Permiso: **`mdvnpc.admin`**, por defecto para OP. Los jugadores normales pueden interactuar sin permiso especial, salvo que definas `interaction.permission`.

| Comando | Uso |
| --- | --- |
| `/mdvnpc create <id> <skin> [nombre]` | Crear en tu posición; admite colores `&`. |
| `/mdvnpc movehere <id>` | Mover a tu posición y guardar mundo/rotación. |
| `/mdvnpc rename <id> <nombre>` | Cambiar el nombre visible. |
| `/mdvnpc skin <id> <jugador>` | Usar skin por nombre y quitar la textura fija anterior. |
| `/mdvnpc enable <id> <true\|false>` | Activar o desactivar. |
| `/mdvnpc delete <id>` | Eliminar de forma persistente. |
| `/mdvnpc list` | Ver los IDs. |
| `/mdvnpc status` | Ver configurados, activos y frecuencia. |
| `/mdvnpc reload` | Aplicar ambos archivos YAML. |

Los NPC creados con el comando traen un saludo y no ejecutan comandos hasta que los configures.

## Comandos por clic

Dentro de un NPC:

```yaml
interaction:
  range: 6.0
  cooldown-seconds: 2.0
  require-line-of-sight: true
  permission: ''
  commands:
    - click: RIGHT
      executor: CONSOLE
      command: 'mdvquest npc <p>'
    - click: LEFT
      executor: PLAYER
      command: 'quest'
```

`click`: `RIGHT`, `LEFT` o `BOTH`. Las acciones del mismo clic se ejecutan en orden. La pausa se comparte entre ambos botones para ese NPC y jugador. La mano secundaria se ignora y eventos repetidos del mismo clic se deduplican durante 150 ms, incluso si la pausa configurada es cero.

`CONSOLE` ejecuta con permisos de consola. `PLAYER` ejecuta con los permisos reales del jugador; no lo convierte en OP. Los comandos deben existir en el servidor y aceptar el tipo de ejecutor seleccionado. No se dispara ninguna acción solamente por acercarse: la proximidad controla los diálogos.

Variables en frases y comandos: `<p>`, `<player>` y `{player}` son el nombre real; `{uuid}` es el UUID del jugador; `{npc}` es el nombre del NPC; `{npc_id}` es su ID. No requiere PlaceholderAPI.

## Mirada, diálogos y skin

- `look.enabled/range`: mirar al jugador válido más cercano. Una rotación es compartida por todos los espectadores.
- `look.require-line-of-sight`: evitar mirar a través de paredes.
- `look.reset-when-alone`: recuperar la orientación guardada al quedarse solo.
- `dialogue.enabled/range`: activar mensajes privados a cada jugador cercano.
- `dialogue.initial-delay-seconds/interval-seconds`: espera inicial y pausa entre frases, por jugador.
- `dialogue.random`: aleatorio si es `true`; orden de la lista si es `false`.
- `dialogue.require-line-of-sight`: exigir visibilidad para hablar.
- `skin.name`: nombre de cuenta cuya skin resolverá LibsDisguises si no hay textura fija. Requiere acceso a los servicios correspondientes para una skin no almacenada en caché.
- `skin.texture/signature/uuid`: perfil firmado fijo. Textura y firma deben establecerse juntas. Thurg ya las incluye.
- `name-visible`: mostrar u ocultar el nombre.

Para añadir otro NPC puedes duplicar la sección `thurg` con otro ID o usar `/mdvnpc create`. Para no tener ninguno usa `npcs: {}`.

## Rendimiento y persistencia

Una sola tarea compartida revisa mirada y diálogos cada **10 ticks** por defecto, aproximadamente dos veces por segundo a 20 TPS. Consulta jugadores cercanos en los NPC activos y solo cambia rotación si supera el umbral configurado. Los clics funcionan por eventos. No hay escrituras periódicas, búsqueda de rutas ni tickets para mantener chunks cargados.

La entidad base es un aldeano adulto sin IA, gravedad, colisión, sonido, recolección de objetos ni comercio. Daño, movimientos, teletransportes, transformaciones y entrada a vehículos se bloquean. LibsDisguises muestra su apariencia de jugador. La definición se guarda en YAML; las entidades son temporales y se retiran al descargar el chunk o apagar MDVNPC, y se recrean al cargarlo. La marca persistente `mdvnpc:npc-id` permite limpiar restos propios sin tocar entidades de otros plugins.

Hay coste de la entidad base y de LibsDisguises: no se promete consumo cero ni un porcentaje de ahorro sin medir con Spark en el servidor real. No se implementan rutas, combate, inventarios ni IA de NPC.

## Organización

```text
com.mdvcraft.mdvnpc
  MdvNpcPlugin              inicio y recarga
  command/NpcCommand       administración
  config/                  lectura y validación
  model/NpcDefinition      datos inmutables
  storage/NpcRepository    persistencia y respaldo
  skin/DisguiseService     integración LibsDisguises
  runtime/                 ciclo de vida, mirada, diálogos, clics y filtros
  listener/NpcListener     eventos y protección
  util/                    mensajes y variables
```

## Comprobación en tu servidor

Después de instalar: comprueba la skin desde un cliente, mirada, saludo, clic derecho y pausa; reinicia y vuelve al NPC; aléjate lo suficiente para descargar su zona y regresa. Repite con dos jugadores y verifica que cada uno abre su propio menú. Para comparar rendimiento, toma perfiles de Spark con la misma cantidad de jugadores y la misma ubicación. Las pruebas automáticas no sustituyen esta comprobación visual y de integración con tu conjunto de plugins.

Referencias de integración: [API de LibsDisguises](https://libraryaddict.github.io/LibsDisguises/javadoc/me/libraryaddict/disguise/disguisetypes/PlayerDisguise.html), [PacketEvents para Maven](https://docs.packetevents.com/introduction/development-setup/), [evento de ataque de Paper 1.21.6](https://jd.papermc.io/paper/1.21.6/io/papermc/paper/event/player/PrePlayerAttackEntityEvent.html).
