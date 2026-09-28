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

