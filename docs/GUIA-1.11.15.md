# MDVNPC 1.11.15 — instalación de la corrección

Esta versión corrige el error de activación de 1.11.14:

```text
No implementation of the random number generator algorithm "L32X64MixRandom" is available
```

El constructor del pescador se ejecuta al iniciar las rutinas, incluso si todavía
no hay un pescador configurado. La fábrica de generadores anterior podía lanzar
esa excepción y abortar el arranque de todo el plugin. Ahora usa
`java.util.Random`, que pertenece al módulo básico de Java y no necesita ese
proveedor opcional. Los puntos de pesca siguen eligiéndose al azar.

1. Detén el servidor.
2. Sustituye `MDVNPC-1.11.14.jar` por `MDVNPC-1.11.15.jar` en `plugins`.
   Deja un solo JAR de MDVNPC en esa carpeta.
3. Inicia el servidor y comprueba que MDVNPC se activa.

No hay cambios de formato en la configuración ni en los datos de NPC.
Se conservan las tiendas, las estaciones del herrero, los puntos del pescador,
el bote y la navegación. Consulta [la guía de 1.11.14](GUIA-1.11.14.md) para
configurar esas animaciones. Se mantiene el requisito de Java 21 del proyecto.
