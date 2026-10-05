# MDVNPC 1.3.0 — editor de rutinas y diálogos por goal

## Editor gráfico

Usa cualquiera de estos comandos:

```text
/mdvnpc routine herrero
/mdvnpc rutina herrero
/mdvnpc rutinas herrero
```

Se abre un inventario con todos los goals. Al pulsar uno puedes cambiar:

- horario de inicio y fin;
- puntos de recorrido, puesto, cama o sillas;
- velocidad de movimiento;
- modo `META`, `ALEATORIO` o `CICLO` cuando el goal es `CAMINAR`;
- radio del modo aleatorio;
- diálogos exclusivos de ese goal;
- eliminar el goal.

`Agregar goal` permite crear rápidamente dormir, caminar META, caminar ALEATORIO, caminar CICLO, sentarse o trabajo. Los valores de texto se solicitan por chat. `cancelar` cancela una entrada.

Al reasignar puntos, la selección nueva empieza en 0. La configuración anterior continúa en disco hasta que terminas y confirmas la nueva selección, evitando dejar al NPC con un goal inválido si cancelas.

## Diálogos por goal

Cada goal puede tener su propia configuración:

```yaml
dialogue:
  enabled: true
  range: 6.0
  interval-seconds: 40.0
  initial-delay-seconds: 2.0
  random: true
  require-line-of-sight: false
  lines:
    - '&7{npc} &f» &7Buenos días, &e{player}&7.'
```

Variables disponibles: `{npc}`, `{npc_id}`, `{player}`, `{uuid}`, `<player>` y `<p>`.

Para conservar compatibilidad, un goal `WORK` antiguo que todavía no tenga bloque `dialogue` hereda el diálogo global del NPC. En cuanto editas sus diálogos desde el menú, pasa a utilizar su configuración propia. Los demás goals antiguos empiezan sin diálogo hasta que se configure uno.

## Clic fuera del trabajo

Si un NPC con rutina no está actualmente en su puesto de `WORK`, un clic derecho no abre la tienda ni ejecuta sus comandos. En su lugar responde con un mensaje configurable en `npc.yml`:

```yaml
interaction:
  unavailable:
    cooldown-seconds: 3.0
    random: true
    lines:
      - '&7{npc} &f» &7Ahora mismo no estoy trabajando. Vuelve durante mi horario.'
```

El cooldown es por NPC y jugador para evitar spam.

## Archivos por NPC

Desde 1.3.0 la estructura es:

```text
plugins/MDVNPC/
├─ config.yml
├─ clocks.yml
├─ clock-state.yml
└─ NPCs/
   └─ herrero/
      ├─ npc.yml
      ├─ routines.yml
      ├─ shop.yml
      └─ skin-cache.yml
```

Al iniciar 1.3.0 por primera vez, `npcs.yml`, `routines.yml`, `shops.yml` y `skins.yml` antiguos se importan automáticamente. Después de una migración correcta se conservan como `*.legacy-backup` en la raíz para poder recuperar la configuración si fuera necesario.

Los comandos antiguos para rutinas continúan funcionando.
