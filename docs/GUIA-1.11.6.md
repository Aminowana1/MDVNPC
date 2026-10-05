# MDVNPC 1.11.6 — pisos sin regresiones

- Conserva gravedad en los NPC base.
- Las rutas normales vuelven a comenzar con FOLLOW_RANGE 16 y sólo usan hasta 32.
- Un destino realmente en otra planta puede ampliar 16 -> 24 -> 32 -> 48 -> 64.
- Una ruta parcial que termina justo debajo/encima de cama o puesto se rechaza y se amplía en vez de caminar contra la pared.
- El rango ampliado queda retenido sólo después de detectar ese callejón vertical.
- Se restaura el movimiento estable para esquinas, stairs, bloques completos y diagonales.
- Mud, dirt path y otras superficies de altura parcial pueden asentar los pies gradualmente sin descartar la ruta si el borde anterior todavía estorba.
