# Saludo a los NPC

Un jugador saluda a un NPC agachándose dos veces rápido (dos pulsaciones de Shift) a menos de 5 bloques. El NPC más cercano que lo ve:

1. Se gira y lo mira.
2. Saluda con la mano: se agacha y mueve el brazo dos veces, como el gesto `wave` de los acompañantes.
3. Si estaba caminando, se detiene unos segundos y después sigue su rutina.

No envía ningún mensaje al chat.

## Cuándo no responde

- Está durmiendo: no se levanta para saludar.
- Está bebiendo una cerveza ofrecida o reaccionando a un golpe.
- Una pared lo tapa, o el jugador está oculto para los NPC.
- Ya saludó a ese mismo jugador hace poco (`cooldown-seconds`).

Un golpe durante el saludo lo interrumpe: el NPC pasa a reaccionar al golpe. Un NPC sentado saluda sin levantarse de la silla.

El gesto de la mano necesita la skin de jugador (LibsDisguises). Sin ella, el NPC solo se gira y se detiene. Los acompañantes no responden a este saludo.

## Configuración

En `config.yml`:

```yaml
npc-greetings:
  enabled: true
  range: 5                  # 1..16; distancia máxima entre el jugador y el NPC
  double-sneak-seconds: 0.6 # 0.1..2; tiempo máximo entre las dos veces que se agacha
  pause-seconds: 3          # 0.5..15; tiempo que el NPC se detiene mirando al jugador
  cooldown-seconds: 10      # 0..600; espera por jugador y NPC antes de volver a saludar
```

Tras editarlo, ejecutá `/mdvnpc reload`. Si el archivo no tiene esta sección, se usan estos valores.
