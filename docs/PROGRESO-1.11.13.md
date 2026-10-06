# Progreso guardado — MDVNPC 1.11.13

Base 1.11.12 conservada. Fuente editable: work/fix-1.11.13/MDVNPC-main.
Resultado: compilación correcta, 762 pruebas en 57 suites, sin fallos,
errores ni casos omitidos. Guía en GUIA-1.11.13.md y detalles de las
comprobaciones en VALIDACION-1.11.13.md.

Archivos y métodos principales:

- ShopWorkDefinition y NpcDefinition.shopWork: categoría Vendedor/Herrero
  y coordenadas de estaciones. NpcParser carga y valida sus datos;
  los NPC anteriores mantienen su comportamiento de Vendedor.
- ShopWorkEditor: open/openStations, selección guiada y persistencia
  de fundición, caldero y yunque; permisos, cancelaciones y recargas.
- BlacksmithController: tick/beginTravel/travel/effects/updateFlights
  ejecutan el ciclo usando RoutineNavigator. travelExpired limita
  los viajes incluso durante saltos o recuperación del suelo.
  stop/clear retiran objetos y restauran manos y orientación.
- RoutineService: updateBlacksmith, returnBlacksmithToPost,
  interruptBlacksmith y stopBlacksmith integran horario, trabajo,
  comercio, movimiento, gravedad, reacciones y limpieza.
  canInteract/canLook y recoverFloor respetan la actividad de herrero.
- ShopService.hasOpenSession detecta compras para pausar la animación.
- NpcCommand añade /mdvnpc herrero; NpcEditor muestra las categorías.
  RoutineCommands abre estaciones después de marcar el puesto.
  RoutineEditor y TraitEditor cancelan selecciones incompatibles.
- MdvNpcPlugin registra el editor y lo cierra al recargar o desactivar.
  config.yml añade tiempos blacksmith y ayuda; pom.xml pasa a 1.11.13.

El ciclo vuelve a la fundición tras fundir mineral, enfriar un lingote,
golpear el yunque, enfriar una espada y golpear el yunque otra vez.
Ambos trabajos del yunque duran 120 segundos por defecto. La tienda
conserva sus intercambios y el puesto normal sirve de regreso cuando
la animación no es posible. Los objetos son exclusivamente visuales.

Prueba visual en el servidor real pendiente.
