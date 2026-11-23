# Choque entre NPC

Desde la 1.16.0 los NPC no se atraviesan al caminar: entre dos NPC en el mismo piso queda siempre al menos `min-distance` (1 bloque por defecto, medido entre sus centros). Vale para los NPC con rutina y para los acompañantes.

## Qué hace un NPC que se encuentra con otro

1. **Lo rodea.** Gira hacia su derecha y lo pasa por un lado. Dos NPC que vienen de frente se esquivan sin chocar.
2. **Lo aparta, si está quieto de pie.** Un NPC parado sin nada que hacer (entre dos puntos de su paseo, esperando, un acompañante quieto) se desplaza un poco, como un empujón. Nunca se mueve a un NPC sentado, durmiendo, trabajando, bailando, en barca, reaccionando a un golpe o saludo, ni a un NPC sin rutina.
3. **Se acerca y espera.** Si no hay sitio para rodearlo, se detiene justo al borde.
4. **Busca otro camino.** Tras medio segundo bloqueado, busca un rodeo corto por otra calle o puerta.
5. **Se aparta en los pasillos.** Si dos NPC se encuentran de frente en un pasillo de un bloque de ancho, el que empezó su viaje más tarde camina hasta el hueco más cercano (una puerta, un ensanche, el final del pasillo), espera a que pase el otro y sigue.

Esperar a otro NPC no cuenta como atasco: no recalcula su ruta una y otra vez ni hace el salto para destrabarse.

### Casos especiales

- **Destino ocupado:** si otro NPC está justo en su destino, quedarse a su lado cuenta como haber llegado.
- **Puntos colocados juntos:** si pusiste un punto de rutina a menos de 1 bloque de un NPC sentado, trabajando o sin rutina, el que llega se coloca en su punto como antes.
- **NPC solapados:** si dos NPC aparecen uno dentro del otro (al cargar la zona, tras un teletransporte), se separan poco a poco, unos centímetros cada medio segundo.
- **Pisos distintos:** un NPC en el piso de arriba no bloquea al de abajo.
- **Paseos aleatorios:** no eligen un punto en el que ya hay alguien de pie.
- **Caídas y saltos para destrabarse:** no se frenan por la distancia.

### Pasillos sin salida

Si dos NPC se bloquean en un pasillo cerrado sin ningún hueco, se quedan esperando: por defecto nunca se atraviesan. Lo revisan cada cierto tiempo, cada vez más espaciado, por si algo cambia. Para que en ese caso acaben cruzándose, pon `pass-through-after-seconds` mayor que 0.

## Configuración

En `config.yml`:

```yaml
npc-spacing:
  enabled: true
  min-distance: 1.0              # 0..3; distancia mínima entre centros; 0 = se atraviesan como antes
  push-idle: true                # se puede apartar a los NPC quietos de pie
  pass-through-after-seconds: 0  # 0..120; 0 = nunca se atraviesan
  companions: true               # los acompañantes también chocan al caminar
```

Tras editarlo, ejecuta `/mdvnpc reload`. Si falta la sección, se usan estos valores.

`/mdvnpc` muestra «esperando a que pase otro NPC» en el estado de un NPC bloqueado.

## Rendimiento

Cada paso consulta las casillas de 2×2 bloques que lo rodean: alrededor de 1 µs con 300 NPC cerca. Un NPC que espera sin que nada cambie se salta el cálculo de su paso. Las búsquedas de rodeo y de hueco son cortas (256 y 200 nodos como máximo) y usan el mismo presupuesto por actualización que las rutas normales.
