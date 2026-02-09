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