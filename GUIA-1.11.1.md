# MDVNPC 1.11.1 — Nombres y desplazamientos

## Nombres como antes

El nombre vuelve a mostrarse con el sistema nativo de LibsDisguises que usaba
MDVNPC 1.9. Se conserva el nombre completo, los colores, el título y el ajuste
`name-visible` de cada NPC. No se modifica la configuración de LibsDisguises.

Se elimina el TextDisplay independiente que seguía al NPC y todas las
modificaciones de altura del nombre al sentarse o dormir. Los antiguos
`routines.name-offset-seated-y` y `routines.name-offset-sleeping-y` quedan
ignorados aunque todavía estén en tu `config.yml`.

Los rótulos antiguos marcados por MDVNPC se limpian al arrancar o al cargar
entidades de sus chunks. La invisibilidad reforzada de los asientos permanece.
El ajuste `routines.seat-offset-y` sigue funcionando y conserva su valor
predeterminado de `0.5`; regula el asiento, no el nombre.

## Caminatas y cambios de actividad

La recuperación de una rutina sin jugadores deja de saltar a un destino donde
ya hay un jugador. Solo permite recolocar un NPC fuera de la zona de observación
de jugadores tanto en su posición actual como en su destino. Los espectadores
también cuentan para impedir esos saltos.

La guarda usa al menos 96 bloques y, en el origen, comprueba también quién está
recibiendo la entidad según Paper. Este margen no amplía el radio de activación
configurado: un NPC fuera de ese radio puede permanecer suspendido sin recolocarse
mientras alguien aún lo observa.

Cuando se observa al NPC, los cambios de Caminar a Sentarse, Dormir o Trabajar
siguen la ruta Paper hasta el destino. Entrar o salir de una silla/cama solo
permite el ajuste local necesario para la pose; una pose desplazada lejos de su
punto no puede devolver al NPC mediante un salto largo.

La hora actual sigue eligiendo el goal al reanudar una rutina. Si el jugador
ya observa el destino y el NPC sigue suspendido lejos, la recuperación no lo
teletransporta frente al jugador: reanudará la ruta al activarse su zona.
Los NPC que reaparecen tras cargar un chunk o reiniciar usan el horario actual,
como antes.

## Compatibilidad y rendimiento

No hay que borrar `config.yml`, recrear NPC ni editar los goals de Thurg.
Se mantienen alternativas aleatorias, diálogos, comandos, tiendas, atención por
goal, músicos, canciones, gestos y baile exclusivo del rasgo Fiestero.

No se añaden tareas por NPC ni escaneos globales por tick. Se retira el seguimiento
del nombre independiente. Las comprobaciones de presencia y observación son
locales y se reutiliza el reloj compartido de rutinas y su presupuesto de rutas.
La presencia se comprueba cada segundo. Las recuperaciones pendientes se reintentan
como máximo cada cinco segundos; una recuperación completada se recuerda durante
ese horario. Los ajustes de altura al iniciar una ruta siguen la velocidad del
NPC, en lugar de corregirse mediante un salto de hasta un bloque.
No se han medido CPU, RAM o TPS de un servidor real.

## Actualizar y verificar

La entrega contiene solo el ZIP de fuentes. Compila con Java 21 y
`mvn --batch-mode --no-transfer-progress clean verify`, o usa el workflow existente
de GitHub. Apaga el servidor, sustituye el JAR generado y arranca conservando
`plugins/MDVNPC/`.

Paper/Purpur 1.21.6, LibsDisguises 11.0.18 y PacketEvents 2.14.0.
El resultado de compilación y pruebas se incluye en `dist/VERIFICACION.txt`.
Las pruebas automatizadas verifican lógica y llamadas de las APIs; la apariencia
visual necesita una comprobación con un cliente real de Minecraft.
