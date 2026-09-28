# MDVNPC 1.5.0 — prefijos, voces y reacciones

Base: ZIP 1.4.2 proporcionado por el usuario. Solo fuente, sin compilar ni ejecutar pruebas Java/servidor. El workflow de GitHub se conserva intacto y sigue ejecutando `clean verify`; no se saltan pruebas.

## Prefijo por NPC desde el editor

1. `/mdvnpc routine manolito` (también `rutina` o `rutinas`).
2. En la fila inferior, **Prefijo de los diálogos**, junto al selector de rasgos.
3. **Editar prefijo**. Escribe por chat, por ejemplo `&6[Tabernero] &e{npc} &8»`.
4. Se guarda y vuelve al menú con una vista previa. `cancelar` vuelve sin guardar; la entrada caduca a los 2 minutos.

También hay **Sin prefijo** y **Restaurar formato original**. Requiere `mdvnpc.admin`, funciona sin rutina y comprueba el permiso al guardar. Si otro administrador cambió ese prefijo mientras escribías, pide abrirlo de nuevo en lugar de sobrescribirlo silenciosamente.

Se aplica a frases de proximidad, de cada goal, compra, fuera de horario, agradecimiento por cerveza y enfado. No cambia el nombre flotante ni la skin. No modifica avisos administrativos, errores de tienda ni mensajes enviados por comandos u otros plugins.

Los NPC existentes conservan el formato original hasta asignarles un prefijo. El prefijo personalizado sustituye el encabezado estándar `{npc} »` (también con colores y separadores `:`, `>`, `-`, `–`, `—`) al principio de las frases. Las menciones de `{npc}` dentro de la frase se conservan. Si tenías nombres escritos literalmente en encabezados personalizados, deja la frase como texto de diálogo o reemplaza ese encabezado por `{prefix}`: no se intenta adivinar qué parte del texto debe borrarse.

Admite colores `&` y variables `{npc}`, `{npc_id}`, `{player}`, `<player>`, `<p>`, `{uuid}`. Máximo 256 caracteres sin saltos de línea. Se añade un espacio entre el prefijo y la frase. En líneas con `{prefix}`, se inserta allí, sin añadirlo una segunda vez.

Persistencia en `NPCs/manolito/npc.yml`, dentro del NPC existente:

```yaml
npcs:
  manolito:
    speech:
      prefix: '&6[Tabernero] &e{npc} &8»'
      # Opcional: sustituye solo las frases de enfado de ESTE NPC.
      anger-lines:
        - '&c¡Cuidado con esas manos, {player}!'
        - '&c¡En mi taberna no se pega!'
```

No reemplaces todo el archivo por el ejemplo. `prefix: ''` quita el prefijo; borrar la clave restaura el formato original. `anger-lines: []` quita solo el texto de enfado de ese NPC; omitirla usa las frases globales. Después de editar YAML: `/mdvnpc reload`.

## Voz y ruidoso

Cada diálogo inicia una secuencia breve de sonidos de aldeano con variación de tono. No es síntesis de voz ni lectura del texto. Si un NPC dice algo a varios jugadores a la vez, comparten una secuencia. Los mensajes que llegan mientras ya está hablando comparten esa voz, evitando una cola o sonidos superpuestos ilimitados.

Valores nuevos en `npc-sounds` del `config.yml`:

| Clave | Predeterminado | Función |
| --- | --- | --- |
| `voice-enabled` | true | Habilita voz normal y ruido espontáneo. |
| `voice-volume` / `noisy-volume` | 0.3 / 1.0 | Volumen al dialogar. |
| `voice-syllables` / `noisy-syllables` | 3 / 5 | Cantidad de sonidos por frase. |
| `voice-gap-ticks` | 5 | Separación entre sonidos. |
| `pitch-min` / `pitch-max` | 0.8 / 1.3 | Variación de tono. |
| `noisy-idle-enabled` | true | Ruidoso emite sonidos incluso sin diálogo. |
| `idle-min-seconds` / `idle-max-seconds` | 40 / 90 | Intervalo aleatorio del ruido espontáneo. |
| `idle-syllables` / `idle-gap-ticks` | 8 / 7 | Secuencia espontánea más larga. |
| `idle-volume` | 1.2 | Volumen del ruido espontáneo. |
| `idle-player-range` | 12 | Debe haber un jugador válido cerca. |

No inicia ruido espontáneo durante un goal de dormir, cuando está suspendido, reaccionando a un golpe o ya hablando. Si empieza a dormir, se cortan los sonidos espontáneos pendientes. Se conservan los pasos configurables. Los sonidos ya enviados al cliente pueden terminar de reproducirse después de una interrupción.

## Golpes

Todos los rasgos, incluido Sin rasgo, tienen la reacción por defecto: partículas de impacto y enfado, sonido de queja, una frase y mirada hacia el atacante. El NPC continúa siendo invulnerable, no contraataca ni pierde objetos. Es una reacción al intento de golpe directo de un jugador; no a flechas, explosiones o daño ambiental. Respeta la cancelación previa del evento de ataque por otros plugins y exige cercanía y línea de visión.

`npc-reactions` configura:

| Clave | Predeterminado | Función |
| --- | --- | --- |
| `enabled` | true | Activa la reacción. |
| `cooldown-seconds` | 5 | Cooldown por NPC, compartido entre atacantes. |
| `max-hit-distance` | 4.5 | Distancia máxima del golpe aceptado. |
| `look-seconds` | 3 | Cuánto tiempo se queda mirando. |
| `look-range` | 8 | Deja de mirar si el atacante se aleja más. |
| `angry-particles` / `impact-particles` | 5 / 7 | Cantidades; 0 desactiva cada efecto. |
| `hit-volume` | 0.6 | Volumen de la queja de impacto. |
| `lines` | Catálogo incluido | Frases aleatorias, con el prefijo individual. |

Durante la mirada pausa el desplazamiento. Si está sentado, conserva la silla. Si duerme, se levanta para reaccionar y después intenta regresar a la cama si el horario sigue vigente. Interrumpe el consumo/lectura visual; una cerveza ofrecida que ya se había consumido no se devuelve. Al desconectarse el atacante, salir del rango o perder línea de visión, termina la mirada. El spam durante el cooldown no prolonga la reacción. Se conservan las acciones de clic izquierdo ya configuradas y su control de acceso.

## Miradas completamente configurables por contexto

Dentro de `routines.looking`, hay dos secciones independientes: `walking` y `seated`. Ambas admiten las siguientes claves. El archivo `src/main/resources/config.yml` contiene todas, comentadas.

| Clave | Función |
| --- | --- |
| `enabled` | Enciende las miradas de ese contexto. |
| `interval-min-seconds`, `interval-max-seconds` | Espera aleatoria entre miradas. |
| `duration-min-seconds`, `duration-max-seconds` | Tiempo que dura cada mirada. |
| `chance` | Probabilidad de iniciar mirada al vencer la espera: 0..1. |
| `update-interval-ticks` | Intervalo del suavizado; limitado por el ticker de movimiento. |
| `yaw-min`, `yaw-max` | Ángulos aleatorios horizontales; negativo=izquierda. |
| `pitch-min`, `pitch-max` | Ángulos verticales; negativo=arriba. |
| `yaw-limit` | Giro máximo respecto del cuerpo. |
| `yaw-step`, `pitch-step` | Grados máximos que avanza la cabeza en cada actualización. |
| `reading-pitch` | Inclinación al leer, normalmente útil en seated. |
| `look-at-players` | Permite elegir jugadores en lugar de mirar al azar. |
| `player-range` | Radio real de búsqueda; 0 desactiva, máximo 16. |
| `player-chance` | Probabilidad de elegir jugador al iniciar una mirada. |
| `require-line-of-sight` | Impide mirar jugadores detrás de obstáculos. |
| `player-pitch-min`, `player-pitch-max` | Límites verticales al seguir jugadores. |

Por defecto: mirando sentado sigue jugadores a hasta **3 bloques**; caminando mira al azar. Giro máximo 65° sentado / 40° caminando. Los límites previenen giros anómalos: yaw hasta 85°, pitch entre -80° y 80°, probabilidades 0..1 y valores finitos. Rangos invertidos se ajustan al mínimo. Un ángulo negativo en pitch mira hacia arriba.

Se conserva `routines.occasional-looking` como interruptor general. En configuraciones antiguas, `glance-min-seconds` y `glance-max-seconds` siguen aplicándose hasta que añadas explícitamente los intervalos de cada contexto. Esto no modifica el seguimiento habitual en el puesto de trabajo, que sigue usando `look` y `look-update-interval-ticks`.

### Inquieto más inquieto

`routines.restless`:

- `frequency-multiplier: 6`: divide por seis la espera entre miradas.
- `duration-multiplier: 0.5`: miradas más cortas.
- `angle-multiplier: 1.5`: mayor amplitud, conservando los límites de giro.
- `arms-enabled: true`, `arm-min-seconds: 2`, `arm-max-seconds: 5`, `arm-chance: 0.8`: gestos independientes de las miradas.

Los brazos no gesticulan mientras lee, come o bebe. Estas modificaciones corresponden a caminar/sentarse; no agregan movimiento aleatorio ni nuevos goals. Desactivar las miradas generales o del contexto también desactiva sus gestos ocasionales en ese contexto.

## Actualización y validación

No se sobrescribe el `config.yml` existente. Las opciones nuevas tienen valores predeterminados internos. Para personalizarlas, incorpora las secciones del archivo incluido conservando tu configuración, sin duplicar `routines` ni `npc-sounds`. No reemplaces el archivo de NPC por los ejemplos de esta guía.

Se reutiliza la tarea de rutinas para voces, ruido espontáneo y miradas de enfado; no se crea una tarea periódica por NPC ni se escribe a disco por gesto. Las opciones de mirada se leen una vez por recarga. Hay un estado de voz/reacción como máximo por NPC; los sonidos no acumulan una cola de diálogos. La edición del prefijo usa la misma persistencia y recarga que otros cambios administrativos.

Revisión estática del fuente, integración de todos los emisores de diálogo, constructores compatibles, cancelación/restauración de poses, límites numéricos, YAML/XML y ZIP. Añadidas fuentes de pruebas para prefijos, configuración de miradas y reacción/cooldown; actualizado el test de inquieto al multiplicador nuevo. **No se compilaron ni ejecutaron esas pruebas**, siguiendo la indicación de entregar solo fuente. La validación de GitHub Actions y el comportamiento visual con LibsDisguises/PacketEvents quedan pendientes; no se midieron CPU/RAM/TPS.

Probar en servidor: prefijo persistente tras reinicio, todas las clases de frase, edición por dos administradores, varias personas oyendo la misma frase, ruidoso durmiendo/sin observadores, golpe durante caminata/silla/cama/cerveza, desconexión del atacante, retorno al goal y recarga durante reacción. Confirmar además que las protecciones de región permiten los eventos esperados.
