# Validación — MDVNPC 1.11.12

2026-10-05: **BUILD SUCCESS**, **712 pruebas en 53 suites**, con
**0 fallos, 0 errores y 0 casos omitidos**. Compilación con Java 21,
Maven 3.9.9 y las dependencias locales de Paper 1.21.6.

Casos específicos:

- Inicio sin preflight de terreno: flotando, sin apoyo ni suelo final,
  suelo peligroso, cuerpo incrustado y NPC ya sobre una cubierta de valla.
- Pared que recorta X y deja subir; techo que recorta Y; encierro completo
  que consume la animación sin desplazamiento ni teleport atravesando bloques.
- Dirección de la mirada, altura 0.6 en espacio libre, animación gradual,
  variación externa de Y y cancelación de eventos de movimiento.
- Salida de incrustación por el lado más cercano, sin entrar más en el bloque
  ni atravesar un segundo obstáculo.
- Vallas, cubiertas inferiores y superiores, puertas de valla abiertas/cerradas,
  puertas normales con sus paneles reales y permisos de apertura.
- Chunks descargados, límite de mundo y consultas acotadas: un destino
  descargado permite el salto local sin leer ni entrar en ese chunk.
- Repetición ante suelo bloqueado, recuperación de caída después del salto,
  ausencia de progreso falso por oscilación y exclusiones de poses/trabajo.
- Regresiones previas de navegación, terrain, gravedad, cama, silla, trabajo,
  editor, tiendas, nombres, rasgos y restantes sistemas del proyecto.

Los tests emplean MockBukkit y entidades/rutas simuladas. Verifican la capa
de ejecución y recuperación de MDVNPC. La prueba visual con el mundo y los
plugins del usuario en un servidor Paper/Purpur sigue pendiente.

Antes de entregar se comparan las fuentes principales con la instantánea
usada al iniciar las pruebas, las clases y recursos del JAR con target/classes,
y cada entrada del ZIP con su archivo fuente mediante SHA-256. El ZIP
excluye JAR, clases compiladas, target y logs.
