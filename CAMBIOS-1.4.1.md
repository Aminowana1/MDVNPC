# MDVNPC 1.4.1 — selector de rasgos en el editor

Abre `/mdvnpc routine manolito` (también `/mdvnpc rutina manolito`) y pulsa **Rasgo del NPC**, en la fila inferior, a la derecha de Estado.

El menú muestra Alcohólico, Lector, Glotón, Inquieto, Ruidoso y Sin rasgo. Cada opción explica su efecto. Un clic izquierdo o derecho asigna y guarda inmediatamente; el rasgo actual aparece en verde con una marca. Sin rasgo elimina la asignación. Volver regresa a la misma página del editor.

Funciona sin rutina activada y sin goals. Cada NPC sigue teniendo como máximo un rasgo. Se mantienen los comandos de 1.4.0 y los diálogos/cooldown configurados al cambiar de rasgo. La edición de diálogos y cooldown de cerveza sigue realizándose en `npc.yml`, como se explica en RASGOS-1.4.0.md.

El selector requiere `mdvnpc.admin`, revalida el permiso antes de guardar y comprueba que el jugador siga en ese menú. Los iconos son botones: arrastre, shift-click, teclas numéricas y doble clic quedan cancelados, sin intercambio de objetos. Las aperturas y el guardado se programan después del evento de clic. No se modifica el comportamiento del inventario de intercambios.

Guardado en el repositorio existente y recarga de NPC como el comando de asignación. No agrega tareas periódicas. Implementación del selector separada en `trait/TraitEditor.java`. `build.yml` permanece intacto.

Revisión: integración de apertura/registro, permisos, selección única, conservación del catálogo y navegación; estructura del fuente, versión XML e integridad del ZIP. No se compiló, no se ejecutaron pruebas Java ni se verificó en servidor, siguiendo la indicación de entregar solo fuente. Los informes anteriores corresponden a sus respectivas versiones.
