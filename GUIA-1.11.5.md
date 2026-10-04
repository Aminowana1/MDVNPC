# MDVNPC 1.11.5 — Navegación entre plantas y gravedad

## Corrección principal

Los destinos situados en otra planta ya no aceptan como útil una ruta parcial que termina casi directamente debajo o encima de la cama/puesto de trabajo. Ese patrón hacía que el NPC se pegara a una pared o caminara unos bloques de ida y vuelta sin buscar la escalera o entrada real.

El navegador ahora:

- mantiene la búsqueda normal barata en el mismo piso;
- inicia con más alcance cuando el destino está cerca en X/Z pero claramente en otra Y;
- amplía el `FOLLOW_RANGE` de Paper de forma adaptativa hasta `16 / 24 / 32 / 48 / 64`;
- detecta la "sombra vertical" del destino: endpoint cercano en X/Z pero todavía en otra planta;
- descarta esa ruta parcial antes de caminar hacia la pared y vuelve a buscar con más alcance;
- conserva el alcance ampliado durante ese viaje para no volver a caer en el mismo callejón vertical;
- al máximo alcance, marca la ruta como agotada en vez de oscilar indefinidamente.

Esto se aplica al navegador compartido por meta, ciclo, aleatorio, cama, trabajo y desplazamientos de baile.

## Gravedad

Los aldeanos base de MDVNPC ahora nacen con gravedad activada (`setGravity(true)`) manteniendo `AI=false` y `Aware=false`. Si pierden soporte físico pueden caer normalmente en vez de permanecer suspendidos en una coordenada Y inválida. Las sillas auxiliares siguen sin gravedad porque son soportes invisibles de montaje.

## Compatibilidad

Se conservan las correcciones de 1.11.3/1.11.4 para puertas, esquinas, stairs, slabs, alfombras, dirt path, mud y huecos de un bloque.
