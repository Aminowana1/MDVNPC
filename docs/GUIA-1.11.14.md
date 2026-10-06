# MDVNPC 1.11.14 — Pescador y golpes de Herrero

La categoría **Pescador** conserva la tienda, sus intercambios y el horario de
Trabajo. El NPC pesca desde un punto de tierra, camina al muelle, navega en un
bote hasta un punto de agua, pesca allí y regresa al muelle. Después baja,
desaparece el bote y comienza otro ciclo.

El **Herrero** emite ahora un `clink` metálico corto en cada gesto de golpe,
mediante `BLOCK_CHAIN_HIT`. El sonido del martilleo sale únicamente junto al
gesto: no se reproduce entre golpes ni al terminar la sesión del yunque.
Los efectos propios de fundición y caldero conservan sus sonidos.

## Configurar un Pescador

1. Abre `/mdvnpc edit <id>` y elige **Trabajo del NPC → Tienda**.
2. En **Rutinas y horarios**, configura un goal **Trabajo** con horario y
   su punto normal. Este será el puesto base donde atiende si no puede pescar.
3. Abre `/mdvnpc pescador <id>` y selecciona **Pescador**. También puedes
   entrar desde **Categoría y estaciones** en el editor del NPC.
4. Pulsa **Configurar los tres pasos** para marcar, en orden, un punto de
   pesca en tierra, el muelle y un punto de pesca en bote.

La configuración requiere `mdvnpc.admin`. Los puntos deben estar en el mundo
del NPC. La selección dura hasta diez minutos y se cancela con `cancelar`,
al salir, al perder el permiso o al recargar el plugin. No modifica ni consume
bloques. Elegir Vendedor u otra categoría conserva los puntos y los intercambios.

## Marcar los puntos y la dirección

- **Pesca en tierra:** mira hacia el agua donde debe caer la boya y haz clic
  izquierdo o derecho en el bloque del suelo. Se guarda el centro de ese
  bloque, a un bloque sobre su Y, y la dirección horizontal de tu mirada.
- **Muelle:** marca un bloque del suelo en la orilla mirando hacia el agua
  por donde saldrá el bote. Deja sitio para que el NPC llegue caminando y
  espacio de agua suficiente delante para colocar y abordar el bote.
- **Pesca en bote:** apunta a la superficie del agua mirando en la dirección
  del lanzamiento y haz clic derecho. Se puede seleccionar con clic al aire;
  el editor busca el agua en la mirada hasta **16 bloques**. Se guardan el
  centro del bloque de agua, su superficie y la dirección horizontal.

La dirección se guarda al hacer el clic; vuelve a marcar el punto si quieres
cambiarla. Al pescar, la boya cae a **4.5 bloques** hacia esa dirección por
defecto. Deja agua y espacio libre para el lanzamiento en ambos tipos de punto.

Puedes guardar varios puntos de tierra y varios de bote, hasta **32 por lista**.
Abre **Pesca en tierra** o **Pesca en bote** para verlos. **Añadir punto** inicia
una selección nueva; un clic derecho sobre un punto elimina sólo ese punto.
El muelle es único: clic para cambiarlo y clic derecho para quitarlo. Si otro
administrador cambia los puntos mientras seleccionas, la selección anterior
no sobrescribe esos cambios.

## Preparar el agua para el bote

El bote es un bote nativo de Minecraft y respeta la geometría de las orillas,
paredes, vallas, losas y puentes. Prepara una superficie de agua conectada, al
mismo nivel, desde el muelle hasta los puntos de pesca en bote. Usa agua fuente;
las plantas acuáticas habituales y las columnas de burbujas son transitables
cuando dejan la superficie y el espacio del bote libres.

Su anchura es **1.375 bloques**. Un canal recto de dos bloques puede permitir
el paso por el centro; un canal de un bloque entre paredes no tiene anchura
suficiente. En las esquinas deja más espacio. La navegación comprueba también
un volumen de **2.6 bloques de altura** desde el bote para su pasajero.

Los puntos de bote deben permitir colocar el bote completo sobre agua:
selecciona agua suficientemente apartada de la orilla y de obstáculos.
También debe haber espacio junto al muelle para salir del bote y volver al
punto de tierra. Los bloques protegidos cancelados por otros plugins no se
usan para marcar puntos de tierra ni el muelle.

La búsqueda acuática está limitada a un alcance de **96 bloques** y **4096
nodos** por consulta. Sólo consulta chunks ya cargados. Para recorridos mayores
o lagos muy laberínticos, coloca los puntos más cerca del muelle. El plugin
no carga chunks a la fuerza para ejecutar la animación.

## Ciclo y efectos

1. Tras llegar al puesto de Trabajo, elige un punto de tierra al azar y llega
   caminando mediante el navegador de rutinas de Paper.
2. Sostiene una caña, lanza la boya en la dirección guardada y pesca durante
   **45 segundos**. La boya y el sedal se muestran con objetos visuales y
   partículas; los lanzamientos se repiten durante esa fase.
3. Camina al muelle, aparece un bote y se monta. Elige al azar un punto de
   pesca en bote y navega hasta allí siguiendo una ruta de agua libre.
4. Pesca desde el bote durante otros **45 segundos**, usando la dirección
   guardada en ese punto.
5. Regresa navegando al muelle, baja al suelo, se retira el bote y vuelve
   a empezar con un punto de tierra al azar.

Los viajes se suman a los tiempos de pesca. La boya se representa con
`ItemDisplay` y el sedal con partículas `DUST`; la caña se muestra en la mano.
Estos efectos son cosméticos: no generan capturas ni recursos, no consumen
cebo y no cambian los intercambios de la tienda.

## Ajustes globales

En `config.yml`:

```yaml
fisherman:
  shore-seconds: 45
  boat-seconds: 45
  cast-distance: 4.5
  travel-timeout-seconds: 60
  boat-speed: 3.0
  retry-seconds: 30
```

`shore-seconds` y `boat-seconds` son los tiempos de cada fase de pesca.
`cast-distance` es la distancia horizontal de la boya. `boat-speed` se mide
en bloques por segundo. `travel-timeout-seconds` limita cada desplazamiento,
incluido el regreso al muelle. `retry-seconds` establece la espera en el
puesto base antes de volver a intentar una animación que ha fallado.

## Atención, regreso y limpieza

Si faltan puntos, no hay agua válida para el lanzamiento o el bote, no existe
acceso caminando, se descarga una zona necesaria o no encuentra una ruta
acuática transitable, la animación cede a la atención normal en el puesto
base. El regreso depende de una ruta accesible y de la recuperación habitual
de las rutinas.

Mientras un jugador tiene abierto el comercio, el Pescador pausa la animación
y atiende sin desplazar al NPC, también si está en el bote. Al cerrar el
comercio libera los efectos y regresa por el muelle o al puesto base según
la fase; antes de comenzar otra animación, recupera su puesto normal.

Si un jugador golpea al Pescador mientras está en el bote, el NPC conserva
su asiento: pausa la animación durante la reacción y después reanuda la pesca
o la navegación en el mismo bote. Los golpes en tierra y las bebidas ofrecidas
interrumpen el ciclo para ceder la pose al sistema correspondiente y aplicar
el regreso habitual.

Al finalizar Trabajo, cambiar de goal, recargar, retirar al
NPC o suspenderlo por falta de observadores, se limpian la boya, el sedal y
el bote propios de la animación. La mano y la orientación se restauran cuando
siguen perteneciendo a esta animación.

## Comprobación visual pendiente

La apariencia de la caña, boya, sedal, montaje y salida del bote debe comprobarse
en un servidor Paper con LibsDisguises. Prueba el ciclo completo con varios
puntos, abre y cierra la tienda durante la navegación, golpea al NPC montado
y confirma que conserva el bote y reanuda su actividad, revisa un canal de dos
bloques y uno de un bloque, retira temporalmente el agua y cambia el horario.
Confirma que se limpian los efectos y que el NPC vuelve a atender en su puesto.
