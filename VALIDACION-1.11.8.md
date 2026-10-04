# Validación MDVNPC 1.11.8

## Estado actual: v4 pendiente de CI

La revisión v3 fue compilada y ejecutada en CI con Java 21 mediante:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Resultado de v3:

- Tests ejecutados: 458
- Fallos: 2
- Errores: 0
- Omitidos: 0
- Los únicos fallos restantes fueron:
  - `RoutineNavigatorTest.halfSlabBetweenSameHeightPaperNodesIsSteppedWithoutReplanningOrCircling`
  - `RoutineNavigatorTest.thinCarpetBetweenSameHeightPaperNodesDoesNotCreateAReplanLoop`

La v4 añade una recuperación conservadora para risers parciales omitidos por los nodos de Paper. Solo entra si el replay normal falla y solo permite una elevación física de hasta 0.51 bloques; por tanto no convierte bloques completos/muros en escalones falsos.

La v4 debe validarse nuevamente con `mvn clean verify` antes de marcarse como verificada.
