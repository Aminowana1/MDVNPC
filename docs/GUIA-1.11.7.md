# MDVNPC 1.11.7 — navegación y recuperación

Esta entrega parte del ZIP 1.11.6 auditado. Conserva Paper 1.21.6 como calculador de
rutas y la IA del aldeano desactivada. No reactiva los pathfinders anteriores.

## Cambios

- **Escalones:** la altura ganada se conserva hasta entrar sobre el apoyo. El
  asentamiento sobre el piso anterior deja de deshacer la subida. El NPC sólo
  inicia una subida cuando el waypoint tiene una altura superior; una pared
  lateral sigue siendo un obstáculo.
- **Rodeos:** la búsqueda comienza en 16 y permite 24, 32, 48 y 64 ante rutas
  fallidas, parciales repetidas o accesos a otra planta. Una ruta parcial útil
  puede continuar el viaje sin exigir una búsqueda completa cara, incluso si
  termina debajo/encima del goal pero ha mejorado realmente la altura y la distancia.
- **Atascos:** se comprueba el movimiento real, se acumula progreso y se conserva
  un historial de hasta doce endpoints parciales. Los ciclos no mantienen al NPC
  caminando y recalculando indefinidamente. Un desplazamiento externo importante
  invalida la ruta anterior.
- **Reintentos:** obstáculo local, cuatro ticks; chunk descargado, veinte;
  búsqueda agotada, cien. El presupuesto compartido conserva el límite de
  búsquedas por actualización y evita comenzar más consultas una vez consumido
  el tiempo disponible. Una consulta síncrona de Paper ya iniciada termina antes
  de comprobar ese tiempo.
- **Colisiones:** se inspeccionan también las formas de muros y vallas que
  sobresalen desde un bloque inferior. Una puerta abierta conserva la colisión
  de su hoja; el centro libre sigue permitiendo el paso.
- **Puerta denegada:** si la ruta propuesta cruza una puerta cerrada sin permiso,
  una consulta posterior de Paper busca una alternativa sin abrir puertas
  cerradas. Usa el mismo presupuesto compartido. Las puertas abiertas siguen
  disponibles y los permisos se vuelven a considerar después del reintento largo.
- **Coste del terreno:** se evita calcular repetidamente las mismas formas
  dentro de una actualización. La caché admite hasta 512 entradas, se descarta
  al terminar y comprueba cambios de material/estado. Las puertas se leen siempre
  en vivo. No se solicita cargar chunks.
- **Goals:** las velocidades bajas ya cuentan como progreso. La llegada y pérdida
  de suelo en WORK se recuperan conservando las reglas de los distintos tipos de
  NPC. Los músicos mantienen su regreso al puesto tras un desplazamiento. Cama y
  silla conservan sus alternativas de acceso y reservas durante las recuperaciones.
- **Pérdida de suelo:** una recuperación vertical controlada cubre la falta de
  física natural con NoAI. Revisa sólo datos cargados, busca apoyo hasta ocho
  bloques debajo y limita cada descenso a 0.4 bloques, comprobando el cuerpo a
  intervalos de como máximo 0.08. Puede continuar una caída más profunda por
  etapas. Suspende el movimiento ante geometría peligrosa o chunks ausentes.
  Respeta vehículos, poses, baile, reacciones y bebida. El chequeo estable se
  espacia veinte ticks y la caída usa la cadencia de movimiento.

## Archivos y métodos principales

| Archivo | Cambios relevantes |
| --- | --- |
| `RoutineNavigator.java` | `move`, `advance`, `walkingStep`, `observeProgress`, `rememberPartialEndpoint`, `applyStep`, `tuneNextSearch` y reintentos. |
| `RoutineTerrain.java` | `fits`, `supportHeight`, `beginUpdate`, lectura/caché de colisiones y `fallColumn`. |
| `RoutineService.java` | Progreso acumulado, recuperación de piso y conexión con el ciclo de rutinas. |
| `RoutineGravity.java` | Nuevo controlador acotado de pérdida de apoyo, sin activar IA. |
| `pom.xml`, documentación y pruebas | Versión 1.11.7 y regresiones de los escenarios auditados. |

Los sistemas de tiendas, skins, diálogos, música, baile y poses conservan sus
implementaciones. La recuperación de WORK usa sus mecanismos existentes para
retirar la atención y la música durante el desplazamiento y restaurarlas al llegar.

## Validación

Consultar `VALIDACION-1.11.7.md` para el resultado exacto de la compilación y las
pruebas de esta entrega. Las pruebas de movimiento utilizan las clases reales
del plugin, formas de colisión controladas y respuestas de ruta simuladas.
Incluyen los 24 sentidos/formas de escaleras, superficies parciales, ventanas,
pasillos estrechos, rodeos, ciclos, pérdida de suelo y viajes de 160 bloques a cama
y trabajo por encima y por debajo del inicio. La cama de esos viajes tiene sólo
un lado disponible.

No se ejecutó un servidor Paper con clientes y LibsDisguises. Las pruebas no
certifican todas las construcciones posibles ni miden el coste real en un servidor.
Un acceso necesita espacio físico para el cuerpo y los chunks del recorrido
deben estar cargados. El plugin respeta protección y permisos de puertas.

## Instalación

Esta entrega contiene sólo el fuente. Completar primero la verificación indicada
en `PROGRESO-1.11.7.md`; el JAR se generará al compilar el proyecto.

Usar Java 21, Paper 1.21.6 y las dependencias ya requeridas por el proyecto.
Con el servidor detenido, sustituir el JAR anterior por `MDVNPC-1.11.7.jar`
y mantener la carpeta de datos de MDVNPC. Evitar dejar dos JAR del mismo plugin.
Después de iniciar, comprobar las rutas reales de las camas y puestos de la ciudad,
incluidos sus accesos únicos, puertas protegidas y cambios de piso.
