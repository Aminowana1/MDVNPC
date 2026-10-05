# Validación — 1.11.11

Entorno de compilación: Java 21, Maven 3.9.9 y dependencias locales de
Paper 1.21.6. Primero se ejecutan las pruebas de terreno, ejecución del
salto, recuperación de suelo y contador de inmovilidad. La entrega se
empaqueta después de la suite completa de regresiones.

Resultado final, 2026-10-05: **BUILD SUCCESS**, **677 pruebas**, con **0 fallos,
0 errores y 0 casos omitidos**. Se generó `MDVNPC-1.11.11.jar` con Java 21.
La comprobación específica previa aprobó 269 casos. La suite completa
incluye además la selección de suelo del editor y los demás sistemas.

## Casos nuevos

- Inmovilidad con oscilaciones de Y, suelo bloqueado y recuperación de
  suelo que no produce desplazamiento; repetición y prioridad del salto.
- Salto completo en espacio libre, salto más corto frente a una pared,
  salto vertical repetible y menor altura bajo un techo.
- Vallas con slabs inferiores/superiores/dobles, trampillas y bloques
  encima, a nivel inferior y con alturas fraccionarias.
- Prohibición de subida y de cruce como suelo plano sobre la valla
  cubierta; apoyo y aterrizaje seguro de las opciones de recuperación.
- Puentes separados, puertas de valla abiertas/cerradas, chunks
  descargados y límites de altura del mundo.

Las pruebas utilizan MockBukkit y entidades/resultados de ruta simulados.
Verifican las decisiones de movimiento y las colisiones de MDVNPC. La
observación visual en un servidor Paper/Purpur con el mundo y los plugins
del usuario sigue pendiente. En particular, comprobar el NPC detenido
junto a la construcción afectada, con la velocidad y altura de su goal.

Antes de entregar se comparan por SHA-256 las fuentes con las usadas en las
pruebas, cada entrada del ZIP con su archivo de trabajo y las clases/recursos
del JAR con la salida de compilación. El ZIP de fuentes excluye JAR, clases
compiladas y logs; el JAR se entrega por separado.
