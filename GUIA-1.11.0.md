# MDVNPC 1.11.0 — Atender durante otros goals

Puedes permitir que un NPC siga ejecutando sus comandos de interacción o vendiendo
en su tienda durante un goal de Sentarse, Dormir o Caminar.

## Configurarlo en el editor

1. Abre `/mdvnpc routine <id>` y selecciona el goal.
2. Activa **Atender durante este goal**.
3. El ajuste se guarda para ese goal y todas sus variantes aleatorias. También puedes
   cambiarlo desde la pantalla de una variante; cambia el ajuste común del goal.

El NPC conserva la actividad elegida: puede atender sentado sin levantarse, o
permitir compras y comandos mientras camina. Se permite atender durante todo el
goal activo, incluido el camino a su destino. No se crean comandos ni productos
nuevos: se usan los que ya configuraste para ese NPC.

Cuando pasa a otro goal que no permite atender, dejan de estar disponibles esas
interacciones. Las compras vuelven a comprobar el estado del NPC antes de completarse.
Se mantienen las restricciones de distancia, permisos y protección de los comandos
y tiendas.

## Archivos y compatibilidad

En `plugins/MDVNPC/NPCs/<id>/routines.yml`, el ajuste pertenece al goal principal:

```yaml
npcs:
  vendedor:
    goals:
      '1':
        # Conserva aquí el tipo, horario, puntos y demás ajustes del goal.
        work-interaction: true
```

No copies ese fragmento sobre todo el archivo: añade la clave al goal existente.
Las variantes heredan el valor del principal; no necesitan una clave propia.

El valor predeterminado es `false`. Los goals de Trabajo conservan la atención
habitual al llegar al puesto. No necesitas borrar configuraciones ni recrear NPC.
Después de editar archivos a mano, usa `/mdvnpc reload`.

## Actualizar

Esta entrega contiene el ZIP de fuentes. Para generar el JAR con Java 21, usa
`mvn package -DskipTests` o el flujo de compilación del repositorio. Después apaga
el servidor, respalda `plugins/MDVNPC/`, sustituye el JAR y arranca.
Se conservan las mejoras de [1.10.1](GUIA-1.10.1.md).

La opción se comprueba al interactuar y durante las comprobaciones de compra que
ya existían. No añade tareas por NPC ni búsquedas globales por tick.

Java 21, Paper/Purpur 1.21.6, LibsDisguises 11.0.18 y PacketEvents 2.14.0.
Compilaron todas las fuentes Java y se ejecutaron siete pruebas rápidas del
modelo y su persistencia; la comprobación en juego queda a cargo del usuario.
El detalle está en `dist/VERIFICACION.txt`.
