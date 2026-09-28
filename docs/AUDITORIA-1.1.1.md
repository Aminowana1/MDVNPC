# Auditoría MDVNPC 1.1.1

## Alcance y procedencia

Se revisó y modificó el código del ZIP **MDVNPC-1.1.0-source.zip** proporcionado en este chat. También se consultó la base 1.0.2 recuperada para comprobar continuidad. No se reconstruyó la tienda desde cero ni se sustituyó el NPC Thurg. Se mantuvieron las clases separadas, Maven, los comandos previos y los archivos de configuración existentes.

Objetivo de compatibilidad: **Paper/Purpur 1.21.6, Java 21, LibsDisguises 11.0.18 y PacketEvents 2.14.0**; WorldGuard 7.0.14 es opcional. MMOItems continúa como integración opcional por reflexión. Spigot puro, Folia y versiones distintas no están certificados.

## Hallazgos y correcciones

| Área | Hallazgo en 1.1.0 | Corrección / estado |
|---|---|---|
| Skins | Guardar solo el nombre no congela la textura obtenida de Internet. | `SkinStore` y `SkinCacheService` conservan textura, firma, UUID y nombre solicitado por NPC. Se restaura sin nueva resolución si hay caché. |
| Skins asíncronas | Una resolución tardía podía requerir control para no reponer una skin anterior. | Invalidación elimina la captura pendiente; cada captura comprueba la definición vigente. No se guardan perfiles vacíos. |
| Autorización comercial | Distancia y permisos se comprobaban al abrir, pero no al comprar. | Validación en el clic de resultado y `PlayerPurchaseEvent`, incluyendo mundo y entidad vigente. |
| Ofertas obsoletas | Las sesiones ya abiertas seguían ofreciendo el precio anterior tras editar. | Revisión de sesión e invalidación inmediata; cierre seguro al siguiente tick. |
| Ítems personalizados | La coincidencia nativa puede aceptar componentes adicionales. | Se exige `ItemStack.isSimilar` además de la cantidad, para no consumir una variante distinta de la plantilla. |
| Cantidades | El precio nativo puede ajustarse al límite apilable. | Plantillas nuevas sobre el máximo se rechazan; ofertas antiguas incompatibles se omiten hasta corregirlas. |
| MMOItems | Un error de reflexión al identificar devolvía null y podía guardar el objeto como snapshot. | El error impide guardar; los métodos se publican en caché solo cuando toda la API requerida se resolvió. |
| Edición | La conversión de ítems ocurría fuera del bloque de manejo de errores. | Conversión, validación y guardado cubiertos; ningún error debe convertirse en confirmación de guardado. |
| Cierre de editor | Un cierre con una columna incompleta perdía el borrador. | Copia en `shop-recovery/` y aviso; las ofertas anteriores siguen vigentes. Si el disco falla también al recuperar, se informa en consola. |
| Concurrencia | Ya existía bloqueo por tienda; faltaba detectar ediciones del archivo externo. | Bloqueo conservado y comparación de la página en disco antes de escribir. |
| Tareas diferidas | Una apertura pendiente podía sobrevivir a una recarga o interferir con otra ventana. | Generación, token por jugador y comprobación del inventario anterior. |
| Diálogos | El estado comercial sobrevivía a recargas y podía mantener índices incompatibles. | Limpieza, índice acotado y mantenimiento; una tarea de mensaje por jugador/NPC/tick. |
| Protección externa | La tienda ignoraba cancelaciones de interacción de otros plugins. | Se respetan por defecto; opción explícita para restaurar la excepción de lobby. |
| Tests originales | Una prueba creaba un ItemStack sin registros de Paper: la compilación con tests fallaba. | Entorno MockBukkit configurado; adaptaciones de simulación detalladas abajo. |

## Garantías de diseño y límites de los intercambios

Cada jugador conserva su propio comerciante virtual. El plugin valida y deja a **Paper** consumir los dos costos, colocar el resultado, manejar Shift, capacidad del inventario y devolver entradas al cerrar. No existe una segunda rutina de entrega o devolución que pueda duplicar esos objetos. No hay inventario ni stock compartido entre jugadores: las ofertas son ilimitadas.

Se validan ambos costos, también si están intercambiados de posición. Si una oferta exige dos costos del mismo material, no se cuenta una sola pila como dos pagos. Los clicks especiales del resultado se cancelan; el clic normal y Shift siguen disponibles. No se intenta corregir manualmente el inventario desde `PlayerPurchaseEvent`.

Esto **no constituye una prueba de ausencia absoluta de duplicación**: las transferencias reales, inventario lleno, desconexión a mitad de paquete, clientes modificados y plugins que alteren eventos después de MDVNPC requieren prueba en el servidor. Un plugin que reabra, modifique o quite cancelaciones de eventos puede romper invariantes de cualquier tienda basada en Bukkit. El mensaje comercial se difiere y vuelve a consultar la cancelación, pero no es un registro durable de transacciones.

## Skins y archivos

- `npcs.yml`: configuración original; las skins con textura/firma explícitas tienen prioridad y se mantienen.
- `skins.yml`: caché por NPC de skins originalmente configuradas por nombre. Necesita que LibsDisguises resuelva correctamente la skin al menos una vez. Una caída de red previa a esa primera resolución no puede producir una textura válida.
- `/mdvnpc skin <id> <nombre>` fuerza una nueva resolución, incluso con el mismo nombre. El cambio manual del nombre invalida por comparación; borrar la textura explícita sin cambiar el nombre puede reutilizar una caché previa.
- `shops.yml`: ofertas, con referencia categoría/ID/cantidad para MMOItems y snapshot de Bukkit/Paper para otros objetos.
- `*.bak`: copia anterior. Los reemplazos intentan ser atómicos; en sistemas sin soporte se utiliza sustitución normal. No hay garantía contra fallo físico de disco ni transacción conjunta entre archivos.
- `shop-recovery/<uuid>-<npc>-<página>.yml`: último borrador fallido de esa página, con original y celdas modificadas. La página en el nombre es base cero. Es recuperación manual de administración: comparar con las ofertas vigentes y recrear las celdas en el editor. No se carga automáticamente ni se entrega ningún ítem.

La caché corrupta hace fallar el inicio con diagnóstico; no se sobrescribe silenciosamente. La recarga lee y valida los archivos antes de sustituir las definiciones activas. Al eliminar un NPC, la eliminación de `npcs.yml` y de `shops.yml` sigue siendo una operación de dos archivos: un fallo entre ambos puede dejar ofertas huérfanas, recuperables en las copias. No crea un NPC ni un pago gratuito.

## MMOItems y personalización

Los ítems reconocidos se guardan solo por **categoría, ID y cantidad**. Se regeneran en cada apertura; referencias idénticas se resuelven una vez durante esa apertura. Una actualización de MMOItems aparece al volver a abrir la tienda, no en una ventana que ya estaba abierta. Las ofertas editadas desde MDVNPC sí cierran las ventanas anteriores.

Si MMOItems está instalado pero desactivado, se bloquea la captura de nuevas plantillas. Si su API es incompatible, se informa y no se guarda un snapshot por error. Las referencias ya guardadas cuyo ítem no puede generarse no se ofrecen al jugador y aparecen como barrera en el editor; si no se tocan, conservan su referencia original.

Cuando MMOItems **no está instalado en absoluto**, no hay API para identificar con fiabilidad objetos antiguos de ese plugin: se tratan como objetos personalizados. Por eso, al editar tiendas con MMOItems debe estar instalado y funcionando. No se certificó ninguna versión concreta del JAR de MMOItems en este entorno.

Los costos MMOItems exigen la plantilla generada para esa apertura. Un objeto antiguo, mejorado, con gemas, durabilidad o NBT distinto puede ser rechazado. Los resultados aleatorios se fijan al abrir, no se sortean nuevamente por cada compra. Otros plugins de objetos custom se almacenan como snapshot y no se actualizan por su ID. Paper es responsable de serializar todos sus componentes; el test de repositorio utiliza un adaptador, no demuestra compatibilidad con todos los NBT externos.

## CPU, RAM y disco

La mirada y los diálogos conservan **una tarea compartida** con frecuencias independientes, consultando jugadores cercanos al NPC, sin recorrer todos los jugadores por cada NPC y sin forzar carga de chunks. Se omiten consultas de proximidad para diálogos sin líneas. Si ambos intervalos son coprimos, el reloj base puede despertar cada tick, aunque no busque jugadores hasta que corresponda.

La captura de skins añade una única tarea temporal a 20 ticks, solo mientras existen resoluciones pendientes y hasta 60 intentos por captura; finaliza al quedar vacía. Un trabajador agrupa las escrituras de skins; no escribe por frame ni por giro de cabeza. Los guardados de tienda/NPC son síncronos y solo al editar; **serializan el archivo completo**, así que tiendas muy grandes o discos lentos pueden producir una pausa al guardar. No se reescribe el archivo en cada compra. El apagado espera hasta cinco segundos al trabajador de skins.

La RAM de sesiones crece con jugadores que comercian y ofertas resueltas por ventana; 900 ofertas por tienda tienen un costo apreciable y no conviene abrirlas masivamente. Las búsquedas y rotaciones crecen con NPC activos y jugadores cercanos. Se limpian sesiones al cerrar/desconectar, cooldowns de comercio durante mantenimiento y estados al recargar. LibsDisguises/PacketEvents añaden sus propios costos de entidades, tracking y paquetes.

Los cooldowns se mantienen en memoria durante la sesión; desconectarse o recargar los reinicia. No son límites económicos persistentes ni mecanismos contra abuso de reconexión.

No se midieron TPS, MSPT, asignaciones ni consumo bajo carga real. La evaluación de economía es de arquitectura, no un benchmark. Como inicio, usar `look-update-interval-ticks: 10` o `20`, radio de mirada reducido y `rotation-threshold-degrees: 3`. Medir en el servidor con su cantidad real de NPC/jugadores antes de bajar a 1–5 ticks.

## WorldGuard y compatibilidad

Se mantiene la excepción original de spawn para aldeanos propios marcados durante su creación. `worldguard-spawn-bypass: false` permite respetar todos los bloqueos. La API Bukkit no identifica qué plugin canceló un evento: si otro plugin cancela en el mismo tramo que WorldGuard, la excepción activada puede quitar esa cancelación. No modifica flags ni regiones, pero no puede atribuir la cancelación con certeza.

`shop-allow-cancelled-interaction: false` respeta por defecto las protecciones al abrir tiendas. Si tu lobby necesita el comportamiento 1.1.0, se puede establecer `true`, entendiendo la misma limitación de atribución. Las comprobaciones de distancia y permisos siguen aplicándose a la compra.

Se admite `/mdvnpc reload`. No se certifica recarga en caliente de las dependencias mediante herramientas de terceros. Los avisos de API obsoleta de Paper no impiden compilar en el objetivo 1.21.6.

## Validación y prueba pendiente en servidor

Resultado: **35 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS**. Se compiló con Maven 3.9.11 y Temurin Java 21. La suite combina JUnit, Mockito y MockBukkit 4.56.0. Incluye reglas originales, guardado/carga de skins, captura/vaciado, invalidación de capturas tardías, ambos pagos, permisos y distancia en sesión, revisión de ofertas, editor, recuperación, metadatos y conflicto de páginas. El log entregado registra el resultado exacto.

**Límite del simulador:** MockBukkit 4.56 no implementa `serializeStack` y su `isSimilar` compara solo material. `TestServer` implementa un adaptador YAML exclusivo de pruebas y el test de rechazo de objetos distintos provee explícitamente el contrato de comparación de metadatos. Las pruebas de compra inyectan sesiones para aislar la validación; no simulan paquetes ni la transferencia NMS. Estos auxiliares están solo en `src/test`, no dentro del JAR.

Antes de usarlo en producción, probar en copia del servidor: reinicio sin red con skins ya resueltas; actualización de un MMOItem y reapertura; un costo y dos costos; Shift con inventario lleno; variantes custom y objetos dañados; cierre/ESC/desconexión con entradas depositadas; dos jugadores comprando y dos administradores editando; edición/recarga mientras alguien compra; descarga de chunk; y las flags reales de WorldGuard. Verificar que cantidad pagada, resultado y devoluciones coincidan. No se realizaron estas pruebas de cliente/servidor aquí.

## Referencias técnicas consultadas

- [API de MerchantRecipe de Paper 1.21.6](https://jd.papermc.io/paper/1.21.6/org/bukkit/inventory/MerchantRecipe.html).
- [Evento PlayerPurchaseEvent de Paper 1.21.6](https://jd.papermc.io/paper/1.21.6/io/papermc/paper/event/player/PlayerPurchaseEvent.html).
- [PlayerDisguise en LibsDisguises 11.0.18](https://github.com/libraryaddict/LibsDisguises/blob/v11.0.18/plugin/src/main/java/me/libraryaddict/disguise/disguisetypes/PlayerDisguise.java).
- [MockBukkit](https://github.com/MockBukkit/MockBukkit), además de sus fuentes exactas 4.56.0 descargadas para diagnosticar límites del simulador.


