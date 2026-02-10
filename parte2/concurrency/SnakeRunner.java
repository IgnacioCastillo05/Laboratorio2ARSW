package co.eci.snake.concurrency;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class SnakeRunner implements Runnable {
  private final Snake snake;
  private final Board board;
  private final GameClock clock;
  private final Consumer<Snake> onDeath; 
  private final int baseSleepMs = 80;
  private final int turboSleepMs = 40;
  private int turboTicks = 0;

  public SnakeRunner(Snake snake, Board board, GameClock clock, Consumer<Snake> onDeath) {
    this.snake = snake;
    this.board = board;
    this.clock = clock;
    this.onDeath = onDeath;
  }

  @Override
  public void run() {
    try {
      while (!Thread.currentThread().isInterrupted() && snake.isAlive()) {

        clock.waitIfPaused();

        maybeTurn();
        var res = board.step(snake);
        
        if (res == Board.MoveResult.HIT_OBSTACLE) {
          snake.kill(); 
          onDeath.accept(snake);
          break;
        } else if (res == Board.MoveResult.ATE_TURBO) {
          turboTicks = 100;
        }
        
        int sleep = (turboTicks > 0) ? turboSleepMs : baseSleepMs;
        if (turboTicks > 0) turboTicks--;
        Thread.sleep(sleep);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private void maybeTurn() {
    double p = (turboTicks > 0) ? 0.05 : 0.10;
    if (ThreadLocalRandom.current().nextDouble() < p) randomTurn();
  }

  private void randomTurn() {
    Direction[] dirs = Direction.values();
    snake.turn(dirs[ThreadLocalRandom.current().nextInt(dirs.length)]);
  }
}