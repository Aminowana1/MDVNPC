# MDVNPC 1.2.0 — rutinas

Base: ZIP 1.1.2 entregado por el usuario. Paper/Purpur 1.21.6, Java 21, LibsDisguises 11.0.18 y PacketEvents 2.14.0. No requiere Multiverse: lee el reloj del mundo Bukkit correspondiente, también si Multiverse lo administra. MMOItems y WorldGuard siguen siendo opcionales.

## Instalar

Apaga el servidor, respalda `plugins/MDVNPC/` y sustituye solamente el JAR. Conserva `npcs.yml`, `shops.yml`, `skins.yml` y `config.yml`. Los parámetros nuevos tienen valores predeterminados dentro del JAR: no hace falta borrar tu configuración.

No se activan rutinas en NPC existentes hasta añadirles un goal. Los NPC sin rutina conservan su comportamiento anterior. Los comandos requieren `mdvnpc.admin`.

## Ejemplo completo

Primero crea el NPC, si todavía no existe:

```text
/mdvnpc create herrero Steve shop &6Herrero
```

Ejecuta cada comando y termina su selección antes de pasar al siguiente:

```text
/mdvnpc routine herrero goal 1 dormir 22:00 07:00 2.4
```

Haz clic en una cama. Se registra su cabecera. Desde las 22:00 camina hasta ella; cuando llega se acuesta. A las 07:00 se levanta. Si el camino demora, ese viaje forma parte del horario.

```text
/mdvnpc routine herrero goal 2 caminar meta 2.4
```

Clic izquierdo en bloques del suelo para marcar puntos. Clic derecho para guardar. Esta caminata se ejecutará al comenzar el horario del siguiente goal (trabajo), antes de atender. Al alcanzar el último punto pasa al siguiente goal.

```text
/mdvnpc routine herrero goal 3 trabajo 07:00 13:00 2.4
```

Haz clic en el suelo de su puesto. Se guarda también la dirección hacia la que mirás. La tienda y los comandos por interacción solo funcionan cuando llegó al puesto, durante su horario. Al terminar, se bloquean las compras y se cierra la tienda mediante el flujo normal de Paper. Los administradores pueden seguir editando con Shift + clic derecho o `/mdvnpc shop herrero`.

```text
/mdvnpc routine herrero goal 4 sentarse 13:00 15:00 2.4
```

Clic izquierdo en una o varias stairs normales (no invertidas); clic derecho confirma. Elige una silla disponible, camina hasta ella y se sienta. Ocasionalmente muestra comida o bebida con gesto, partículas y sonidos. Al finalizar se levanta.

```text
/mdvnpc routine herrero goal 5 caminar aleatorio 15:00 20:00 2.4 20
```

Marca puntos con clic izquierdo y confirma con derecho. Elige entre los puntos marcados dentro de un radio de 20 bloques desde su posición; si no hay ninguno, elige el más cercano. No genera destinos nuevos al azar por todo el terreno. Si hay alternativas, evita elegir el punto donde ya está.

```text
/mdvnpc routine herrero goal 6 caminar ciclo 20:00 22:00 2.4
```

Marca puntos y confirma. Recorre la lista en orden y vuelve al primero hasta las 22:00. Entonces empieza de nuevo el horario de dormir.

## Selección por chat y edición

También podés omitir ambas horas:

```text
/mdvnpc routine herrero goal 1 dormir
```

El chat pide primero la hora de inicio, después la de fin y finalmente el lugar. Escribe `cancelar` en cualquier momento o usa `/mdvnpc routine cancelar`. Las selecciones vencen a los diez minutos y se descartan al desconectarte. No se modifica el bloque al seleccionarlo.

Volver a configurar el mismo número reemplaza ese goal después de confirmar. Si otro administrador lo cambió durante tu selección, la escritura se rechaza para no pisar su cambio.

```text
/mdvnpc routine herrero list
/mdvnpc routine herrero status
/mdvnpc routine herrero delete 6
/mdvnpc routine herrero enable false
/mdvnpc routine herrero enable true
```

`rutina` también funciona como alias de `routine`. Tipos en inglés: `sleep`, `walk`, `sit`, `work`; modos `target`, `random`, `cycle`.

Velocidad: **0.2 a 6 bloques por segundo**. Radio aleatorio: **1 a 128 bloques**. Máximo 100 números de goal por NPC y 128 puntos por goal. Los puntos deben pertenecer al mundo del NPC.

## Reglas de horario

- Horas `00:00` a `23:59`, también se admite `7` para las 07:00. Tick 0 de Minecraft corresponde a las 06:00.
- Inicio incluido y fin excluido. `22:00–07:00` cruza la medianoche.
- Inicio y fin iguales significan todo el día. Los horarios de un mismo NPC no pueden superponerse.
- Fuera de los horarios queda esperando sin habilitar su tienda. Completa las 24 horas si querés una agenda continua.
- Los goals `meta` no tienen horario. Se encadenan, por número, **antes del siguiente goal con horario**, al empezar la franja de este. El último puede enlazar con el primero del día.
- Si solo hay goals meta, recorre la secuencia una vez por día de Minecraft y luego espera. Al recargar se reinicia el progreso de esa secuencia.
- El horario tiene prioridad: si un viaje se demora hasta la siguiente franja, cambia de actividad. No desplaza los horarios posteriores.
- Reiniciar, recargar o saltar la hora recalcula la actividad correspondiente; no guarda cada paso ni reproduce todas las actividades omitidas.

## Día y noche de duración distinta

Opcional, por mundo:

```text
/mdvnpc clock world5 30 15
/mdvnpc clock world5 status
/mdvnpc clock world5 off
```

El ejemplo hace que 06:00–18:00 dure 30 minutos reales y 18:00–06:00 dure 15, **a 20 TPS**. Con lag, el tiempo real se alarga. Las rutinas siguen usando el reloj del mundo; no usan el reloj de la computadora.

Al activarlo, MDVNPC administra `doDaylightCycle` en ese mundo. Al desactivarlo o apagar correctamente el plugin restaura el valor anterior. `clock-state.yml` permite recuperar ese valor tras una interrupción. No borres este archivo mientras el reloj esté activado. Evita que otro plugin controle simultáneamente la velocidad del mismo reloj. Los cambios externos de hora se respetan como saltos del reloj. No se admiten nombres de mundo con puntos en este gestor opcional.

## Consumo y configuración

Agrega este bloque a `config.yml` solamente si querés sustituir los valores predeterminados:

```yaml
routines:
  default-speed: 2.4
  random-radius: 20
  activation-range: 48
  movement-interval-ticks: 2
  max-search-nodes: 2048
  search-nodes-per-tick: 160
  cached-routes: 32
  seat-offset-y: 0.0
  seated-consumption: true
  meal-min-seconds: 30
  meal-max-seconds: 90
  beer-type: CONSUMABLE
  beer-id: CERVEZA
```

`beer-type` debe ser el ID interno de la categoría MMOItems; si tu categoría se llama internamente `CONSUMIBLES`, cambia ese valor. Si no se puede obtener la cerveza, utiliza una poción visual. Se muestran también bistec cocinado, manzana y sopa. Son objetos cosméticos temporales: no generan ítems recogibles ni aplican efectos de MMOItems. Se restaura el objeto anterior de la mano al terminar.

Una sola tarea compartida controla las rutinas. Las búsquedas se reparten y tienen un máximo de nodos; el presupuesto se aplica por actualización de rutina (cada dos ticks por defecto), con comprobación de tiempo entre lotes pequeños. La caché global guarda hasta 32 rutas; se valida el paso siguiente y se descarta una ruta obstruida. No se persiste la caché en disco.

No se fuerzan ni generan chunks por las rutinas. Sin jugadores no espectadores dentro del radio de activación, el NPC suspende movimiento y acciones. Si su destino horario está cargado y tiene observadores, puede recuperarse allí cuando nadie observa su posición anterior. Al cargar una zona se reconstruye la actividad por horario. No mantiene una simulación física completa de todo el pueblo vacío.

## Límites de esta entrega

- Navegación terrestre conservadora, con pasos de hasta un bloque. No vuela, nada, trepa escaleras de mano ni cruza portales. Obstáculos, vallas, geometría compleja o caminos muy largos pueden exigir puntos intermedios. No atraviesa paredes como recurso para destrabarse.
- El desplazamiento se implementa con pequeños pasos controlados y la IA del aldeano apagada. Hay que verificar su suavidad con tu latencia y cliente; no se ha certificado visualmente en un servidor real.
- Abre puertas de madera accesibles y cierra las que abrió cuando la entrada queda libre. No fuerza puertas de hierro cerradas. Con WorldGuard respeta `use` como no miembro y las cancelaciones de `EntityInteractEvent`. No interpreta automáticamente todas las políticas de otros plugins de protección.
- Las puertas que un jugador dejó abiertas no se cierran. Si la zona se descarga o el proceso se corta antes del cierre, una puerta puede quedar abierta; no se carga el chunk para cerrarla.
- Camas y sillas se reservan entre estos NPC; no hay integración universal con otros plugins de sillas. Se bloquea dormir sobre una cama reclamada por un NPC. Para sentarse se usa una entidad auxiliar invisible, retirada al terminar.
- La altura de la silla, la pose acostada y la animación de consumo necesitan verificación visual con LibsDisguises y tu cliente. `seat-offset-y` permite ajustar la silla.
- No hay benchmark real de RAM/CPU con 25 NPC. Los límites de trabajo y caché están implementados; la estimación previa de memoria no es un resultado medido.

## Prueba recomendada en una copia del servidor

Configura primero un NPC con cama, dos sillas, un puesto y un recorrido corto con puerta. Cambia la hora entre cada franja y comprueba llegada, postura, regreso de la mano después de comer y cierre de la tienda con ítems en sus casillas. Después recarga, reinicia y entra/sale del área. Finalmente prueba los 20–25 NPC y compara Spark con la misma zona y los mismos jugadores cargados.

El ZIP incluye Maven, pruebas y GitHub Actions. `build.yml` conserva el patrón `target/MDVNPC-*.jar`: no hay que editarlo por versión.
