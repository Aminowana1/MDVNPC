# MDVNPC 1.11.12

Sustituye el JAR anterior por MDVNPC-1.11.12.jar y reinicia el servidor.
Conserva la configuración existente. Los valores por defecto son:

```yaml
routines:
  stuck-hop:
    enabled: true
    delay-seconds: 3
    height: 0.6
    distance: 1.0
```

Mientras camina hacia cama, silla, trabajo, meta, ciclo o destino aleatorio,
el NPC inicia el impulso de salto tras el intervalo sin avance horizontal.
No necesita suelo bajo los pies, espacio libre previo ni un destino seguro.
La mirada al iniciar determina la dirección horizontal; el pitch no la inclina.

Los bloques recortan el desplazamiento al chocar: una pared frena el avance
y un techo frena la subida. No se busca un arco alternativo ni se reduce
preventivamente la altura. Si está completamente encerrado, consume el intento
aunque físicamente no pueda desplazarse y vuelve a intentarlo tras el intervalo.
Los eventos que cancelan el movimiento siguen respetándose.

Las vallas, puertas de valla cerradas y slabs/trampillas/bloques directamente
sobre ellas siguen impidiendo cruzar horizontalmente, aunque la valla esté
por debajo. Un NPC ya incrustado puede intentar salir por el lado más cercano;
no se le permite entrar más profundamente en un bloque ni atravesarlo.

Un salto puede terminar sobre un hueco: en la siguiente actualización se
reanuda la recuperación gradual de suelo existente. No carga chunks para
ejecutar el impulso. Los NPC dormidos, sentados, trabajando en el puesto,
pausados o realizando otra actividad siguen excluidos.

La altura admite 0.05..4 bloques, la distancia 0.05..4 y el intervalo 1..60
segundos. La altura/distancia son el impulso solicitado; el movimiento real
depende de las colisiones que encuentre durante su ejecución.
