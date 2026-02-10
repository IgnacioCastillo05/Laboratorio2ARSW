# Laboratorio2ARSW
Laboratorio de programación concurrente: condiciones de carrera, sincronización y colecciones seguras.

## Reglas del juego (resumen)
- N serpientes corren de forma autónoma (cada una en su propio hilo).
- Ratones: al comer uno, la serpiente crece y aparece un nuevo obstáculo.
- Obstáculos: si la cabeza entra en un obstáculo hay rebote.
- Teletransportadores (flechas rojas): entrar por uno te saca por su par.
- Rayos (Turbo): al pisarlos, la serpiente obtiene velocidad aumentada temporal.
- Movimiento con wrap-around (el tablero “se repite” en los bordes).

## Cómo ejecutar

```bash
mvn clean verify
mvn -q -DskipTests exec:java -Dsnakes=4
```

- `-Dsnakes=N` → inicia el juego con **N** serpientes (por defecto 2).
- **Controles**:
  - **Flechas**: serpiente **0** (Jugador 1).
  - **WASD**: serpiente **1** (si existe).
  - **Espacio** o botón **Action**: Pausar / Reanudar.

---

## Explicación técnica (reporte)
El reporte de lo trabajado a lo largo del laboratorio se encuentra en el documento explicacion.md
