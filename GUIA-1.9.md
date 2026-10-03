# MDVNPC 1.9.0 — Músicos animados

Los NPC con trabajo **Músico** muestran su instrumento cuando están en su puesto de Trabajo:

- **Flauta:** bambú con pose de llevarlo a la boca.
- **Guitarra:** armadura de caballo de hierro y gestos de rasgueo.
- Movimiento suave de cabeza durante la canción y partículas de notas que ascienden desde
  la zona del instrumento. Las notas solo se envían a jugadores cercanos, dentro del radio musical.

Se activa automáticamente para los músicos existentes. No necesitas recrearlos ni borrar
`config.yml`. Conserva las seis canciones, la sincronización por conjunto y el baile exclusivo
del rasgo Fiestero de 1.8.0.

## Configurar un músico

Abre `/mdvnpc edit <id>` → **Trabajo del NPC** → **Músico** → **Flauta** o **Guitarra**.
En **Rutinas y horarios**, añade un goal **Trabajo**, su horario y el puesto.
El NPC empieza a tocar al llegar. También siguen disponibles:

```text
/mdvnpc trabajo flautista musico flauta
/mdvnpc trabajo guitarrista musico guitarra
/mdvnpc routine flautista
```

La guía completa del editor, los rasgos y las canciones se conserva en [GUIA-1.8.md](GUIA-1.8.md).

## Ajustes opcionales

Dentro de la sección `music` de `config.yml`:

```yaml
music:
  group-radius: 8
  audio-radius: 14
  visuals:
    enabled: true
    head-movement: true
    note-particles: true
```

`enabled: false` desactiva instrumento y gestos; la música sigue sonando.
`head-movement: false` desactiva la animación de cabeza; `note-particles: false` oculta las notas.
No dupliques la sección `music` si ya existe. Aplica los cambios con `/mdvnpc reload`.
Los valores nuevos tienen estos predeterminados aunque falten en tu archivo actual.

## Limpieza y rendimiento

El instrumento es equipo cosmético del disfraz: no se genera un objeto en el suelo ni se
inserta en inventarios. Al acabar el Trabajo, descargarse el NPC o recargarse el plugin,
se restaura el equipo anterior. Si recibe un golpe o acepta una cerveza, las animaciones
ceden la mano y la cabeza a esa actividad y se reanudan después si continúa trabajando.

Se reutilizan el reloj musical y la audiencia local en caché. No se crean tareas por músico,
por nota ni por partícula. Los gestos tienen una frecuencia limitada y las notas usan la
animación ascendente del propio cliente. No hay escaneos globales nuevos ni entidades auxiliares.

La compilación y las pruebas automatizadas se detallan en `dist/VERIFICACION.txt`. Las pruebas
usan MockBukkit/Mockito; falta comprobar la apariencia de las poses en un cliente conectado
a Paper/Purpur real y medir el consumo allí.

## Actualizar

Java 21, Paper/Purpur 1.21.6, LibsDisguises 11.0.18 y PacketEvents 2.14.0.
Apaga el servidor, guarda una copia de `plugins/MDVNPC/`, sustituye el JAR anterior y arranca.
Conserva `NPCs/` y los archivos de configuración; los datos de 1.8.0 son compatibles.
