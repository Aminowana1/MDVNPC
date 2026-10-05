# MDVNPC 1.11.3 — Pathfinding adaptativo

Esta entrega se concentra únicamente en la navegación de las rutinas. Mantiene Paper como calculador de rutas y la IA del aldeano desactivada; MDVNPC continúa reproduciendo los waypoints con movimiento controlado.

## Qué corrige

- NPC que se quedaban mirando una pared al ir a una cama o puesto lejano.
- Rutas que necesitaban rodear una casa para encontrar una única puerta en el lado opuesto.
- Atascos al rozar esquinas durante un waypoint diagonal.
- Subida y bajada por stairs, slabs y bloques completos.
- Alfombras y otras superficies finas con caja de colisión.
- Pequeños desniveles y huecos de hasta un bloque de profundidad que Paper considere transitables.
- Viajes largos que antes podían reiniciarse a los 60 segundos aunque el NPC siguiera avanzando.
- Camas/asientos con varios lados localmente válidos donde el primer acceso elegido no tenía ruta real.

## Cómo funciona ahora

1. La primera búsqueda usa `FOLLOW_RANGE=16`.
2. Si Paper devuelve una ruta parcial que no progresa, un endpoint repetido o no encuentra ruta, la siguiente búsqueda amplía a 24 y después 32.
3. Una ruta parcial ya no se rechaza sólo porque durante un rodeo termine temporalmente más lejos del objetivo.
4. `canReachFinalPoint()` distingue una ruta completa de una parcial.
5. Un fallo local de movimiento recalcula rápido; un chunk descargado espera un intervalo intermedio; una ruta realmente agotada conserva el reintento lento.
6. Si una cama/asiento tiene varios accesos, MDVNPC agota la búsqueda ampliada del lado actual antes de probar el siguiente.

## Terreno

El soporte de los pies usa una huella más estrecha que la caja corporal. Esto evita que una pared que apenas toca el hombro del NPC sea interpretada como un suelo más alto. Para stairs/bloques se usa una sonda direccional delante del NPC, por lo que sigue elevándose antes de penetrar el riser.

Cuando una diagonal de Paper roza una esquina, MDVNPC intenta un paso corto por X o Z, manteniendo el mismo límite de velocidad, en vez de descartar inmediatamente toda la ruta.

Las superficies finas con colisión (por ejemplo carpet) pueden ser soporte aunque Bukkit no las marque como bloques sólidos completos.

## Viajes y rendimiento

La búsqueda normal sigue siendo de 16 bloques. Los rangos 24/32 sólo aparecen ante una ruta difícil, para no encarecer todos los NPC. El presupuesto compartido de inicios de pathfinding por update se conserva.

El límite de seguridad de la rutina ahora representa 60 segundos **sin movimiento real**. Cada avance del NPC renueva ese margen, de modo que un viaje largo a velocidad baja puede finalizar normalmente.

## Pruebas recomendadas en Purpur 1.21.6

1. Cama a 30–60 bloques con calle abierta.
2. Cama dentro de una casa cuya única puerta esté en el lado opuesto al NPC.
3. Cama un piso arriba usando stairs y un piso abajo usando stairs.
4. Puesto de trabajo en las mismas tres situaciones.
5. Ruta con slab inferior/superior, alfombra y escalera de esquina.
6. Ruta junto a una esquina de pared que fuerce un waypoint diagonal.
7. Subir un bloque completo y bajar un bloque completo.
8. Cruzar un pequeño hueco/desnivel de un bloque que el pathfinder vanilla considere caminable.
9. Repetir con velocidad mínima configurada (0.2 b/s) y comprobar que el viaje no se reinicie mientras avanza.
10. Comprobar que puertas, sillas, baile, tiendas y diálogos sigan funcionando como en 1.11.2.

## Actualización

Compila con Java 21 y coloca únicamente el JAR de esta versión en `plugins/`. No requiere cambios de configuración ni migraciones de datos desde 1.11.2.
