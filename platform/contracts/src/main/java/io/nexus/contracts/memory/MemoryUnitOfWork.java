package io.nexus.contracts.memory;

import io.nexus.contracts.event.UnitOfWork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Unit of work without a database: in-memory stores register an undo action for every write,
 * and a failing unit replays them in reverse. Used by the browser runtime and by core unit tests.
 * Single-threaded by design.
 */
public final class MemoryUnitOfWork implements UnitOfWork {
  private record Frame(List<Runnable> undo, List<Runnable> afterCommit) {}

  private final Deque<Frame> frames = new ArrayDeque<>();

  @Override
  public void afterCommit(Runnable action) {
    if (frames.isEmpty()) action.run();
    else frames.peek().afterCommit().add(action);
  }

  @Override
  public void inNewTransaction(Runnable action) {
    var frame = new Frame(new ArrayList<>(), new ArrayList<>());
    frames.push(frame);
    try {
      action.run();
    } catch (RuntimeException | Error e) { // an Error must not leave the frame on the stack
      frames.pop();
      for (int i = frame.undo().size() - 1; i >= 0; i--) frame.undo().get(i).run();
      throw e;
    }
    frames.pop();
    for (Runnable after : frame.afterCommit()) after.run();
  }

  /** For in-memory stores: how to take back the write just made if the current unit fails. */
  public void onRollback(Runnable undo) {
    if (!frames.isEmpty()) frames.peek().undo().add(undo);
  }
}
