# Auditoría y límites — MDVNPC 1.2.0

## Cambios

Rutinas en clases separadas: modelo y horarios, persistencia atómica, editor por chat/clic, búsqueda de caminos incremental, navegación, puertas, poses/consumo cosmético y reloj por mundo. Integración con ciclo de vida, protección de NPC y validación de compras. Correcciones previas de tiendas, MMOItems y skins conservadas.

Los NPC con rutina habilitan sus interacciones de juego únicamente cuando están trabajando, dentro del horario y en el puesto. La autorización de una compra se vuelve a comprobar aunque la tienda ya esté abierta. La edición administrativa sigue disponible fuera del trabajo.

La navegación conserva desactivada la IA del aldeano. Usa pequeños desplazamientos controlados y geometría terrestre conservadora, no el navegador nativo de Minecraft. Las rutas se calculan automáticamente, se guardan en una caché limitada en RAM y se revalidan antes del desplazamiento. No se guardan posiciones por paso ni se fuerzan chunks.

## Validación realizada antes de la petición de entregar solo fuente

- Primera compilación y suite de 61 pruebas: aprobadas, sin fallos, errores ni omitidas.
- Una ejecución posterior de 31 pruebas seleccionadas de rutinas, persistencia, navegación, restricciones de compra y reloj: aprobadas, sin omitidas. Incluye pruebas ya contadas en la ejecución anterior; las cifras no se suman.
- Seis pruebas adicionales del editor de rutinas por chat/clic: aprobadas. Cubren selección, confirmación, cancelación, permisos, mundo y conflicto entre administradores.
- Cuatro pruebas experimentales de poses con Mockito no pudieron iniciarse porque el entorno de pruebas no proporciona `com.mojang.authlib.GameProfile`, requerido para instrumentar las clases de LibsDisguises. Se retiró ese arnés experimental; no se presenta como verificación de animaciones. Las poses siguen pendientes de prueba en un cliente real.
- Después de la petición del usuario no se ejecutaron nuevas compilaciones ni pruebas. La última revisión fuente completa no tiene una ejecución final de `clean verify`.
- GitHub Actions permanece idéntico al del ZIP 1.1.2; Maven define la versión 1.2.0 y el workflow usa el patrón de artefacto independiente de versión.

## Límites relevantes

No se ejecutó un servidor real de Minecraft ni se probaron clientes conectados. Las pruebas de navegación usan una geometría simulada; no certifican todas las formas de bloques, animación de caminar, montaje en stairs, alineación con camas o efectos de LibsDisguises. El editor de tiendas conserva los límites de MockBukkit documentados en la auditoría 1.1.2.

No se midió la RAM ni CPU real con 20–25 NPC. Se limitan nodos, caché, actividad distante y frecuencia de búsquedas. El presupuesto temporal se comprueba entre lotes de búsqueda, no es una garantía dura del tiempo máximo del tick. El total del servidor depende de jugadores, chunks, protecciones y otros plugins.

Rutinas y reloj se recuperan por el horario actual. El progreso exacto de las caminatas meta y las rutas en caché no se persisten. La recuperación fuera de la vista puede recolocar al NPC en un destino ya cargado. No se cargan zonas para completar una actividad.

Las puertas obedecen la política `use` de WorldGuard para no miembros y cancelaciones del evento de interacción; no hay compatibilidad universal con otros protectores. Las que un jugador dejó abiertas no se cierran. Una descarga de zona o interrupción del proceso puede dejar abierta una puerta que el NPC abrió.

El consumo sentado es visual, sin efectos reales ni ítems recogibles. La cerveza usa categoría interna e ID configurables; el valor predeterminado es CONSUMABLE/CERVEZA, con poción visual como alternativa. La altura de asiento es ajustable. No hay integración con reservas de sillas de otros plugins.

Ver [guía](GUIA-RUTINAS.md) para configurar, interpretar las caminatas meta y realizar la prueba dentro del juego. La auditoría histórica de tiendas está en [docs/AUDITORIA-1.1.2.md](docs/AUDITORIA-1.1.2.md).