# MDVNPC 1.4.0 — rasgos y sonidos

Base: ZIP 1.3.1 del usuario. Entrega exclusivamente fuente, sin compilación ni ejecución de pruebas Java o servidor. Los informes de versiones anteriores no certifican esta entrega.

## Comandos

Requieren `mdvnpc.admin`. El ID distingue mayúsculas y minúsculas; los ID existentes son minúsculos.

```text
/mdvnpc rasgo manolito alcoholico
/mdvnpc rasgo manolito lector
/mdvnpc rasgo manolito gloton
/mdvnpc rasgo manolito inquieto
/mdvnpc rasgo manolito ruidoso
/mdvnpc rasgo manolito ninguno
/mdvnpc rasgo manolito
```

Cada asignación sustituye el rasgo anterior. El último comando consulta el actual. `trait` es alias de `rasgo`; se aceptan nombres en inglés y acentos en español. Los NPC antiguos empiezan sin rasgo y conservan sus rutinas.

## Comportamiento

| Rasgo | Efecto |
| --- | --- |
| Alcohólico | Acepta una cerveza MMOItems tirada por un jugador a hasta 2.5 bloques, pausa la caminata si la había y bebe durante unos 3.2 segundos. Luego eructa y responde al jugador con una línea aleatoria de su catálogo. |
| Lector | Al elegir una actividad sentado, tiene al menos 75% de probabilidad de leer, o el doble de la probabilidad configurada hasta 100%. Cada lectura dura exactamente el triple del tiempo normal elegido. |
| Glotón | Reduce a un cuarto la espera entre actividades sentado. Con lectura y consumo habilitados, lee un 2% de las veces; del resto, come un 95% y bebe un 5%. |
| Inquieto | Divide por 3.5 el intervalo de miradas ocasionales al caminar/sentarse, acorta las miradas y a veces balancea un brazo al iniciarlas. No mueve los brazos mientras lee. |
| Ruidoso | Voz de diálogo más fuerte: volumen predeterminado 1.0 frente a 0.3 de los demás. |

Los interruptores globales `seated-reading`, `seated-consumption` y `occasional-looking` siguen teniendo prioridad. Los rasgos lector/glotón modifican las actividades de sentarse; no crean una rutina nueva. Las miradas del inquieto corresponden a las miradas ocasionales de las rutinas. Los NPC quietos en su puesto conservan el seguimiento de jugadores habitual.

Todos los NPC que caminan con una rutina emiten pasos por distancia recorrida (aproximadamente cada 0.85 bloques), usando el sonido del bloque bajo sus pies. Las recolocaciones en camas/sillas y recuperaciones no producen pasos. La voz se aplica a diálogos de proximidad, goals, tienda, respuesta fuera de horario y cerveza. No intercepta mensajes de otros plugins ni comandos de chat externos ejecutados por las interacciones.

## Cerveza y diálogos personalizados

Al asignar el rasgo se crea `npcs.manolito.trait` dentro de `plugins/MDVNPC/NPCs/manolito/npc.yml`. Edita esa sección dentro del NPC existente, conservando los demás campos:

```yaml
npcs:
  manolito:
    trait:
      type: alcoholic
      beer-cooldown-seconds: 20
      beer-dialogues:
        - '&6{npc} &f» &e¡A tu salud, {player}!'
        - '&6{npc} &f» &e¡Esta sí que está buena!'
```

Después de editar: `/mdvnpc reload`. Cooldown válido: 1 a 86400 segundos, por NPC, entre aceptaciones; además nunca inicia otra bebida mientras la anterior está activa. Se conserva durante recargas y descargas de chunks dentro de la sesión, pero se reinicia al reiniciar el servidor. `beer-dialogues: []` desactiva el texto de agradecimiento, sin quitar bebida ni eructo. Máximo 128 líneas.

La identidad se toma de las opciones existentes de `config.yml`:

```yaml
routines:
  beer-type: CONSUMABLE
  beer-id: CERVEZA
```

Modifica esas claves dentro de la sección existente; no dupliques `routines`. Se necesita MMOItems habilitado. Se compara categoría e ID; una poción renombrada no sirve. La bebida ofrecida consume realmente una unidad; la comida/bebida espontánea de las sillas sigue siendo cosmética. La animación no ejecuta efectos, habilidades o recompensas del consumible MMOItems.

Solo acepta objetos con un jugador lanzador identificable, conectado, vivo y a hasta 8 bloques. Ignora objetos protegidos contra recogida, con propietario ajeno, detrás de obstáculos, de dispensadores o sin lanzador. El NPC no recoge mientras duerme ni durante un goal de dormir, incluso mientras va hacia la cama. No obliga a cargar chunks ni camina hasta objetos lejanos. Si hay varios NPC cerca, cada uno puede tomar su propia unidad del montón; no se garantiza prioridad por proximidad.

La comprobación ocurre cada 10 ticks aproximadamente; no espera el retardo de recogida normal del objeto recién tirado. Durante el cooldown la cerveza queda en el suelo. Si se interrumpe una bebida por cambio de goal, recarga o retirada del NPC, restaura la mano y no devuelve una unidad ya consumida; puede no llegar a emitir el eructo/diálogo. Esta política evita reembolsos duplicados. No hay garantía transaccional frente a una caída abrupta del servidor.

## Volumen

Opciones nuevas de `config.yml`, con valores predeterminados también para configuraciones antiguas:

```yaml
npc-sounds:
  voice-enabled: true
  voice-volume: 0.3
  noisy-volume: 1.0
  steps-enabled: true
  step-volume: 0.25
```

Una sola voz por NPC cada 0.6 segundos como máximo evita superposición al enviar el mismo diálogo a varios jugadores. Se usa sonido de aldeano, no voz sintetizada ni lectura del texto. Sonidos locales: los jugadores cercanos pueden oírlos. Los sonidos existentes de comer y beber siguen independientes de estos interruptores.

## Implementación, rendimiento y revisión

- Enum único, persistente, con validación; clases separadas en `trait/`.
- Reutiliza la tarea de rutinas: ninguna tarea nueva por NPC y ninguna escritura de disco por gesto, paso o bebida. El comando de asignación sí guarda y recarga los NPC, igual que otros comandos administrativos.
- Búsquedas locales de cerveza cada 10 ticks, solo para alcohólicos disponibles con jugadores cerca; hasta 64 entidades inspeccionadas por búsqueda. Una zona saturada de objetos puede retrasar la aceptación. La consulta local de Bukkit puede devolver más entidades que ese límite; no es un límite global de entidades del servidor.
- El débito ocurre en el hilo principal, tras `EntityPickupItemEvent`. Respeta cancelaciones y vuelve a comprobar entidad, rutina, propietario y stack después de que otros plugins procesen el evento.
- Reserva por NPC contra reentrada, una unidad por aceptación y restauración de la mano al terminar/interrumpirse. No introduce objetos cosméticos en inventarios.
- Maven y workflow de GitHub conservados; `build.yml` no se modificó. Versión declarada en `pom.xml`: 1.4.0.
- Se revisaron las rutas de integración, YAML/XML y el contenido del ZIP. Se añadieron fuentes de pruebas para políticas de rasgos, compatibilidad de configuración y cancelación/cambio de stack/cooldown de ofertas; **no se ejecutaron** por la instrucción de no compilar.
- No se midieron RAM, CPU, TPS ni paquetes con esta versión. El aumento esperado es pequeño con 20–25 NPC, pero no hay una cifra de memoria medida que pueda garantizarse.

## Prueba pendiente en servidor

Probar cerveza individual y montón, dos NPC próximos, spam durante cooldown, recogida cancelada por otro plugin, objeto con dueño ajeno, MMOItems desactivado, dormir, cambios de horario, recarga durante bebida y descarga de chunk. Verificar visualmente mano/animación/eructo, lectura triple, gestos, pasos sobre distintos suelos y voces con varios oyentes. Confirmar también tienda y skins con la combinación real de plugins. La animación del brazo y la bebida dependen de la transmisión de LibsDisguises/PacketEvents al cliente; no fueron verificadas en juego.
