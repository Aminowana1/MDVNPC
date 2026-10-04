# Estado de validación de MDVNPC 1.11.8

Esta revisión parte de la fuente 1.11.7 verificada (449/449 pruebas en su entrega).
Se modificaron `RoutineNavigator`, `RoutineTerrain` y la validación de puntos de
`RoutineCommands`, y se añadieron regresiones específicas del bug de desniveles.

En este entorno no está instalado Maven y no existe una caché local de las dependencias
del proyecto, por lo que **no se afirma que la suite 1.11.8 haya sido ejecutada**. Se hizo
una comprobación sintáctica con `javac` sobre la fuente modificada: no aparecieron errores
de sintaxis; la resolución completa de tipos externos no puede terminar sin Paper/MockBukkit
y las demás dependencias Maven.

El workflow `.github/workflows/build.yml` sigue disponible con Java 21. Validación
recomendada:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Después del build, probar en Purpur/Paper 1.21.6 al menos:

- full block -> slab -> full block, ida y vuelta;
- path/mud/soul sand intercalados;
- carpet en medio del camino y carpet como punto WORK;
- stairs y esquinas/giros sobre pavimento mixto;
- meta, ciclo, aleatorio, WORK y viaje a cama;
- NPC ya existente al que se le añade carpet/bottom slab bajo los pies.

## CI v2 y correcciones v3

La segunda ejecución externa llegó a compilar las 63 clases principales y las 50 clases de tests,
y ejecutó 458 pruebas. Terminó con 7 fallos y 2 errores. La revisión v3 corrige las causas observadas:

- `walkingSurface` conserva la aceptación histórica de materiales sólidos y usa colisión para superficies finas;
- el destino nativo de Paper cae de vuelta al destino original si no existe un nodo local resoluble;
- la tolerancia vertical de waypoint se redujo para obligar a visitar la altura física real de path/mud/soul sand/carpet;
- si una ruta de Paper omite el nodo intermedio de slab/carpet, el replay detecta el obstáculo al primer choque horizontal y sube desde el borde seguro en vez de descartar la ruta.

Esta v3 queda pendiente de una nueva ejecución de `clean verify`; no se marca como verificada hasta obtener `BUILD SUCCESS`.
