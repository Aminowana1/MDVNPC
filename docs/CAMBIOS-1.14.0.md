# MDVNPC 1.14.0 — ocultar NPC por jugador

Primer cambio pedido por el tutorial guiado de MDVTutorial (ver
`MDVTutorial/docs/DISENO-GUIA-ISOLDA.md`). Es una función genérica de la API:
MDVNPC no sabe qué es un tutorial.

## API nueva (`MdvNpcApi`)

```java
boolean hideNpcs(Plugin owner, Player viewer);   // true si antes no los tenía ocultos
boolean showNpcs(Plugin owner, Player viewer);   // true si vuelve a verlos
boolean isHidingNpcs(Player viewer);
```

- Oculta a ese jugador **todos los NPC normales** de `npcs.yml`, junto con sus
  entidades auxiliares: asiento de rutina, bote del pescador y objetos del
  herrero. El anzuelo del pescador ya solo se envía a quien ve al NPC, así que
  también queda oculto.
- Mientras están ocultos, esos NPC **no interactúan con él**: no le dicen
  diálogos, no lo miran, no reaccionan a sus golpes, no le abren tiendas y no
  oye su música ni ve sus notas.
- **Se mantiene** aunque el NPC reaparezca, cambie de chunk, cambie de mundo o
  se haga `/mdvnpc reload`.
- **Por plugin:** cada plugin pide la ocultación por su cuenta. Los NPC solo
  vuelven a verse cuando ningún plugin la mantiene.
- **Solo en memoria:** se olvida cuando el jugador se desconecta (Paper también
  olvida la visibilidad por entidad) o cuando se deshabilita el plugin que la
  pidió. Al apagarse MDVNPC se muestra todo. Quien la use debe volver a pedirla
  cuando el jugador reconecte.
- **Los acompañantes no se ven afectados:** su visibilidad depende solo de sus
  visores. La Isolda de un jugador sigue visible para él aunque oculte los NPC.
- Todo debe llamarse desde el hilo principal.

## Implementación

- `runtime/NpcVisibilityService` (nuevo): registro de entidades de NPC y
  peticiones de ocultación por `(plugin, jugador)`. Lo crea el plugin antes que
  cualquier NPC y sobrevive a los reload. Las entidades que ya no existen se
  limpian solas.
- `runtime/NpcVisibilityListener` (nuevo): limpia el estado al desconectarse el
  jugador y al deshabilitarse el plugin dueño.
- Las entidades se registran donde se crean: `NpcManager` (aldeano base),
  `RoutineVisuals` (asiento), `FishermanController` (bote) y
  `BlacksmithController` (`ItemDisplay`).
- `PlayerFilter.accepts` descarta a los jugadores ocultos. Lo usan diálogos,
  miradas, clicks, tiendas y reacciones a golpes. Se añade
  `PlayerFilter.hidden(player)`.
- `MusicService` quita a los jugadores ocultos del público de cada canción.
- `CompanionService` implementa los tres métodos nuevos de la API delegando en
  el servicio.

## Límites conocidos

- Los sonidos y partículas que se emiten **al mundo** siguen llegando a quien
  esté cerca: yunque, fragua, pasos, remos, sonido ambiental del aldeano,
  comer/beber. Son ambientales y no revelan al NPC de forma directa.
- Paper guarda las entidades ocultas por UUID hasta que el jugador se
  desconecta. Los objetos del herrero son entidades nuevas en cada golpe, así
  que un jugador oculto durante mucho tiempo junto a un herrero acumula
  entradas pequeñas. Para el caso de uso previsto (unos minutos de tutorial) es
  despreciable.
- **Pendiente de probar en el servidor:** que LibsDisguises 11.0.18 no reenvíe
  el disfraz de un NPC oculto. Paper no rastrea entidades ocultas y
  LibsDisguises refresca por el tracker, así que no debería ocurrir, pero
  MockBukkit no puede comprobarlo.

## Tests

- `NpcVisibilityServiceTest` (8 tests nuevos):
  - las entidades existentes y las que aparecen después se ocultan solo al
    jugador oculto;
  - con varios plugins dueños, se vuelven a ver solo cuando todos lo retiran;
  - `show` sin `hide` no hace nada;
  - al deshabilitarse un dueño, solo se muestran los jugadores que nadie más
    oculta;
  - al desconectarse el jugador se olvida su estado;
  - `close` muestra todo y olvida el estado;
  - se limpian las entidades que ya no existen;
  - `PlayerFilter` descarta a los jugadores ocultos.
- Suite completa: 913 tests, 0 fallos.

## Compatibilidad

- No hay cambios de config ni de datos. Los NPC, rutinas, tiendas y
  acompañantes funcionan igual mientras nadie llame a `hideNpcs`.
- `MdvNpcApi` gana tres métodos. Solo cambia algo para quien implemente la
  interfaz; quien solo la llama no tiene que tocar nada.
