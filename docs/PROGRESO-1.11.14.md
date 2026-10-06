# Progreso guardado — MDVNPC 1.11.14

Base 1.11.13 conservada. Fuente editable: work/fix-1.11.14/MDVNPC-main.
Resultado final: compilación correcta, 850 pruebas en 63 suites,
sin fallos, errores ni casos omitidos. Guía en GUIA-1.11.14.md;
comprobaciones en VALIDACION-1.11.14.md.

Archivos y métodos principales:

- BlacksmithController.effects/tick: clink corto de cada golpe y
  final del yunque sin emitir un golpe extra.
- FishingDefinition y ShopWorkDefinition: Pescador, puntos de tierra,
  muelle, puntos de bote y dirección. NpcParser carga sus datos.
- ShopWorkEditor: categoría Pescador, openFishingStations, listas de
  puntos, selección de superficie de agua y dirección del administrador.
  NpcEditor muestra el acceso a categorías y estaciones.
- FishermanController: tick/walk/launch/sail/fish/disembark ejecutan
  el ciclo. pause mantiene el comercio en su ubicación. suspend/resume
  conservan bote y fase durante golpes. stop/clear retiran los efectos
  y el bote, desmontan y restauran la apariencia cuando sigue siendo propia.
- BoatNavigator: position/valid/segment/route/castClear comprueban
  agua, huella, espacio del pasajero, ruta y arco de la boya. Cuadrícula
  de medio bloque y caché de consulta que se descarta al terminar.
- RoutineService: updateFisherman, retorno al puesto, suspensión por
  reacción y reanudación. Integración con WORK, tienda, gravedad y
  salto en tierra; montaje y movimiento del pasajero protegidos.
- NpcListener: protege el bote de entradas, desmontajes, daños y
  destrucción externos; permite el movimiento del pasajero asignado.
- NpcCommand añade /mdvnpc pescador; RoutineCommands abre su selección
  tras configurar el puesto. config.yml añade tiempos y parámetros
  fisherman; pom.xml pasa a 1.11.14.

El NPC conserva su tienda y un puesto base. Alterna pesca en tierra y
pesca en bote con puntos elegidos al azar. Al regresar al muelle baja,
el bote desaparece y comienza otro ciclo. Los golpes en bote sólo pausan
su fase y luego permiten reanudarla sin abandonar el asiento.

Prueba visual en servidor real pendiente.
