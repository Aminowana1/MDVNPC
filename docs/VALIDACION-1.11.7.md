# Estado de validación de MDVNPC 1.11.7

El estado exacto y los pasos para continuar están guardados en
[PROGRESO-1.11.7.md](PROGRESO-1.11.7.md).

Verificación completa del estado final con Java 21 y Maven 3.9.9:

**449 pruebas ejecutadas: cero fallos, cero errores y cero omitidas. BUILD SUCCESS.**

Comando: `mvn --batch-mode --no-transfer-progress clean test`.
Terminó el 4 de octubre de 2026 a las 11:39, hora de Argentina.

Incluye las pruebas originales, navegación y terreno ampliados, pérdida de
suelo, viajes largos a otra altura y compatibilidad de tiendas y músicos.
El paquete conserva sólo el fuente; no se generó un JAR.

Queda por comprobar el comportamiento dentro de un servidor Paper real con
clientes y LibsDisguises. El calculador nativo de rutas está simulado en estas
pruebas, por lo que no certifican todas las construcciones posibles.
