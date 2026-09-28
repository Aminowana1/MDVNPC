# MDVNPC 1.4.2 — corrección de RoutineLookTest

El registro de GitHub Actions aportado por el usuario confirma que 1.4.1 compiló tanto el plugin como las pruebas. Maven se detuvo al ejecutar las pruebas: 90 ejecutadas, 3 errores, todos en RoutineLookTest.

La preparación de esa clase construía `ActiveNpc` con `definition = null`. Desde 1.4.0, RoutineLook consulta la definición para aplicar el rasgo inquieto. Los NPC reales se crean con una definición, pero esta prueba anterior a los rasgos no la proporcionaba.

Corrección: las pruebas crean ahora una definición válida mediante NpcParser, con rasgo `none`. Se conservan sus cuatro comprobaciones y se añade una quinta para comprobar que inquieto reduce el intervalo de miradas sin empezar a mirar o mover el brazo inmediatamente. Usa límites de tiempo deterministas, sin depender de un valor aleatorio concreto.

No se modificó código de producción, no se omitieron pruebas y `build.yml` sigue intacto. La versión Maven es 1.4.2. Los avisos de API obsoleta, SLF4J y Mockito del registro no fueron la causa del fallo.

Validación de esta entrega: revisión del código y del registro, versión XML, comparación del fuente de producción y del workflow con 1.4.1 e integridad del ZIP. No se ejecutaron Maven ni las pruebas localmente, conservando la indicación de entregar solo fuente sin compilar. El nuevo resultado de `clean verify` queda pendiente de GitHub Actions; no se presenta como una compilación ya aprobada.
