# Validación — 1.11.10

Entorno: Java 21, Maven 3.9.9 y dependencias locales del proyecto, con API de
Paper 1.21.6. Se compila y se ejecutan las pruebas con `clean test`; esta fase
no empaqueta un JAR.

Resultado final, 2026-10-05: **BUILD SUCCESS**. **639 pruebas**, con **0 fallos,
0 errores y 0 casos omitidos**. La primera ejecución completa fue seguida
de una recompilación y otra ejecución completa tras los ajustes finales del
salto. El segundo resultado corresponde al código de esta entrega.

Se comprobaron 19 casos de ejecución del salto, 29 de integración del
contador de inmovilidad con las rutinas y 78 de terreno, además de las
regresiones del proyecto. No se generó un JAR del plugin.

## Cobertura

- Barreras de todas las variantes actuales de valla y puertas de valla;
  puertas abiertas, puentes sobre vallas inferiores y colisión con la
  geometría real del bloque.
- Salto animado, dirección de la mirada, altura/distancia mínimas y máximas,
  aterrizajes en suelo fraccional y actualizaciones demoradas.
- Rechazo de paredes, techos bajos, huecos sin apoyo, peligros y chunks
  descargados; apertura de puertas autorizada y cancelada.
- Detección de movimiento real, comandos sin desplazamiento, pequeños
  movimientos acumulados, reintentos y accesos alternativos del mismo goal.
- WALK meta/ciclo/aleatorio, desplazamientos WORK/SLEEP/SIT, exclusión de
  poses y trabajo en el puesto, pausas, baile, bebida y reacciones.
- Regresiones existentes de navegación, terreno, gravedad, puertas, cama,
  sillas, música, tiendas, comandos y edición.

Los tests de materiales sólo incluyen bloques actuales de Paper 1.21. Los
valores `LEGACY_*` representan formatos antiguos y no son bloques utilizables
en el servidor objetivo.

## Alcance

Las pruebas usan MockBukkit y dobles para las entidades y los resultados de
Paper. Verifican la ejecución y la recuperación de MDVNPC, pero no sustituyen
una prueba visual en un servidor Paper/Purpur con el mundo y los demás
plugins reales.

Para esa prueba, verificar un NPC caminando hacia cada tipo de goal, un
tramo con vallas y una puerta de valla abierta, una ruta con mud/slabs/stairs/
carpet, y los estados de cama, asiento y puesto de trabajo. Provocar una
detención en suelo despejado para observar el salto tras 3 segundos; frente
a una pared o bajo un techo insuficiente debe respetar la colisión. Cambiar
`height`/`distance` y desactivar `enabled` comprueba los ajustes.

El empaquetado compara por SHA-256 cada archivo del ZIP con la fuente de
trabajo. Incluye las fuentes, recursos, pruebas y documentación; excluye
directorios de compilación, JAR, clases compiladas y logs.
