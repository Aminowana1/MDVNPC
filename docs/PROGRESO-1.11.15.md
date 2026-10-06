# Progreso guardado — MDVNPC 1.11.15

Base: entrega 1.11.14. La copia de esa versión se conserva sin modificar.

Cambio de producción: constructor público de
`src/main/java/com/mdvcraft/mdvnpc/work/FishermanController.java`.
Usa `new Random()` en lugar de `RandomGenerator.getDefault()` e importa
`java.util.Random`. La interfaz inyectable `RandomGenerator` se conserva
para pruebas y selección aleatoria de puntos.

Prueba añadida: `publicConstructorWorksWhenTheDefaultAlgorithmProviderIsUnavailable`
en `FishermanControllerTest`. Bloquea la fábrica anterior, construye el controlador
por la misma entrada usada al activar el plugin e inicia la pesca en la orilla.

La versión Maven pasa a 1.11.15. No cambian los recursos de configuración,
el pathfinding, las tiendas, el herrero ni el resto de la lógica del pescador.
Los resultados de compilación y pruebas figuran en
[la validación](VALIDACION-1.11.15.md).
