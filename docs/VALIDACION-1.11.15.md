# Validación de MDVNPC 1.11.15

Fecha: 2026-10-06. Compilación con Maven 3.9.9 y Java 21.0.12.1.

## Reproducción de la causa

Un programa independiente ejecutado con `--limit-modules=java.base` comprueba
que `jdk.random` no está disponible y reproduce exactamente la excepción de
`RandomGenerator.getDefault()` referida a `L32X64MixRandom`.
En el mismo proceso, `new Random()` completa 1000 selecciones `nextInt(32)`.
Se repite el resultado con la lista de módulos empleada en las pruebas del
proyecto. El mensaje del servidor no permite concluir por sí solo si falta el
módulo o si su proveedor no es visible para el cargador del plugin; el cambio
elimina la dependencia del proveedor en ambos casos.

## Pruebas y compilación

La JVM de pruebas excluye el módulo opcional `jdk.random` mediante:

```text
--limit-modules=java.se,jdk.unsupported,jdk.attach,jdk.management,jdk.zipfs,jdk.compiler
```

- Pruebas iniciales del pescador, sus listeners e integración de rutinas y
  del herrero: **93**, sin fallos, errores ni omisiones.
- Compilación final `package`: **851 pruebas en 63 suites**, sin fallos,
  errores ni omisiones. Resultado **BUILD SUCCESS**.
- Nueva prueba del constructor público: la fábrica anterior lanza la excepción
  del proveedor; el controlador corregido se construye e inicia la pesca en la
  orilla sin consultar esa fábrica.

## Alcance

La comparación con 1.11.14 confirma que sólo cambia un archivo de producción:
`FishermanController.java`, en la creación del generador aleatorio. Cambian
también su prueba, la versión Maven y la documentación. Los demás archivos
de código de producción y recursos permanecen idénticos a la entrega anterior.

El JAR se verifica contra las clases y recursos de esta compilación; el ZIP
de fuentes excluye JAR, clases y carpetas de compilación. No se ha iniciado
el servidor Purpur del usuario; las pruebas automatizadas y la reproducción
del proveedor no sustituyen esa comprobación dentro de su servidor.
