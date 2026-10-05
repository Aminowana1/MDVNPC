# Progreso guardado — 1.11.12

Base preservada: 1.11.11. Fuentes editables en work/fix-1.11.12/MDVNPC-main.

Cambio solicitado: ejecutar el fallback siempre ante inmovilidad al caminar,
sin requisitos preventivos de apoyo, espacio ni aterrizaje seguro.

Archivos y métodos de producción:

- RoutineNavigator: startRecoveryHop encola el impulso incondicional;
  advanceForcedHop ejecuta incrementos de arco con recorte de colisiones.
  La API startHop estricta sigue disponible para integraciones previas.
- RoutineTerrain: clipRecoveryHop y clipAxis recortan desplazamiento por
  voxels, admiten salida de incrustación y conservan barreras de valla/cubierta.
- RoutineService: stuckHop observa avance neto al finalizar, reinicia gravedad
  al comenzar y le devuelve el control al terminar; recoverFloor respeta el
  próximo intento y no convierte un salto vertical en progreso de ruta.
- config.yml: comentarios del impulso; pom.xml: versión 1.11.12.

Pruebas ampliadas en RoutineHopTest, RoutineStuckHopTest y
RoutineForcedHopTerrainTest. RoutineRuntimeTest verifica que el salto local
no acceda al chunk descargado del destino. Resultado final: BUILD SUCCESS,
712 pruebas en 53 suites; 0 fallos, errores u omisiones.
Validación final en VALIDACION-1.11.12.md.

Los otros sistemas y la búsqueda nativa de Paper conservan su implementación.
La prueba visual en un servidor real queda pendiente.
