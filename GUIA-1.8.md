# MDVNPC 1.8.0 — Fiestero, canciones y editor

Requiere Java 21 y Paper/Purpur 1.21.6, LibsDisguises 11.0.18 y PacketEvents 2.14.0.
La actualización conserva las rutinas, tiendas, skins y opciones de los NPC existentes.
Reemplaza el JAR con el servidor apagado y una copia de `plugins/MDVNPC/`.

## Editor

Abre `/mdvnpc edit <id>` con permiso `mdvnpc.admin`. También funcionan `editor` y `menu`.
Desde `/mdvnpc routine <id>` puedes pulsar **Editar NPC**.

- **Cambiar nombre:** abre un yunque gráfico. Escribe el nombre arriba y pulsa el resultado
  para guardarlo; admite colores con `&` y un máximo de 50 caracteres. No consume experiencia ni objetos. Escape cancela
  y vuelve al menú. Cambia el nombre visible, conservando el ID y sus archivos.
- **Trabajo del NPC:** elige **Normal**, **Tienda** o **Músico**. Músico abre la selección de
  **Flauta** y **Guitarra**. Puedes cambiar el instrumento en cualquier momento.
- **Rutinas y horarios:** abre el editor existente con sus goals y alternativas aleatorias.
- **Editar tienda:** abre los intercambios del NPC con trabajo Tienda. Si aún no es vendedor,
  primero ofrece seleccionar ese trabajo. Los intercambios se conservan al cambiar de trabajo.
- **Rasgo del NPC:** incluye la nueva opción **Fiestero**.
- **Prefijo de los diálogos**, **Nombre visible** y **NPC activado:** accesos a esas opciones.

La selección de trabajo, instrumento y rasgo se guarda al pulsar. Los músicos necesitan un
goal **Trabajo**, su horario y un puesto: abre **Rutinas y horarios** para configurarlo.
Los vendedores con rutinas también atienden durante su Trabajo y cuando llegan al puesto.

## Quién baila

Solo bailan los NPC con rasgo **Fiestero** que estén realizando un goal **Sentarse** y escuchen
a músicos trabajando cerca. Los demás rasgos, incluido **Sin rasgo**, conservan su actividad.
Cada NPC tiene un solo rasgo: seleccionar Fiestero sustituye el anterior.

El fiestero deja la silla, camina hasta estar a un máximo de 3 bloques del músico y alterna
saltos, agacharse, giros y pequeños pasos. Después vuelve a la silla reservada. No interrumpe
el sueño ni un puesto de trabajo para bailar. El baile termina al cambiar de horario,
detenerse la música, desaparecer jugadores cercanos o interrumpirse por una reacción.
Si no hay suelo seguro o camino accesible, no atraviesa obstáculos ni fuerza chunks.

Se corrige el bloqueo de navegación causado por esperar una señal de suelo que el aldeano
con IA desactivada puede no actualizar. La consulta de ruta ahora usa el suelo verificado
sin activar su IA ni esperar a la gravedad. Una vez dentro del radio de baile, los gestos
pueden empezar aunque un paso local no consiga alcanzar su punto exacto.

Por defecto `routines.dancing: true`, baile de 20–40 segundos y 30 segundos de espera antes
de repetir. Puedes modificar `dance-min-seconds` y `dance-max-seconds` en `config.yml`.

## Música

El repertorio conserva Tourdion, Jabalí y Farol e incorpora:

| Canción nueva | Estilo | Duración a 20 TPS |
| --- | --- | --- |
| Romería de las Linternas | Danza en 6/8, modo dórico | 30 s |
| El Cuervo y la Jarra | Vals de taberna, modo eólico | 30 s |
| Branle del Roble | Danza en 4/4, modo mixolidio | 30 s |

Las partes de Flauta y Guitarra comparten la misma línea de tiempo. Cada instrumento funciona
también solo. El conjunto sortea entre las seis canciones y empieza sincronizado; el audio
se envía únicamente a jugadores cercanos (14 bloques por defecto, configurable entre 12 y 15).
Las tres canciones nuevas duran 600 ticks; si el servidor pierde TPS, tardarán más en tiempo real.

## Comandos opcionales

Todo lo anterior se puede configurar desde el editor. Los comandos anteriores siguen disponibles:

```text
/mdvnpc edit flautista
/mdvnpc trabajo flautista musico flauta
/mdvnpc trabajo guitarrista musico guitarra
/mdvnpc rasgo parroquiano fiestero
/mdvnpc routine flautista
```

## Rendimiento y comprobación

El editor no añade tareas permanentes. La navegación usa Paper y mantiene el límite compartido
de nuevas consultas de ruta; no se reactiva el buscador A* propio. El baile aprovecha el
planificador de rutinas y solo busca música para fiesteros sentados. La música conserva
sesiones y audiencia local en caché y solo procesa músicos trabajando. Las canciones se
cargan una vez y se indexan por tick, sin crear tareas por nota o por NPC.

La compilación y las pruebas automatizadas se detallan en `dist/VERIFICACION.txt`.
No se ha realizado una prueba visual en servidor real ni medido CPU, RAM o TPS; el coste
para 25 NPC depende de los caminos, jugadores cercanos y el hardware.
