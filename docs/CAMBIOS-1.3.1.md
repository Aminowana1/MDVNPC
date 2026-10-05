# MDVNPC 1.3.1

Actualización sobre `MDVNPC-1.3.0-source-fixed.zip`. Se conserva la estructura `NPCs/<id>/`, el editor gráfico, los diálogos por goal, las tiendas y las skins de esa base.

## Lectura sentado

Entre las acciones ocasionales puede escoger un libro en lugar de comida o bebida. Lo mantiene en la mano entre 12 y 25 segundos y baja ligeramente la mirada. El libro no activa la animación de comer ni genera sonidos/partículas de comida. Al terminar, cambiar de goal, suspenderse o retirarse el NPC, se restaura el objeto anterior de la mano. No se generan objetos recogibles.

Por defecto, elige leer con un 30 % de probabilidad en cada oportunidad de actividad, usando las pausas existentes de `meal-min-seconds`/`meal-max-seconds`. Si desactivas el consumo pero mantienes la lectura, puede seguir leyendo.

## Miradas ocasionales

Durante el desplazamiento y sentado, realiza miradas breves de aproximadamente 1.25–3 segundos, con pausas aleatorias de 8–18 segundos entre ellas. Los giros se suavizan y limitan; se mantiene la orientación corporal del recorrido o del asiento. Los temporizadores se conservan entre puntos cortos del recorrido.

Sentado puede elegir un jugador visible, con línea de visión y **a un máximo de 3 bloques de distancia**. Se comprueba también la distancia exacta después de la consulta de proximidad, y deja de seguirlo si se aleja. Respeta los filtros existentes de invisibilidad/espectadores. Durante la lectura prioriza mirar el libro. Dormir y trabajar conservan su comportamiento propio.

## Suspensión y cama

La versión anterior retiraba la pose y llevaba al NPC al punto de acceso cuando desaparecía el último observador. Ahora mantiene la postura y la reserva de cama/silla mientras continúe el mismo horario. Se paran las acciones cosméticas y las búsquedas de caminos durante la suspensión.

Al regresar un jugador, se comprueban la cama, posición y metadatos de sueño, y se reaplica la postura. Mientras está activo se comprueba el sueño cada dos segundos; solo se corrige si se detecta una diferencia. El fin del horario, la retirada del mueble o la descarga de la entidad siguen liberando la postura. Al salir se limpia la inclinación de cabeza.

## Puertas

Además de cerrar las que abrió, puede cerrar **puertas de madera que ya estaban abiertas y por las que pasa su recorrido**. Espera a que no haya entidades vivas en la entrada. Revalida ambas mitades, el estado de redstone, WorldGuard y el evento de interacción antes de cerrar. No recorre el pueblo buscando todas las puertas abiertas ni fuerza puertas de hierro.

Si otra persona modifica la puerta, el NPC desaparece, la zona se descarga, la protección cancela la acción o el paso no queda libre en un minuto, deja de intentar cerrarla. La cola se limita a 256 puertas y no mantiene chunks cargados.

## Opciones nuevas

Los valores se aplican por defecto aunque conserves tu `config.yml`. Para cambiarlos, agrega estas claves **dentro del bloque `routines:` existente**, sin duplicarlo:

```yaml
  seated-reading: true
  reading-chance: 0.30
  reading-min-seconds: 12
  reading-max-seconds: 25
  occasional-looking: true
  glance-min-seconds: 8
  glance-max-seconds: 18
  close-preopened-doors: true
```

No hay comandos nuevos obligatorios ni cambios en tus goals. El editor, las conversaciones y la persistencia por NPC de 1.3.0 se mantienen. Maven pasa a 1.3.1; `.github/workflows/build.yml` permanece sin cambios.

## Validación y límites

Entrega exclusivamente fuente, **sin compilar ni ejecutar tests**, respetando la petición del usuario. Se revisaron las diferencias y el contenido del ZIP, y se añadieron pruebas de regresión en fuente para el ciclo de suspensión/sueño y las miradas. No se presentan esas pruebas como aprobadas.

La representación exacta de cabeza, libro y cama debe comprobarse en Minecraft con LibsDisguises. No se midió RAM/CPU. Los cambios reutilizan la tarea existente, no escriben por movimiento ni cargan chunks; las búsquedas de jugadores para elegir una mirada solo se realizan al iniciar un gesto sentado.

Prueba sugerida: deja un NPC durmiendo, aléjate y vuelve durante el mismo horario; repite después del amanecer. Con otro NPC sentado, observa una lectura completa y acércate/alejate del límite de 3 bloques. Haz pasar un NPC por una puerta de madera abierta, y repite con alguien parado en la entrada y con una protección que deniegue `use`.

Referencias de API revisadas: [rotación corporal de Paper 1.21.6](https://jd.papermc.io/paper/1.21.6/org/bukkit/entity/LivingEntity.html#setBodyYaw(float)) y [poses de LibsDisguises 11.0.18](https://github.com/libraryaddict/LibsDisguises/blob/v11.0.18/plugin/src/main/java/me/libraryaddict/disguise/disguisetypes/FlagWatcher.java).
