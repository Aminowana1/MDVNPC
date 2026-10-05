# MDVNPC 1.11.10 — vallas y salto de recuperación

Esta entrega parte de `MDVNPC-main.zip` (versión 1.11.9) y conserva sus límites
configurables de subida y bajada, incluido `routines.max-climb-height: 1.3`.
Paper sigue calculando las rutas. Los cambios se concentran en la ejecución de
movimiento y en la recuperación de NPC que están intentando caminar.

## Vallas

Todas las variantes de valla, incluida la valla de ladrillos del Nether, se
consideran barreras para las subidas y para el nuevo salto. No se pueden usar
como apoyo ni como destino transitable. La comprobación conserva la altura
inicial de los pies durante la subida, de modo que elevar al NPC no permite
cruzar después por encima de la valla. También respeta muros y puertas de
valla cerradas; una puerta de valla abierta conserva su paso real.

Una ruta que ya está sobre un puente con suelo verdadero por encima de una
valla inferior puede continuar por ese puente.

## Recuperación al caminar

Tras 3 segundos sin acumular al menos 0.05 bloques de desplazamiento real,
el NPC intenta un salto corto en la dirección horizontal de su mirada. Por
defecto alcanza 0.6 bloques de altura sobre el punto inicial y avanza 1 bloque.
La animación se reparte entre varias actualizaciones, con comprobación de
colisiones y del movimiento real en cada una.

Se aplica al desplazamiento de WALK meta/ciclo/aleatorio, WORK y los accesos
de cama y silla. Se desactiva cuando el NPC ya duerme, está sentado o trabaja
en su puesto, y durante pausas, baile, reacciones o bebida. Los cambios de
acceso y reintentos del mismo goal no reinician el contador de inmovilidad.

Si sigue quieto, intenta de nuevo tras el intervalo configurado. El salto se
rechaza si no hay apoyo seguro, el techo es demasiado bajo, hay obstáculos,
peligros o chunks descargados. No atraviesa una valla para recuperarse. Las
puertas que la lógica existente permite abrir siguen usando esa misma lógica.
Un salto iniciado vuelve a dejar el cálculo de ruta en manos de Paper.

## Configuración

```yaml
routines:
  stuck-hop:
    enabled: true
    delay-seconds: 3
    height: 0.6
    distance: 1.0
```

`delay-seconds` acepta segundos enteros de 1 a 60. `height` y `distance`
aceptan valores de 0.05 a 4 bloques. Los valores altos también respetan
las barreras y la colisión. Las configuraciones antiguas que no contienen
estas claves usan los valores predeterminados. Reinicia o recarga la
configuración mediante el mecanismo habitual del plugin para aplicar cambios.

## Archivos principales

- `RoutineTerrain`: `recoveryHopFits`, `recoveryHopLanding`, apoyo y selección
  de superficies; clasificación de vallas y puertas de valla.
- `RoutineNavigator`: `startHop`, `advanceHop`, cancelación, comprobación del
  arco y protección del desplazamiento después de una subida.
- `RoutineService`: detección de inmovilidad, fases elegibles, repetición y
  carga de configuración.
- `Settings` y `config.yml`: validación y valores predeterminados.

Consulta `VALIDACION-1.11.10.md` para los resultados y el alcance de las pruebas.
