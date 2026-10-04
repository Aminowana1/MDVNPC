# MDVNPC 1.11.3 — Pathfinding adaptativo y recuperación de atascos

- `RoutineNavigator` amplía el `FOLLOW_RANGE` sólo al detectar falta de progreso: 16 → 24 → 32 bloques.
- Se usa `PathResult.canReachFinalPoint()` y se aceptan rutas parciales/rodeos que no reduzcan inmediatamente la distancia al destino.
- Se eliminó el rechazo global por un chunk vecino descargado; se siguen rechazando el inicio, destino o waypoints que realmente estén en chunks no cargados.
- Reintentos separados: obstáculo local rápido, chunk descargado intermedio y ruta agotada lenta.
- Detector de progreso/atasco para impedir que una ruta mala se repita indefinidamente mirando una pared.
- `RoutineTerrain` separa soporte bajo los pies de colisión lateral; una esquina de pared ya no se interpreta como un escalón.
- Soporte de superficies finas con colisión, incluida alfombra, conservando stairs, slabs, bloques completos y desniveles de hasta un bloque.
- Reproducción de diagonales con deslizamiento corto por eje cuando la caja de 0,60 bloques roza una esquina.
- Cama/asiento: se conservan todos los accesos localmente válidos y sólo se prueba otro lado después de agotar la búsqueda ampliada del lado actual.
- El límite de viaje mide 60 s sin progreso, no 60 s desde la salida; una ruta larga o velocidad baja no se cancela mientras el NPC continúe avanzando.
- Sin cambios a tiendas, diálogos, músicos, traits, puertas, sillas ni almacenamiento fuera de las integraciones necesarias de rutina.
- Guía: `GUIA-1.11.3.md`.

# MDVNPC 1.11.2 — Escaleras, puertas y sillas

- Caminata con las cajas de colisión reales de escaleras y losas, incluida la altura del apoyo bajo el cuerpo del NPC.
- Aproximación al escalón antes de elevarse; desplazamiento horizontal y vertical comparten el límite de velocidad.
- La navegación normal permite subir bloques y cambiar de nivel. El movimiento de baile conserva una pista nivelada.
- Cierre de puertas de madera utilizadas por la ruta, incluidas las que ya estaban abiertas si `routines.close-preopened-doors` está activo.
- La comprobación de ocupación usa el hueco de la puerta: un NPC sentado o quieto al lado ya no retiene su cierre. Se siguen comprobando ocupantes del paso, redstone, cambios de la puerta y protecciones.
- Montaje de sillas verificado contra el soporte exacto; recuperación de desmontajes cercanos y actualización del montaje al aparecer observadores o reanudarse la rutina.
- Limpieza y regreso local ante montajes fallidos; un soporte desaparecido, un vehículo ajeno o un desplazamiento grande obliga a abandonar la pose y reintentar por la ruta.
- Guía: `GUIA-1.11.2.md`. Resultados de comprobaciones locales en `dist/VERIFICACION.txt`; la apariencia y el comportamiento con clientes reales requieren una comprobación en el servidor.

# 1.5.0 — personalidad y prefijos

- Prefijo por NPC desde el editor, con vista previa, eliminación y restauración del formato original.
- Formato compartido para todas las frases propias del NPC, incluidas fuera de horario y enfado.
- Voz en secuencias; ruido espontáneo más largo para ruidoso.
- Reacción cosmética a golpes, pausa de caminata, mirada al atacante y recuperación de rutina.
- Configuración independiente de miradas caminando/sentado, inquieto más activo y gestos configurables.
- Configuración anterior compatible, nuevos tests fuente, workflow intacto. Sin compilación ni ejecución de pruebas locales.
- Guía: PERSONALIDAD-1.5.0.md.

# MDVNPC 1.3.1

- Lectura ocasional sentado con libro, duración configurable y restauración del objeto anterior.
- Miradas espaciadas y suavizadas al desplazarse y sentarse; seguimiento sentado limitado a 3 bloques.
- Mantener cama/asiento durante suspensión y reparar posición/pose de sueño al reactivarse.
- Cerrar puertas de madera previamente abiertas al pasar, con control de obstáculos, redstone y protecciones.
- Tarea compartida y estado acotado; sin cargas de chunks ni escrituras adicionales periódicas.
- Pruebas de regresión añadidas como fuente, sin ejecutar ni compilar por petición del usuario.
- Se preservan GUI, diálogos y almacenamiento de 1.3.0. Workflow de GitHub intacto.
# MDVNPC 1.3.0

- Nuevo editor gráfico con `/mdvnpc routine|rutina|rutinas <npc>`: lista goals, alta rápida y edición por goal.
- Edición de horario, velocidad, puntos/cama/sillas/puesto, modo y radio de caminar desde el GUI.
- Diálogos independientes por goal con rango, intervalo, demora inicial, orden y línea de visión.
- Compatibilidad: los WORK antiguos heredan el diálogo global hasta ser editados.
- Mensaje configurable con cooldown al hacer clic derecho cuando el NPC no está trabajando.
- Persistencia por NPC en `NPCs/<id>/npc.yml`, `routines.yml`, `shop.yml` y `skin-cache.yml`.
- Migración automática de `npcs.yml`, `routines.yml`, `shops.yml` y `skins.yml` con copia `*.legacy-backup`.
- Escrituras optimizadas: editar un NPC o su reloj no reescribe archivos de otros NPC.
- Maven 1.3.0; workflow de GitHub sin cambios de versión manual.

# MDVNPC 1.2.0

- Goals por mundo y hora: dormir, caminar, sentarse y trabajar, con velocidad individual.
- Caminar meta, aleatorio entre puntos marcados y ciclo; horarios nocturnos y validación de superposiciones.
- Selección por clic, preguntas de horario por chat, cancelación y detección de ediciones concurrentes.
- Poses de cama y asiento en stairs; comida y bebida cosméticas, incluida cerveza MMOItems configurable.
- Tiendas y comandos disponibles solamente al llegar al puesto y durante el horario de trabajo.
- Puertas de madera con cierre cuando queda libre el paso, integración opcional con WorldGuard.
- Navegación incremental y caché acotadas, sin tickets de chunks ni escrituras por movimiento.
- Recuperación por horario al cargar zonas; limpieza de asientos, poses y sesiones al retirar NPC.
- Reloj opcional por mundo con duración independiente de día/noche y restauración de doDaylightCycle.
- Maven 1.2.0; build.yml sin cambios. Entrega únicamente fuente, sin compilación final por petición del usuario.
# MDVNPC 1.1.2

- Editor con movimientos reales de inventario: elimina la copia deliberada del cursor.
- Clic, división de pilas, arrastre, teclas numéricas y mano secundaria delegados a Paper.
- Shift + clic en ambos sentidos, respetando capacidad y excluyendo botones.
- Doble clic recoge solo objetos editables, sin extraer controles.
- Persistencia de columnas incompletas en `shops.yml`; retirar un componente ya no restaura la oferta anterior al reabrir.
- Guardar conserva la ventana y su cursor; cambios de página protegidos.
- Barreras de referencias ausentes bloqueadas, con eliminación explícita por clic derecho vacío.
- Se conservan skins, trades, MMOItems y el workflow de GitHub sin cambios de versión en build.yml.
# MDVNPC 1.1.1

Base: ZIP MDVNPC-1.1.0-source.zip entregado por el usuario. Se conservan comandos, NPC normales, tiendas nativas, editor de 100 páginas, diálogos, skins explícitas y excepción de spawn de WorldGuard.

- Skins por nombre: captura de textura firmada, firma y UUID en `skins.yml`; restauración al reiniciar, recargar y reaparecer el NPC. Las texturas explícitas de `npcs.yml` tienen prioridad. `/mdvnpc skin <id> <nombre>` invalida la caché, incluso si se repite el nombre.
- Una sola tarea temporal de captura de skins, cada 20 ticks, con límite de 60 intentos. No se guardan perfiles vacíos. Escritura agrupada en un único trabajador y vaciado al apagar.
- Compras: comprobación de NPC vigente, distancia, mundo, visibilidad del jugador, permisos, versión de ofertas, resultado, costos exactos y cantidades. No se retiran ni entregan ítems manualmente: Paper realiza la transacción.
- La edición de ofertas y la desaparición del NPC invalidan inmediatamente las sesiones existentes; su cierre se difiere fuera del evento de inventario.
- Clic normal y Shift admitidos en el resultado. Teclas numéricas, descarte y otros modos especiales bloqueados en el resultado. Editor de plantillas protegido ante clics cancelados, arrastre y sesiones huérfanas.
- Recarga bloqueada si una página editada no se puede guardar. Al cerrar una página inválida se conserva una copia administrativa en `shop-recovery/`; no se consumen objetos reales del administrador.
- Resolución de MMOItems falla de forma explícita si la API está rota; no convierte silenciosamente referencias en snapshots. Categoría/ID conservados. Resolución compartida para referencias idénticas durante una apertura, sin caché entre aperturas.
- Cantidades superiores al máximo apilable dejan la oferta no disponible, evitando el ajuste implícito de precios del motor vanilla. Índices y cantidades mal formados rechazados.
- Guardado de páginas detecta cambios externos en esa página. Escritura temporal, sincronización a disco, sustitución y copia `.bak` para NPC, tiendas y skins.
- Diálogo comercial agrupado por jugador/NPC/tick; limpieza de estado al recargar y mantenimiento periódico. Se conserva la frecuencia independiente de mirada.
- `shop-allow-cancelled-interaction: false` respeta por defecto la cancelación de otros plugins. La excepción anterior es configurable. `worldguard-spawn-bypass: true` mantiene la excepción original y permite desactivarla.
- Entorno de pruebas con MockBukkit compatible con Paper 1.21.6. Pruebas de regresión de skins, pagos, sesiones, editor y persistencia.
- Maven: versión 1.1.1. `build.yml` conservado byte por byte: el patrón `target/MDVNPC-*.jar` ya permite publicar versiones nuevas sin modificarlo.

