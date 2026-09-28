# Revisión 1.3.1

Ver [CAMBIOS-1.3.1.md](CAMBIOS-1.3.1.md): esta revisión no se compiló ni ejecutó pruebas. Lo que sigue es el reporte histórico de la base suministrada, no una validación nueva.

# Auditoría y límites — MDVNPC 1.3.0

## Cambios de esta revisión

Se añadió un editor gráfico de rutinas accesible con `/mdvnpc routine|rutina|rutinas <npc>`. Permite listar y crear goals, cambiar horarios y velocidad, reasignar cama/sillas/puesto/recorrido, escoger explícitamente el modo de caminar, ajustar el radio aleatorio y administrar diálogos por goal.

Los diálogos de rutina son ahora independientes por goal. Los `WORK` creados antes de 1.3.0 heredan el diálogo global del NPC mientras no se editen, evitando perder la configuración existente. El diálogo global no se ejecuta en paralelo cuando hay rutina, por lo que no duplica mensajes. Los temporizadores se reinician al cambiar de goal o reanudar una actividad después de una suspensión.

Cuando un NPC con rutina no está trabajando, el clic derecho no abre tienda ni ejecuta comandos y puede responder con `interaction.unavailable`, con cooldown por jugador/NPC. Este aviso también puede mostrarse en una región que haya cancelado la interacción original, sin usar esa cancelación para ejecutar la tienda o comandos.

La persistencia específica de cada NPC vive en `NPCs/<id>/`: `npc.yml`, `routines.yml`, `shop.yml`, `skin-cache.yml` y, si hiciera falta, `shop-recovery/`. Los archivos globales antiguos (`npcs.yml`, `routines.yml`, `shops.yml`, `skins.yml`) se importan de forma automática y se renombran a `*.legacy-backup` tras una migración válida.

Las escrituras se limitaron al NPC realmente modificado: cambiar un reloj ya no reescribe todos los `routines.yml`, y editar un NPC no reescribe los `npc.yml` de los demás.

## Validación de esta revisión

- Se revisaron manualmente los flujos de creación/edición, migración, borrado y recarga, incluidos los casos de compatibilidad con diálogos `WORK` antiguos.
- Se añadieron/actualizaron pruebas de persistencia para la nueva estructura por NPC y para los diálogos por goal.
- Se comprobó que `.github/workflows/build.yml` permanece sin cambios y continúa ejecutando `mvn clean verify` con Java 21 y publicando `target/MDVNPC-*.jar`.
- El entorno de trabajo actual no dispone de Maven ni de las dependencias Paper/LibsDisguises en caché, por lo que aquí no fue posible ejecutar `mvn clean verify` completo.
- Se ejecutó `javac --release 21 -proc:none` como control de sintaxis. Como era esperable, se detuvo por dependencias externas ausentes; no aparecieron errores sintácticos de Java en los archivos modificados.

## Validación histórica de 1.2.0

La base 1.2.0 había pasado una compilación/suite de 61 pruebas, una ejecución posterior de 31 pruebas seleccionadas y seis pruebas adicionales del editor de rutinas por chat/clic. Cuatro pruebas experimentales de poses con Mockito no pudieron iniciarse porque ese arnés no aportaba `com.mojang.authlib.GameProfile`; las poses requieren prueba dentro de un cliente real.

## Límites relevantes

No se ejecutó un servidor real de Minecraft ni se probaron clientes conectados en esta revisión. Por eso, además del `clean verify` de GitHub, conviene probar en un servidor de staging: abrir el nuevo GUI, reconfigurar cada tipo de punto, cambiar un WALK entre los tres modos, verificar mensajes de cada goal y comprobar la migración con una copia de la carpeta de producción.

La navegación conserva desactivada la IA del aldeano. Usa pequeños desplazamientos controlados y geometría terrestre conservadora, con caché limitada en RAM, sin guardar posiciones por paso ni forzar chunks. El progreso exacto de una caminata META no se persiste tras una descarga de zona o reinicio.

Las puertas siguen la política `use` de WorldGuard y cancelaciones de interacción; no hay compatibilidad universal con todos los plugins de protección. El consumo sentado sigue siendo visual. La autorización de una compra se vuelve a comprobar aunque la tienda ya esté abierta.

Ver [GUIA-RUTINAS-1.3.md](GUIA-RUTINAS-1.3.md) para el flujo nuevo y [docs/AUDITORIA-1.1.2.md](docs/AUDITORIA-1.1.2.md) para la auditoría histórica de tiendas.
