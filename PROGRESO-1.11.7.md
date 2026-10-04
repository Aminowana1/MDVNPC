# Progreso guardado — MDVNPC 1.11.7

Fecha: 4 de octubre de 2026. Entrega solicitada: sólo ZIP del proyecto fuente.

## Punto de partida

`MDVNPC-1.11.6-pisos-gravedad-regresiones-corregidas.zip`

SHA-256: `A37ED886D669C4C8D27D62BDDC52165CF564E5F186FD7529F581541AB0C0DDFE`.

## Implementado

- Subidas por etapas sin que el asentamiento deshaga la altura ganada.
- Paper como calculador, alcance inicial 16 y ampliación adaptativa hasta 64.
- Continuación de parciales útiles, incluidos los que mejoran altura hacia el goal.
- Historial acotado de endpoints, progreso real, detección de ciclos y teleports sin desplazamiento.
- Reintentos separados y presupuesto compartido de consultas.
- Colisión de muros/vallas sobresalientes y de hojas de puertas abiertas.
- Alternativa calculada por Paper al rechazar una puerta cerrada sin permiso.
- Caché acotada de vóxeles durante una actualización, con cambios de estado comprobados.
- Progreso acumulado para velocidad mínima y viajes largos.
- Recuperación controlada de pérdida de suelo, con IA desactivada, para rutinas y NPC estáticos observados.
- Conservación de reservas, poses, baile, permisos y reglas existentes de tiendas y músicos.

Producción: sólo se modificaron `RoutineNavigator.java`, `RoutineTerrain.java` y
`RoutineService.java`; se añadió `RoutineGravity.java`. Los otros archivos de
`src/main`, incluidos los recursos de configuración, coinciden con la base auditada.
La versión del proyecto se actualizó a 1.11.7.

## Verificación hasta este punto

- Las fuentes principales y las pruebas compilan con Java 21.
- Última ejecución completa terminada: 450 casos, 448 aprobados, dos fallos de
  compatibilidad del comportamiento lateral de WORK para NORMAL/SHOP.
- Se restauró esa regla original y se retiró la prueba nueva que la contradecía.
  Las pruebas originales de esa compatibilidad se conservaron sin cambios.
- En la ejecución completa pasaron las 77 comprobaciones de navegación
  (25 de navegador/puertas, 43 de terreno y 9 de búsqueda), las 11 de gravedad
  y los viajes de 160 bloques a cama/trabajo por encima y por debajo del inicio.
- Se inició una nueva verificación completa sobre la restauración final, pero
  se interrumpió para entregar rápidamente sólo el fuente, según lo solicitado.
  **Queda pendiente terminar esa verificación completa; no se afirma que los
  449 casos del estado final hayan pasado.**
- No se ejecutó un servidor Paper real con clientes y LibsDisguises.

## Continuación

Ejecutar desde la raíz del proyecto, con Java 21 y Maven:

```text
mvn --batch-mode --no-transfer-progress clean verify
```

Después, revisar especialmente `MusicWorkRecoveryTest`, `RoutineGravityTest`,
`RoutineNavigatorTest` y las tres clases `Navigation*AuditTest`. Las pruebas
usan respuestas controladas de Paper; comprobar también las rutas reales en
servidor antes de dar por validadas todas las construcciones de la ciudad.

La entrega conserva `pom.xml`, fuentes, recursos, pruebas y documentación;
excluye JAR, clases compiladas, carpeta `target`, dependencias y logs de ejecución.
