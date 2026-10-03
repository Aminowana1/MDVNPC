# MDVNPC 1.7.0 — Opciones por horario y baile

Incluye los músicos y las tres canciones de 1.6.0. Usa Java 21, Paper/Purpur 1.21.6 y las mismas
dependencias de LibsDisguises/PacketEvents. Sustituye el JAR con el servidor apagado y una copia
de respaldo de `plugins/MDVNPC/`. Las rutinas anteriores siguen siendo acciones fijas.

## Crear alternativas desde el menú

1. Abre `/mdvnpc routine <id>` con permiso `mdvnpc.admin`.
2. Abre el goal que quieras variar, por ejemplo dormir de 22:00 a 07:00.
3. Pulsa el cofre **Opciones del horario** y después **Añadir opción**.
4. Elige **Sentarse** y marca las sillas del bar, o **Caminar aleatorio/ciclo** y marca su paseo.
   Cada selección muestra las instrucciones: clic izquierdo añade puntos y derecho guarda;
   una cama o un puesto de trabajo se guarda con un clic.
5. La alternativa hereda las horas de la principal. Al añadirla se activa **Selección ALEATORIA**.

Al empezar ese horario, el NPC sortea una de las opciones con igual probabilidad: dormir,
ir al bar o pasear, por ejemplo. Conserva esa actividad durante el horario y el sorteo se
repite al día siguiente. Alejarse o recargar el chunk conserva el resultado mientras siga
la misma sesión del plugin; recargar/reiniciar el plugin reinicia las elecciones en memoria.

**Selección FIJA** utiliza siempre la opción principal y conserva las alternativas para
activarlas luego. Pulsa el icono de una opción para editar puntos, velocidad, diálogos o
actividad; su botón de eliminar borra esa alternativa. El horario se edita en la principal
y se aplica a todas. Borrar la principal borra el goal completo. Máximo 16 opciones por goal.

Funciona con dormir, sentarse, trabajo y paseos. Los recorridos **META** son preparativos sin
horario propio: sus alternativas son otros recorridos META, para conservar la secuencia
antes del siguiente horario. Los destinos de todas las opciones deben estar en el mundo del NPC.

## Baile junto a músicos

Los NPC cuyo goal elegido sea **Sentarse** pueden levantarse cuando hay un músico trabajando
dentro del radio de audio. Reservan su silla y buscan una posición segura dentro de 3 bloques
del músico. Bailan con pasos cortos, giros, saltos y agacharse durante 20–40 segundos, luego
vuelven a sentarse. Esperan 30 segundos antes de otro baile.

El baile termina si el músico deja de trabajar, acaba el horario, desaparece la audiencia,
se descarga la zona o una reacción/interacción interrumpe al NPC. Si el camino resulta
inaccesible, vuelve a intentar sentarse. Los dormitorios no se interrumpen para bailar.

La configuración predeterminada activa esta función. Para desactivarla usa `routines.dancing: false`.
La asignación de instrumentos sigue siendo:

```text
/mdvnpc trabajo flautista musico flauta
/mdvnpc trabajo guitarrista musico guitarra
```

Los músicos necesitan una rutina de trabajo y llegar a su puesto. Las instrucciones
completas se conservan en `MUSICOS.md`.

## Consumo y navegación

Paper calcula las rutas mediante su API `Pathfinder.findPath`. El plugin sigue sus puntos,
incluidas las diagonales, con movimiento controlado y comprobación de obstáculos/puertas.
Así mantiene apagada la IA autónoma del aldeano y conserva los horarios y las poses.
No ejecuta el buscador A* propio durante las rutinas.

- Un único planificador de rutinas y un único reloj musical; ninguna tarea por bailarín.
- Hasta 2 consultas nuevas de ruta por actualización, compartidas entre todos los NPC.
- Rutas calculadas una vez por viaje, con continuación si Paper devuelve una ruta parcial.
- Fallos reintentados después de 5 segundos; no recalcula rutas cada tick.
- Búsqueda nativa local con alcance 16 y comprobación de los chunks de su región antes de buscar.
- NPC quietos actualizados cada 8 ticks; sin jugadores cerca se suspenden y revisan presencia
  cada segundo. Se vigilan los cambios de horario incluso en estado pasivo.
- Ventanas horarias y elecciones guardadas en memoria; músicos/bailarines usan consultas locales.

Estas medidas reducen el trabajo para una población como 25 NPC. El coste depende de las
rutas, obstáculos, jugadores y hardware; no se ha medido el consumo en un servidor real.

Para un servidor existente puedes añadir o ajustar dentro de su sección `routines`:

```yaml
routines:
  movement-interval-ticks: 2
  passive-update-ticks: 8
  path-starts-per-update: 2
  activation-range: 48
  dancing: true
  dance-min-seconds: 20
  dance-max-seconds: 40
```

Los nuevos valores tienen estos predeterminados aunque falten en tu archivo. No dupliques
la sección `routines`. Aplica con `/mdvnpc reload`. Las antiguas claves `max-search-nodes`,
`search-nodes-per-tick` y `cached-routes` ya no se utilizan.

## Verificación

El proyecto incluye pruebas del menú, lectura/guardado de opciones, un sorteo por horario,
límites compartidos para 25 NPC, rutas diagonales/partiales, rechazo de chunks descargados,
silencio musical fuera del radio y ciclo de asiento/baile. Compilar con Java 21 y `mvn clean verify`.
La animación, navegación física y rendimiento real requieren una prueba en Paper/Purpur.
