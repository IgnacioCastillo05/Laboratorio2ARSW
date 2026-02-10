package co.eci.snake.core.engine;

import co.eci.snake.core.GameState;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class GameClock implements AutoCloseable {
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final long periodMillis;
  private final Runnable tick;
  private final AtomicReference<GameState> state = new AtomicReference<>(GameState.STOPPED);

  private final Lock pauseLock = new ReentrantLock();
  private final Condition pauseCondition = pauseLock.newCondition();

  public GameClock(long periodMillis, Runnable tick) {
    if (periodMillis <= 0) throw new IllegalArgumentException("periodMillis must be > 0");
    this.periodMillis = periodMillis;
    this.tick = Objects.requireNonNull(tick, "tick");
  }

  public void start() {
    if (state.compareAndSet(GameState.STOPPED, GameState.RUNNING)) {
      scheduler.scheduleAtFixedRate(() -> {
        if (state.get() == GameState.RUNNING) tick.run();
      }, 0, periodMillis, TimeUnit.MILLISECONDS);
    }
  }

  public void pause() {
    state.set(GameState.PAUSED);
  }

  public void resume() {
    pauseLock.lock();
    try {
      state.set(GameState.RUNNING);
      pauseCondition.signalAll();
    } finally {
      pauseLock.unlock();
    }
  }

  public void stop() { 
    state.set(GameState.STOPPED); 
  }

  public GameState getState() {
    return state.get();
  }

  public void waitIfPaused() throws InterruptedException {
    if (state.get() == GameState.PAUSED) {
      pauseLock.lock();
      try {
        while (state.get() == GameState.PAUSED) {
          pauseCondition.await();
        }
      } finally {
        pauseLock.unlock();
      }
    }
  }

  @Override 
  public void close() { 
    scheduler.shutdownNow(); 
  }
}