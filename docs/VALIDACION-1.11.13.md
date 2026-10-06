# Validación — MDVNPC 1.11.13

2026-10-05: **BUILD SUCCESS**, **762 pruebas en 57 suites**, con
**0 fallos, 0 errores y 0 casos omitidos**. Java 21, Maven 3.9.9,
API de Paper 1.21.6 y LibsDisguises 11.0.18. El grupo focalizado
de herrero, editor, tienda y comandos aprobó sus 74 pruebas previamente.

Se añadieron 50 pruebas a las 712 de la base 1.11.12:

- 21 del controlador: ciclo completo y dos sesiones de yunque de 120 s,
  tiempos configurables, MACE, fundición en aire, separación de un bloque,
  objetos visuales consumidos y nunca entidades Item recogibles, limpieza
  y restauración de equipo, orientación y cambios de otros sistemas.
- Faltas de estaciones, agua o yunque; mundo incorrecto; chunk descargado
  sin cargarlo; accesos bloqueados; rutas sin progreso y tiempo máximo.
  Llegada simulada falsa, desplazamiento externo, suelo retirado, cuerpo
  incrustado y error durante la creación de un objeto visual.
- 14 de integración: comenzar tras llegar al puesto de Trabajo, conservar
  su puesto y acceso a tienda entre estaciones, volver caminando y esperar
  antes de reintentar, vendedores normales, otros modos y otros goals,
  salto de recuperación y gravedad durante el viaje, comercio abierto,
  cambios de horario, observadores, retirada y reacciones.
- 14 del modelo y editor: valores anteriores compatibles, categorías,
  validación de estaciones, selección guiada, persistencia de coordenadas,
  estaciones inválidas, permisos y cancelación, eventos de la otra mano
  o cancelados, y necesidad de un punto de Trabajo.
- Una de tienda: detección de un comercio abierto y su cierre.

La batería completa incluye las regresiones existentes de navegación,
puertas, gravedad, vallas, slabs, mud, alfombras, camas, sillas, trabajo,
músicos, bailes, rasgos, tiendas y editores. RoutineNavigator,
RoutineTerrain y RoutineGravity conservan los archivos de la base 1.11.12.

Los tests utilizan MockBukkit y entidades/rutas simuladas. No confirman
la apariencia final de los golpes, partículas y objetos en un cliente real.
La prueba visual en Paper/Purpur con LibsDisguises queda pendiente;
la guía explica los casos que conviene recorrer en el servidor.

Las 137 fuentes, pruebas y recursos de la compilación se compararon por
SHA-256 con la instantánea tomada antes de la batería completa. El JAR
entregado tiene 172 clases y 12 recursos, comparados con target/classes,
versión 1.11.13 y bytecode de Java 21. El ZIP de fuentes se verifica
entrada por entrada y excluye JAR, clases, target y logs.
