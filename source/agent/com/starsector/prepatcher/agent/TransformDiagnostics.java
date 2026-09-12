package com.starsector.prepatcher.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invocation-local outcomes and deferred diagnostics for the ordered pipeline.
 * Standalone transformers publish immediately. Nested pipelines own independent transactions.
 */
public final class TransformDiagnostics {
  private static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();

  private TransformDiagnostics() {}

  public static void status(String key, String value) {
    Transaction transaction = CURRENT.get();
    if (transaction == null) System.setProperty(key, value);
    else transaction.statuses.put(key, value);
  }

  /** Explicit rejection, independent of the spelling or previous value of a diagnostic. */
  public static void reject(String key, String value) {
    Transaction transaction = CURRENT.get();
    if (transaction != null && transaction.rejection == null) transaction.rejection = key + "=" + value;
    status(key, value);
  }

  /** Success logs and counters describe committed bytecode, never a discarded candidate. */
  public static void onCommit(Runnable publication) {
    Transaction transaction = CURRENT.get();
    if (transaction == null) publication.run();
    else transaction.publications.add(publication);
  }

  static Transaction begin(String className) {
    return new Transaction(className);
  }

  record StageResult(byte[] bytes, String rejection) {
  }

  static final class Transaction implements AutoCloseable {
    private final Transaction parent = CURRENT.get();
    private final String className;
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private final List<Runnable> publications = new ArrayList<>();
    private String rejection;

    private Transaction(String className) {
      this.className = className;
      CURRENT.set(this);
    }

    void beginStage() {
      rejection = null;
    }

    StageResult finishStage(byte[] bytes) {
      return new StageResult(bytes, rejection);
    }

    void commit() {
      publish(true);
      System.clearProperty("starsector.prepatcher.pipelineFailure." + className.replace('/', '.'));
    }

    void rollback() {
      publish(false);
    }

    private void publish(boolean committed) {
      // Publication must not join this transaction or an enclosing transformation.
      // A class load triggered by a publication will open its own pipeline scope.
      CURRENT.remove();
      try {
        for (Map.Entry<String, String> entry : statuses.entrySet()) {
          String value = entry.getValue();
          if (!committed && requiresCommit(value)) {
            value = "ROLLED_BACK_PIPELINE";
            PrepatcherLog.warn(
                value + " " + entry.getKey() + " in " + className + ": the enclosing class transaction did not commit");
          }
          System.setProperty(entry.getKey(), value);
        }
        if (committed) {
          for (Runnable publication : publications) publication.run();
        }
      } finally {
        CURRENT.set(this);
      }
    }

    private static boolean requiresCommit(String status) {
      return switch (status) {
        case "APPLIED", "READY", "ready", "PASSED" -> true;
        default -> false;
      };
    }

    @Override
    public void close() {
      if (parent == null) CURRENT.remove();
      else CURRENT.set(parent);
    }
  }
}
