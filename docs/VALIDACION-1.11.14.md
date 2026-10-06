# Validación — MDVNPC 1.11.14

2026-10-06: **BUILD SUCCESS**, **850 pruebas en 63 suites**, con
**0 fallos, 0 errores y 0 casos omitidos**. Java 21, Maven 3.9.9,
Paper API 1.21.6 y LibsDisguises 11.0.18. Se añadieron 88 pruebas a
las 762 de la base 1.11.13.

Casos nuevos:

- Herrero: clink metálico únicamente junto al gesto de golpe; sin sonido
  de martilleo entre gestos o al abandonar el yunque. La secuencia de
  fundición y caldero conserva el comportamiento anterior.
- Configuración y editor: categoría Pescador compatible con SHOP,
  constructor anterior de ShopWorkDefinition, dirección guardada,
  puntos múltiples y límite de 32, superficie del agua, persistencia,
  permisos, cancelaciones y protección de cambios concurrentes.
- Controlador: comienzo tras llegar al puesto base, punto de tierra
  y lance de 4.5 bloques, caña, ciclo completo, bote nativo con pasajero,
  regreso al muelle y retirada del bote. Puntos inválidos o inaccesibles,
  pérdida de suelo, pasajero o chunk, tiempo máximo y limpieza.
- Golpes en bote: conservación de posición, montaje, orientación de la
  reacción y fase; reanudación de pesca, pausa de los relojes de viaje
  y limpieza si termina el horario durante la reacción.
- Comercio desde bote: pausa sin mover al NPC para conservar la distancia
  de compra. Mano y orientación respetan las actividades que las prestan.
- Agua: huella de 1.375 bloques, espacio del pasajero, canal de dos bloques,
  rechazo de uno, rodeo de isla, agua desconectada, paneles, puentes bajos,
  paredes delgadas, cambios de terreno, chunks descargados y arco del lance.
  La ruta está limitada a 4096 nodos y 96 bloques; no carga chunks.
- Eventos: únicamente su NPC puede montar o seguir el bote asignado;
  daño, destrucción, interacción y desmontaje externos se bloquean.

La batería completa también cubre navegación terrestre, vallas, escaleras,
slabs, mud, alfombras, gravedad, camas, sillas, trabajo, música, rasgos,
tiendas y editores. RoutineNavigator, RoutineTerrain y RoutineGravity
mantienen los archivos de la base 1.11.13.

Las pruebas usan MockBukkit y entidades/rutas simuladas. La aparición y
movimiento del bote, la caña, la boya y el sedal, y el sonido del clink
deben comprobarse visualmente en un servidor real con LibsDisguises.
No se ha ejecutado esa comprobación visual.

Antes de entregar se verifican las 146 fuentes, pruebas y recursos contra
la instantánea usada en la compilación, las entradas del JAR contra
target/classes y las entradas del ZIP contra sus archivos fuente.
El ZIP excluye JAR, clases compiladas, target y logs.
