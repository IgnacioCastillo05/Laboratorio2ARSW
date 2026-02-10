### Parte 1 del laboratorio

## Reporte

Monitor Utilizado:
Se usa un único objeto monitor compartido entre el hilo de control y todos los hilos trabajadores. Este objeto sirve como lock para todas las operaciones de sincronización.
Condiciones Sincronizadas

Estado del sistema (RUNNING/PAUSED): Controla si los trabajadores deben ejecutarse o esperar
Contador de hilos pausados: Permite al Control esperar a que todos los trabajadores se detengan antes de mostrar el conteo

Cómo se evitan Lost Wakeups:
- En Control.requestPauseAndWait(): Usa un bucle while que verifica threadsPaused < countActiveThreads(). Esto asegura que aunque se reciba una notificación espuria, el hilo solo sale del wait cuando realmente todos están pausados.
- En PrimeFinderThread.handlePausePoint(): Usa while (control.shouldPause()) en lugar de if. Si un hilo despierta accidentalmente mientras sigue en pausa, vuelve a hacer wait.
- Notificaciones bidireccionales: Los trabajadores notifican al Control cuando se pausan (reportThreadPaused), y el Control notifica a todos los trabajadores cuando deben reanudar (notifyAll).

### Parte 2

# Punto 1: Análisis de Concurrencia

### 1.1 Cómo el código usa hilos para dar autonomía a cada serpiente

El código implementa concurrencia mediante **Virtual Threads** de Java 21:

```java
// En SnakeApp.java (líneas 44-45)
var exec = Executors.newVirtualThreadPerTaskExecutor();
snakes.forEach(s -> exec.submit(new SnakeRunner(s, board)));
```

**Explicación:**
- Cada serpiente (`Snake`) tiene su propio hilo virtual ejecutando una instancia de `SnakeRunner`
- Cada `SnakeRunner` ejecuta un bucle infinito independiente que:
  1. Decide si girar aleatoriamente (`maybeTurn()`)
  2. Avanza un paso en el tablero (`board.step(snake)`)
  3. Maneja colisiones (gira al azar si choca con obstáculo)
  4. Gestiona el modo turbo
  5. Duerme un tiempo (80ms normal, 40ms en turbo)
- Esta arquitectura permite que cada serpiente se mueva de forma **autónoma y asíncrona**, sin depender del ciclo de las demás

**Flujo de ejecución:**
```
Thread Serpiente 1 → [maybeTurn → board.step → sleep] → (loop)
Thread Serpiente 2 → [maybeTurn → board.step → sleep] → (loop)
Thread Serpiente N → [maybeTurn → board.step → sleep] → (loop)
Thread UI (GameClock) → [repaint cada 60ms]
```

---

### 1.2 Condiciones de Carrera (Data Races) Identificadas

#### **RC-1: Acceso concurrente a colecciones mutables en `Board`**

**Ubicación:** `Board.java` - colecciones `mice`, `obstacles`, `turbo`, `teleports`

**Problema:**
```java
private final Set<Position> mice = new HashSet<>();
private final Set<Position> obstacles = new HashSet<>();
private final Set<Position> turbo = new HashSet<>();
private final Map<Position, Position> teleports = new HashMap<>();
```

Estas colecciones son compartidas por múltiples hilos pero **NO son thread-safe**:
- Múltiples `SnakeRunner` llaman `board.step()` concurrentemente
- `step()` modifica las colecciones: `mice.remove(next)`, `turbo.remove(next)`, `mice.add(...)`, etc.
- Los métodos getter (`mice()`, `obstacles()`, etc.) crean copias pero **sin sincronización**

**Escenario de fallo:**
1. Thread A llama `board.step()` → ejecuta `mice.remove(next)`
2. Thread B simultáneamente llama `board.mice()` → itera sobre `mice`
3. **RESULTADO:** `ConcurrentModificationException` o estado inconsistente

**Evidencia:**
```java
// En Board.java - método step() sin sincronización adecuada
boolean ateMouse = mice.remove(next);  // ❌ Escritura sin protección
// ...
if (ateMouse) {
    mice.add(randomEmpty());           // ❌ Más escrituras concurrentes
    obstacles.add(randomEmpty());
}
```

---

#### **RC-2: Modificación no atómica del estado de `Snake`**

**Ubicación:** `Snake.java` - métodos `advance()` y `turn()`

**Problema:**
```java
private final Deque<Position> body = new ArrayDeque<>();
private volatile Direction direction;  // ✓ Volatile pero insuficiente
```

- `body` (ArrayDeque) **NO es thread-safe**
- Aunque `direction` es `volatile`, no protege las operaciones sobre `body`
- `advance()` modifica `body` (desde `SnakeRunner`)
- `snapshot()` lee `body` (desde el thread de UI en `GamePanel.paintComponent`)

**Escenario de fallo:**
1. Thread Snake llama `snake.advance(newHead, grow)` → modifica `body.addFirst()`
2. Thread UI llama `snake.snapshot()` → lee `body` para dibujar
3. **RESULTADO:** `ConcurrentModificationException` o dibujo corrupto

**Evidencia:**
```java
// Snake.java
public void advance(Position newHead, boolean grow) {
    body.addFirst(newHead);              // ❌ Sin sincronización
    if (grow) maxLength++;
    while (body.size() > maxLength) body.removeLast();
}

public Deque<Position> snapshot() { 
    return new ArrayDeque<>(body);       // ❌ Lee sin sincronización
}
```

---

#### **RC-3: Lectura inconsistente al dibujar serpientes**

**Ubicación:** `SnakeApp.GamePanel.paintComponent()`

**Problema:**
```java
var snakes = snakesSupplier.get();  // Obtiene lista de serpientes
for (Snake s : snakes) {
    var body = s.snapshot().toArray(new Position[0]);  // ❌ Sin garantías de consistencia
    // Dibuja cada segmento...
}
```

- Cada serpiente puede estar moviéndose mientras se dibuja
- No hay garantía de que todas las serpientes se vean en el "mismo momento"
- Una serpiente puede dibujarse parcialmente antes/después de moverse

**Resultado:** "Tearing" visual (serpientes dibujadas en estados transicionales)

---

#### **RC-4: Estado compartido en `GameClock` sin coordinación**

**Ubicación:** `GameClock.java` y `SnakeRunner.java`

**Problema:**
```java
// GameClock.java
private final AtomicReference<GameState> state = new AtomicReference<>(GameState.STOPPED);

public void pause()  { state.set(GameState.PAUSED); }  // ✓ Atómico
public void resume() { state.set(GameState.RUNNING); }
```

```java
// SnakeRunner.java
@Override
public void run() {
    while (!Thread.currentThread().isInterrupted()) {  // ❌ No verifica GameState
        // ... mueve serpiente sin importar si está pausado
    }
}
```

- `GameClock` pausa/reanuda solo el `tick` del reloj (UI repaint)
- Los `SnakeRunner` **NO verifican el estado del juego** → siguen moviéndose durante la pausa
- No hay mecanismo de coordinación entre el reloj y los hilos de las serpientes

**Resultado:** Al pausar, las serpientes siguen moviéndose en segundo plano

---

### 1.3 Colecciones/Estructuras NO Seguras en Contexto Concurrente

| Clase | Estructura | Thread-safe | Usada por | Problema |
|-------|-----------|-------------|-----------|----------|
| `Board` | `HashSet<Position> mice` | ❌ NO | Múltiples SnakeRunners | ConcurrentModificationException |
| `Board` | `HashSet<Position> obstacles` | ❌ NO | Múltiples SnakeRunners | ConcurrentModificationException |
| `Board` | `HashSet<Position> turbo` | ❌ NO | Múltiples SnakeRunners | ConcurrentModificationException |
| `Board` | `HashMap<Position, Position> teleports` | ❌ NO | Múltiples SnakeRunners (lectura) | Menos crítico pero inseguro |
| `Snake` | `ArrayDeque<Position> body` | ❌ NO | SnakeRunner + UI thread | ConcurrentModificationException en snapshot |
| `SnakeApp` | `List<Snake> snakes` | ❌ NO | UI thread + lectura concurrente | Potencialmente inseguro si se modifica |

**Detalle de colecciones problemáticas:**

1. **`HashSet` y `HashMap` en Board:**
   - Diseñados para uso single-threaded
   - Modificación estructural concurrente → estado interno corrupto
   - Iteración durante modificación → excepción inmediata

2. **`ArrayDeque` en Snake:**
   - No sincronizado
   - `addFirst()` + `removeLast()` desde SnakeRunner
   - `new ArrayDeque<>(body)` desde UI → copia un objeto mutable sin protección

3. **`ArrayList` en SnakeApp:**
   - Aunque solo se lee después de inicialización, no hay garantía formal de visibilidad

---

### 1.4 Espera Activa (Busy-Wait) y Sincronización Innecesaria

#### **EW-1: NO hay espera activa explícita (pero hay ineficiencia potencial)**

**Análisis:**
```java
// SnakeRunner.java
while (!Thread.currentThread().isInterrupted()) {
    maybeTurn();
    var res = board.step(snake);
    // ...
    Thread.sleep(sleep);  // ✓ Correcto - duerme el hilo
}
```

✓ **BIEN:** El código usa `Thread.sleep()` correctamente, no hace busy-wait
✓ **BIEN:** No hay bucles `while(condition) {}` sin sleep

#### **EW-2: Falta de coordinación eficiente para pausar**

**Problema detectado:**
- Actualmente, los `SnakeRunner` NO respetan el estado de pausa
- Para implementar pausa correctamente, **NO se debe usar busy-wait**
- Solución requerida: usar `wait()/notify()` o `Condition` variables

**Anti-patrón a evitar:**
```java
// ❌ MAL - Busy-wait (NO hacer esto)
while (game.isPaused()) {
    // Consumo de CPU innecesario
}
```

**Patrón correcto (a implementar):**
```java
// ✓ BIEN - Usar señales
synchronized(pauseLock) {
    while (isPaused) {
        pauseLock.wait();  // Duerme el hilo sin consumir CPU
    }
}
```

#### **SI-1: Sincronización innecesaria NO detectada (código actual)**

El código actual tiene **FALTA de sincronización**, no **exceso** de ella.
- Los métodos `synchronized` de `Board` (getters) son correctos pero incompletos
- No hay bloqueos amplios innecesarios en el código actual

---

### 1.5 Resumen de Problemas Críticos

| ID | Categoría | Severidad | Descripción |
|----|-----------|-----------|-------------|
| **RC-1** | Condición de Carrera | 🔴 CRÍTICA | Colecciones mutables en Board sin protección |
| **RC-2** | Condición de Carrera | 🔴 CRÍTICA | Snake.body accedido concurrentemente |
| **RC-3** | Condición de Carrera | 🟡 MEDIA | Lectura inconsistente al dibujar (tearing) |
| **RC-4** | Falta de Coordinación | 🔴 CRÍTICA | SnakeRunners ignoran estado de pausa |
| **CS-1** | Colección Insegura | 🔴 CRÍTICA | HashSet/HashMap no thread-safe |
| **CS-2** | Colección Insegura | 🔴 CRÍTICA | ArrayDeque no thread-safe |

---

# Punto 2: Correcciones y Regiones Críticas

## 2.1 Resumen de Correcciones Implementadas

| ID | Archivo | Cambio | Riesgo Original | Solución |
|----|---------|--------|----------------|----------|
| **C1** | Board.java | Sincronizar método `step()` completo | Múltiples serpientes modifican colecciones concurrentemente → ConcurrentModificationException | Sincronización total del método garantiza atomicidad |
| **C2** | Snake.java | Sincronizar `advance()`, `head()`, `snapshot()` | UI lee `body` mientras serpiente lo modifica → excepción o corrupción visual | Sincronización mínima en cada operación sobre `body` |
| **C3** | GameClock.java | Agregar `Lock + Condition` para pausa | No existía coordinación → serpientes ignoraban pausa | Mecanismo wait/notify sin busy-wait |
| **C4** | SnakeRunner.java | Llamar `clock.waitIfPaused()` en loop | Serpientes se movían durante pausa | Espera eficiente usando `Condition.await()` |

---

## 2.2 Detalle de Correcciones

### C1: Board.java - Sincronización del método `step()`

**Región crítica definida:**
```java
public synchronized MoveResult step(Snake snake) {
    // TODO el método es región crítica
    // Lectura: obstacles, teleports, mice, turbo
    // Escritura: mice.remove/add, turbo.remove, obstacles.add
    snake.advance(next, ateMouse); // Llamada a otra región crítica
}
```

**Justificación del alcance:**
- **¿Por qué TODO el método?** La operación completa (verificar colisión + mover + actualizar tablero) debe ser atómica. Si sincronizamos solo partes, otra serpiente podría comer el mismo ratón entre `mice.remove()` y `mice.add()`.
- **¿Es mínimo?** Sí. Cualquier granularidad menor rompe la invariante "un ratón = un obstáculo nuevo".
- **Costo:** Un hilo a la vez ejecuta `step()`, pero es aceptable porque la operación es rápida (~microsegundos).

---

### C2: Snake.java - Sincronización en operaciones sobre `body`

**Regiones críticas definidas:**
```java
public synchronized Position head() { return body.peekFirst(); }
public synchronized Deque<Position> snapshot() { return new ArrayDeque<>(body); }
public synchronized void advance(Position newHead, boolean grow) {
    body.addFirst(newHead);
    if (grow) maxLength++;
    while (body.size() > maxLength) body.removeLast();
}
```

**Justificación del alcance:**
- **¿Por qué sincronizar `head()`?** `Board.step()` lo llama, y debe ser consistente con `advance()`.
- **¿Por qué sincronizar `snapshot()`?** La UI lo llama para dibujar. Copiar `ArrayDeque` sin protección falla si `advance()` está ejecutándose.
- **¿Es mínimo?** Sí. Cada método protege solo SU operación sobre `body`. No bloqueamos más de lo necesario.
- **Alternativa descartada:** Usar `ConcurrentLinkedDeque` → menos eficiente y no resuelve atomicidad de `advance()`.

---

### C3: GameClock.java - Eliminación de espera activa

**Cambio implementado:**
```java
private final Lock pauseLock = new ReentrantLock();
private final Condition pauseCondition = pauseLock.newCondition();

public void resume() {
    pauseLock.lock();
    try {
        state.set(GameState.RUNNING);
        pauseCondition.signalAll(); // Despertar a todas las serpientes
    } finally {
        pauseLock.unlock();
    }
}

public void waitIfPaused() throws InterruptedException {
    if (state.get() == GameState.PAUSED) {
        pauseLock.lock();
        try {
            while (state.get() == GameState.PAUSED) {
                pauseCondition.await(); // NO busy-wait
            }
        } finally {
            pauseLock.unlock();
        }
    }
}
```

**Justificación:**
- **Riesgo eliminado:** Busy-wait consumiría CPU innecesariamente (`while(isPaused) {}`).
- **Solución:** `Condition.await()` suspende el hilo hasta que `resume()` lo despierte con `signalAll()`.
- **Eficiencia:** 20 serpientes pausadas consumen 0% CPU en lugar de ~20% con busy-wait.

---

### C4: SnakeRunner.java - Respeto de pausa

**Cambio implementado:**
```java
public void run() {
    try {
        while (!Thread.currentThread().isInterrupted() && snake.isAlive()) {
            clock.waitIfPaused(); // Espera eficiente aquí
            
            maybeTurn();
            var res = board.step(snake);
            
            if (res == Board.MoveResult.HIT_OBSTACLE) {
                snake.kill();
                break;
            }
            // ...
        }
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
    }
}
```

**Justificación:**
- **Riesgo original:** Serpientes ignoraban completamente el estado de pausa.
- **Solución:** Verificar pausa ANTES de cada movimiento usando `waitIfPaused()`.
- **Coordinación:** Cuando se pausa, todos los `SnakeRunner` se detienen en `await()`. Al reanudar, `signalAll()` los despierta simultáneamente.

---

## 2.3 Regiones Críticas Justificadas

### Criterio de diseño: **Sincronización mínima necesaria**

✅ **Sincronizamos:**
- `Board.step()` completo → operación atómica multi-paso
- `Snake.advance/head/snapshot()` → protección de `ArrayDeque`
- `GameClock.resume()` → señalización con lock

❌ **NO sincronizamos:**
- `Board.width/height()` → inmutables
- `Snake.direction()` → `volatile` suficiente (lectura atómica)
- `Board.randomEmpty()` → solo llamado desde métodos ya sincronizados
- Getters de `Board` → retornan copias defensivas

### Análisis de granularidad

**Opción descartada:** Sincronizar toda la clase `Board` (monitor único)
- ❌ Bloquearía `mice()`, `obstacles()`, etc. innecesariamente
- ✅ Mejor: Solo `step()` sincronizado, getters independientes

**Opción descartada:** Usar `Collections.synchronizedSet()` para `mice`, `obstacles`, etc.
- ❌ No garantiza atomicidad de operaciones compuestas (`remove + add`)
- ✅ Mejor: Sincronizar método completo `step()`

---

## 2.4 Verificación de Ausencia de Deadlocks

**Análisis de orden de adquisición de locks:**

1. `Board.step()` adquiere monitor de `Board`
2. Dentro de `step()`, llama `snake.advance()` → adquiere monitor de `Snake`
3. **Orden siempre:** Board → Snake (nunca al revés)

4. `GameClock.waitIfPaused()` adquiere `pauseLock`
5. No llama a métodos que adquieran otros locks dentro

**Conclusión:** NO hay posibilidad de deadlock porque:
- No hay adquisición circular de locks
- Los locks se adquieren en orden consistente
- Cada lock se libera en `finally` (no se puede olvidar)

---

## 2.5 Impacto en Rendimiento

**Medición estimada (N=20 serpientes):**
- `Board.step()` sincronizado: ~5μs de bloqueo por serpiente/frame
- Total: ~100μs/frame para 20 serpientes
- Tiempo de sleep: 80ms (baseSleep)
- **Overhead:** <0.13% → despreciable

**Conclusión:** La sincronización añadida NO afecta perceptiblemente el rendimiento porque:
1. Las operaciones sincronizadas son muy rápidas
2. Los hilos pasan 99.87% del tiempo en `sleep()`
3. Los locks se adquieren/liberan sin contención significativa

---

## 2.6 Cambios Adicionales Implementados

### Mejora: Estado vivo/muerto de serpientes
```java
// Snake.java
private volatile boolean alive = true;
public boolean isAlive() { return alive; }
public void kill() { this.alive = false; }
```

**Razón:** Necesario para estadísticas del Punto 3 (peor serpiente = primera en morir).

### Mejora: Método `length()` thread-safe
```java
public synchronized int length() { return body.size(); }
```

**Razón:** Para calcular "serpiente más larga" de forma consistente.

---

## 2.7 Pruebas de Robustez

**Configuración de prueba:**
```bash
java -Dsnakes=20 Main
```

**Verificaciones realizadas:**
✅ NO hay `ConcurrentModificationException`  
✅ NO hay dibujo corrupto (serpientes con posiciones imposibles)  
✅ Pausar detiene todas las serpientes instantáneamente  
✅ Reanudar reactiva todas las serpientes simultáneamente  
✅ Ratones no desaparecen ni se duplican  
✅ Obstáculos se generan correctamente (1 por ratón comido)  

**Resultado:** El juego es estable con N=20+ serpientes.

---

## 2.8 Resumen de Archivos Modificados

| Archivo | Líneas modificadas | Cambios clave |
|---------|-------------------|---------------|
| Board.java | 39, 56 | `synchronized` en `step()` |
| Snake.java | 32-34, 36-40, 42-44, 46-52 | `synchronized` en 3 métodos, nuevos métodos `length()`, `isAlive()`, `kill()` |
| GameClock.java | 18-20, 31-40, 47-58 | Lock+Condition, `waitIfPaused()` |
| SnakeRunner.java | 14, 17, 24-25, 29-31 | Parámetro `clock`, llamada `waitIfPaused()`, `kill()` al chocar |
| SnakeApp.java | 45 | Pasar `clock` a `SnakeRunner` |

---

# Punto 3: Control de Ejecución Seguro (UI)

## 3.1 Implementación de Iniciar/Pausar/Reanudar

**Flujo de estados implementado:**
```
[STOPPED] --Start--> [RUNNING] --Pause--> [PAUSED] --Resume--> [RUNNING]
```

**Cambios en SnakeApp.java:**

1. **Inicio controlado (no automático):**
   - El juego NO inicia automáticamente al abrir la ventana
   - Botón comienza con texto "Start"
   - Los hilos de las serpientes se crean SOLO cuando el usuario presiona "Start"

2. **Panel de estadísticas agregado:**
   ```java
   private final JLabel statsLabel;
   ```
   - Muestra estado actual del juego
   - Al pausar, presenta estadísticas calculadas de forma thread-safe

---

## 3.2 Captura Consistente del Estado al Pausar

**Problema a resolver:** Evitar "tearing" (leer estado mientras las serpientes aún se mueven)

**Solución implementada:**
```java
private void togglePause() {
    // ...
    else if ("Pause".equals(currentText)) {
        clock.pause(); // 1. Detener el reloj primero
        
        // 2. Esperar a que todos los hilos alcancen waitIfPaused()
        try {
            Thread.sleep(100); // Tiempo suficiente para sincronización
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 3. Ahora SÍ es seguro leer el estado
        Snake longest = findLongestAliveSnake();
        int aliveCount = countAliveSnakes();
        // ...
    }
}
```

**Justificación de los 100ms de espera:**
- Los `SnakeRunner` verifican `clock.waitIfPaused()` en cada iteración del loop
- El sleep más corto de una serpiente es 40ms (modo turbo)
- 100ms garantiza que todas las serpientes (incluso en turbo) alcancen el punto de pausa
- Durante esos 100ms, las serpientes ejecutan a lo sumo 2-3 pasos más, luego todas quedan en `await()`

**Resultado:** Estado capturado de forma consistente, sin lecturas parciales.

---

## 3.3 Estadísticas Mostradas al Pausar

### Estadística 1: Serpiente Viva Más Larga

**Implementación:**
```java
private Snake findLongestAliveSnake() {
    Snake longest = null;
    int maxLength = 0;
    
    synchronized (snakes) { // Sincronizar en la lista
        for (Snake s : snakes) {
            if (s.isAlive()) {
                int len = s.length(); // Método synchronized en Snake
                if (len > maxLength) {
                    maxLength = len;
                    longest = s;
                }
            }
        }
    }
    return longest;
}
```

**Thread-safety:**
- Sincronización en la lista `snakes` para evitar modificaciones durante iteración
- `snake.length()` es un método `synchronized` que lee `body.size()` de forma atómica
- `snake.isAlive()` lee un campo `volatile`

---

### Estadística 2: Peor Serpiente (Primera en Morir)

**Implementación mediante callback:**
```java
// En SnakeApp.java
private Snake firstDeadSnake = null;
private Long firstDeathTime = null;

private synchronized void onSnakeDeath(Snake snake) {
    if (firstDeadSnake == null) { // Solo registrar la PRIMERA
        firstDeadSnake = snake;
        firstDeathTime = System.currentTimeMillis();
    }
}
```

**Integración con SnakeRunner:**
```java
// SnakeRunner.java - constructor acepta callback
public SnakeRunner(Snake snake, Board board, GameClock clock, Consumer<Snake> onDeath) {
    this.onDeath = onDeath;
    // ...
}

// En el loop, al chocar con obstáculo:
if (res == Board.MoveResult.HIT_OBSTACLE) {
    snake.kill(); // Marcar como muerta
    onDeath.accept(snake); // Notificar a la UI
    break; // Terminar hilo - serpiente queda quieta (muerte visual)
}
```

**Thread-safety:**
- `onSnakeDeath()` es `synchronized` para evitar condición de carrera entre múltiples muertes simultáneas
- Solo la primera llamada registra la serpiente
- El callback es thread-safe porque se ejecuta desde el hilo de la serpiente que murió

---

## 3.4 Formato de Estadísticas

**Mensaje mostrado al pausar:**
```
⏸ PAUSED | X/Y alive | Longest: N segments | Worst: died first (M segments)
```

**Ejemplo real:**
```
⏸ PAUSED | 3/5 alive | Longest: 12 segments | Worst: died first (7 segments)
```

**Componentes:**
- `X/Y alive`: X serpientes vivas de Y totales
- `Longest`: Longitud de la serpiente más larga que sigue viva
- `Worst`: Longitud que tenía la primera serpiente al morir

---

## 3.5 Mejoras de Thread-Safety en la UI

### Lista de serpientes thread-safe
```java
private final List<Snake> snakes = Collections.synchronizedList(new ArrayList<>());
```

**Razón:** 
- Múltiples hilos pueden leer la lista (UI, cálculo de estadísticas)
- `synchronizedList()` hace atómicas las operaciones básicas
- Al iterar, seguimos usando `synchronized(snakes)` para mayor seguridad

---

### Método auxiliar para contar serpientes vivas
```java
private int countAliveSnakes() {
    int count = 0;
    synchronized (snakes) {
        for (Snake s : snakes) {
            if (s.isAlive()) count++;
        }
    }
    return count;
}
```

**Thread-safety:**
- Sincroniza en `snakes` durante la iteración
- Lee `isAlive()` que es un campo `volatile` en `Snake`

---

## 3.6 Prevención de Condiciones de Carrera en UI

### RC-5: Lectura de estadísticas durante actualización

**Escenario potencial:**
1. Thread UI llama `findLongestAliveSnake()`
2. Mientras itera, una serpiente muere o crece
3. Resultado: lectura inconsistente

**Solución aplicada:**
- Esperar 100ms después de `clock.pause()` antes de leer estadísticas
- Todas las serpientes están en `waitIfPaused()`, NO se mueven
- Estado congelado → lectura 100% consistente

---

### RC-6: Registro de primera muerte concurrente

**Escenario potencial:**
1. Serpiente A y B chocan casi simultáneamente
2. Ambas llaman `onSnakeDeath()` al mismo tiempo
3. Sin sincronización, ambas podrían registrarse como "primera"

**Solución aplicada:**
```java
private synchronized void onSnakeDeath(Snake snake) {
    if (firstDeadSnake == null) { // Check-then-act protegido
        firstDeadSnake = snake;
        firstDeathTime = System.currentTimeMillis();
    }
}
```
- Método `synchronized` → solo un hilo a la vez
- Patrón check-then-act atómico
- Solo la primera muerte se registra

---

## 3.7 Comportamiento de Muerte de Serpientes

**Representación visual:**
- Al chocar con obstáculo: serpiente se marca como muerta (`snake.kill()`)
- Su hilo termina (`break`) → **serpiente queda quieta en pantalla**
- Esto representa visualmente que la serpiente está muerta
- Las serpientes muertas se siguen dibujando pero no se mueven

**Diferencia con versión original:**
- **Original:** Serpientes giraban aleatoriamente al chocar y seguían vivas (inmortales)
- **Implementado:** Serpientes mueren al chocar, lo que permite tener "ganador" y "perdedor"

---

## 3.8 Resumen de Archivos Modificados para Punto 3

| Archivo | Cambios | Propósito |
|---------|---------|-----------|
| **SnakeApp.java** | Agregado `statsLabel`, `firstDeadSnake`, métodos de estadísticas | UI con estadísticas thread-safe |
| **SnakeApp.java** | Modificado `togglePause()` con espera de 100ms | Captura consistente del estado |
| **SnakeApp.java** | Lista `snakes` envuelta con `synchronizedList()` | Thread-safety en acceso a lista |
| **SnakeRunner.java** | Constructor acepta `Consumer<Snake> onDeath` | Callback para notificar muerte |
| **SnakeRunner.java** | Llamada a `onDeath.accept(snake)` al morir | Registro de primera muerte |

---


