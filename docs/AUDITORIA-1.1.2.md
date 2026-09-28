# MDVNPC 1.1.2 — Corrección del editor

Base: MDVNPC-1.1.1-source.zip adjuntado por el usuario. El problema era de diseño: el editor copiaba el objeto del cursor sin descontarlo y cancelaba Shift/arrastre. Ahora las primeras tres filas se comportan como un contenedor editable.

## Comportamiento

- Clic izquierdo/derecho: Paper ejecuta colocar, retirar, dividir pilas e intercambiar, descontando el cursor normalmente. El plugin ya no clona manualmente estos clics.
- Arrastre: permitido entre celdas editables y el inventario del jugador. Si toca controles o una referencia no disponible, se cancela todo el arrastre sin aplicar movimientos parciales.
- Shift + clic: mueve entre las 27 celdas editables y el inventario del jugador; combina pilas y conserva el sobrante si no hay espacio. Nunca coloca objetos en la fila de navegación.
- Teclas numéricas, intercambio con mano secundaria y descarte: comportamiento nativo en celdas editables. Los controles quedan protegidos.
- Doble clic: reúne objetos de las celdas editables y del inventario, sin llevarse botones.
- Guardar ya no reabre innecesariamente la ventana. Al navegar se guarda y se abre la siguiente página fuera del evento; los movimientos quedan bloqueados durante ese cambio.

## Persistencia y columnas incompletas

Los objetos dejados en el editor quedan guardados al cerrar, guardar o cambiar de página. No se devuelven automáticamente al jugador: se pueden retirar del menú con clic o Shift, como de un contenedor. Son muestras de configuración; la tienda continúa vendiendo cantidades ilimitadas y no usa esas pilas como stock.

Una columna incompleta se guarda en `shops.<npc>.drafts`, en el mismo archivo y la misma escritura que las ofertas. No se publica como intercambio. Al volver a abrir mantiene las celdas restantes; el objeto retirado no reaparece por rechazar un guardado incompleto. Al completar resultado y costo 1, pasa a ser una oferta; al vaciarla, desaparece tanto de ofertas como de borradores.

La versión lee las ofertas 1.1.1 sin conversión manual. No borrar `shops.yml`. No volver a una versión anterior mientras haya borradores: esas versiones no conocen el campo `drafts`.

Se conserva la referencia categoría/ID de MMOItems si la celda no cambió. Una referencia no disponible aparece como barrera bloqueada para impedir extraer un objeto ficticio. Clic derecho con cursor vacío elimina esa referencia. Los resultados MMOItems siguen regenerándose desde su plantilla al abrir; el editor no es un almacén para conservar mejoras aleatorias o NBT individual de MMOItems.

## Validación y límites

Se agregaron pruebas de movimiento Shift en ambos sentidos, apilado, inventarios llenos, protección de botones, arrastre permitido/bloqueado, doble clic, retiro y reapertura, columnas incompletas, conversión borrador/oferta, referencias MMOItems no disponibles y cambio diferido de página. Se mantienen las pruebas de skins, sesiones comerciales y persistencia anteriores.

**Resultado: 44 pruebas aprobadas, 0 fallos, 0 errores y 0 omitidas; BUILD SUCCESS.** El resultado exacto está en el log de validación entregado. Las pruebas usan MockBukkit: los movimientos nativos de clic/arrastre se verifican como delegados y, cuando hace falta comprobar persistencia posterior, se simula explícitamente su resultado. No son pruebas con un cliente Minecraft conectado. Las operaciones Shift y doble clic propias del plugin sí se ejecutan en las pruebas. Se conserva el adaptador YAML de pruebas, con los límites explicados en `docs/AUDITORIA-1.1.1.md`.

El guardado de la configuración no es una transacción conjunta con el archivo de jugador de Minecraft. Una caída del proceso o fallo de disco puede desincronizarlos; ante un error de guardado se muestra un aviso y se intenta conservar recuperación administrativa. No se afirma inmunidad a duplicación por caída del servidor ni por otros plugins que modifiquen inventarios. Este cambio corrige la copia deliberada del cursor y los bloqueos de interacción reportados.

La auditoría histórica de la versión anterior está en `docs/AUDITORIA-1.1.1.md`. Esta corrección reemplaza sus indicaciones de editor de copias y de rechazo de columnas incompletas. Las skins, validaciones de compras, diálogos, Maven y GitHub Actions se conservan.

