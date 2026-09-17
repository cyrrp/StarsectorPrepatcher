package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Transaction outcomes must not depend on process-wide diagnostic history or another invocation. */
public final class OrderedTransformerPipelineTest {
  private static final byte[] INPUT = {0};
  private static final byte[] PATCHED = {1};
  private static final String PREFIX = "starsector.prepatcher.pipelineTest.";

  private OrderedTransformerPipelineTest() {}

  static void run() throws Exception {
    repeatedRefusal();
    nestedRefusal();
    nestedCommitSurvivesOuterRollback();
    concurrentRefusal();
    explicitOutcomeAndExceptionalCleanup();
  }

  private static void repeatedRefusal() {
    String target = "fixture/Repeated_Target$Inner";
    AtomicInteger commits = new AtomicInteger();
    OrderedTransformerPipeline pipeline = pipeline(stage("first", (name, bytes) -> {
      TransformDiagnostics.status(key(name), "APPLIED");
      TransformDiagnostics.onCommit(commits::incrementAndGet);
      return PATCHED;
    }), stage("refusal", (name, bytes) -> {
      require(!"APPLIED".equals(System.getProperty(key(name))), "uncommitted status escaped");
      TransformDiagnostics.reject(key(name) + ".refusal", "SKIPPED_STRUCTURAL");
      return null;
    }));
    for (int attempt = 0; attempt < 2; attempt++) {
      require(apply(pipeline, target) == null, "repeated refusal committed partial bytes: " + attempt);
      require("ROLLED_BACK_PIPELINE".equals(System.getProperty(key(target))), "discarded patch reported applied");
      require("SKIPPED_STRUCTURAL".equals(System.getProperty(key(target) + ".refusal")), "refusal was lost");
    }
    require(commits.get() == 0, "discarded candidate published success counters");
  }

  private static void nestedRefusal() {
    String outer = "fixture/Nested_Outer$Success";
    String inner = "fixture/Nested_Inner$Refusal";
    OrderedTransformerPipeline[] shared = new OrderedTransformerPipeline[1];
    shared[0] = pipeline(stage("nested invocation", (name, bytes) -> {
      if (name.equals(inner)) {
        TransformDiagnostics.reject(key(name), "SKIPPED_STRUCTURAL");
        return null;
      }
      TransformDiagnostics.status(key(name), "APPLIED");
      require(apply(shared[0], inner) == null, "inner refusal committed");
      require(System.getProperty(key(name)) == null, "nested completion published outer diagnostics");
      return PATCHED;
    }));
    require(Arrays.equals(apply(shared[0], outer), PATCHED), "nested refusal rolled back outer class");
    require("APPLIED".equals(System.getProperty(key(outer))), "outer commit was lost");
  }

  private static void nestedCommitSurvivesOuterRollback() {
    String outer = "fixture/Outer_Rollback$Case";
    String inner = "fixture/Inner_Commit$Case";
    OrderedTransformerPipeline accepted = pipeline(stage("inner success", (name, bytes) -> {
      TransformDiagnostics.status(key(name), "APPLIED");
      return PATCHED;
    }));
    OrderedTransformerPipeline rejected = pipeline(stage("outer refusal", (name, bytes) -> {
      require(Arrays.equals(apply(accepted, inner), PATCHED), "inner did not commit");
      TransformDiagnostics.reject(key(name), "SKIPPED_STRUCTURAL");
      return null;
    }));
    require(apply(rejected, outer) == null, "outer refusal committed");
    require("APPLIED".equals(System.getProperty(key(inner))), "outer rollback erased inner commit");
  }

  private static void concurrentRefusal() throws Exception {
    String healthy = "fixture/Concurrent_Healthy$Case";
    String rejected = "fixture/Concurrent_Rejected$Case";
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch refused = new CountDownLatch(1);
    OrderedTransformerPipeline shared = pipeline(stage("interleaved invocation", (name, bytes) -> {
      if (name.equals(rejected)) {
        TransformDiagnostics.reject(key(name), "SKIPPED_STRUCTURAL");
        return null;
      }
      TransformDiagnostics.status(key(name), "APPLIED");
      entered.countDown();
      await(refused);
      return PATCHED;
    }));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<byte[]> healthyResult = workers.submit(() -> apply(shared, healthy));
      Future<byte[]> rejectedResult = workers.submit(() -> {
        await(entered);
        try {
          require(System.getProperty(key(healthy)) == null, "pending status visible on another thread");
          return apply(shared, rejected);
        } finally {
          refused.countDown();
        }
      });
      require(rejectedResult.get(10, TimeUnit.SECONDS) == null, "concurrent refusal committed");
      require(Arrays.equals(healthyResult.get(10, TimeUnit.SECONDS), PATCHED),
          "another thread's refusal rolled back healthy bytes");
      require("APPLIED".equals(System.getProperty(key(healthy))), "healthy commit not published");
      require("SKIPPED_STRUCTURAL".equals(System.getProperty(key(rejected))), "concurrent refusal not published");
    } finally {
      refused.countDown();
      workers.shutdownNow();
      require(workers.awaitTermination(10, TimeUnit.SECONDS), "pipeline test workers did not stop");
    }
  }

  private static void explicitOutcomeAndExceptionalCleanup() {
    String target = "fixture/Explicit_Outcome$Case";
    OrderedTransformerPipeline rejected = pipeline(stage("explicit refusal", (name, bytes) -> {
      TransformDiagnostics.reject(key(name), "DECLINED");
      return PATCHED;
    }));
    require(apply(rejected, target) == null, "rejection depended on SKIPPED spelling");

    OrderedTransformerPipeline accepted = pipeline(stage("diagnostic only", (name, bytes) -> {
      System.setProperty(PREFIX + "unrelated", "SKIPPED_STRUCTURAL");
      TransformDiagnostics.status(key(name), "APPLIED");
      return PATCHED;
    }));
    require(Arrays.equals(apply(accepted, target), PATCHED), "global property controlled stage outcome");
    require(System.getProperty("starsector.prepatcher.pipelineFailure." + target.replace('/', '.')) == null,
        "successful transaction retained a stale pipeline failure");

    AtomicInteger commits = new AtomicInteger();
    OrderedTransformerPipeline throwing = pipeline(stage("throwing", (name, bytes) -> {
      TransformDiagnostics.status(key(name), "APPLIED");
      TransformDiagnostics.onCommit(commits::incrementAndGet);
      throw new IllegalStateException("deliberate stage failure");
    }));
    require(apply(throwing, target) == null, "exception committed candidate");
    require("ROLLED_BACK_PIPELINE".equals(System.getProperty(key(target))), "exception retained APPLIED");
    require(commits.get() == 0, "exception published success");
    TransformDiagnostics.status(PREFIX + "standalone", "READY");
    require("READY".equals(System.getProperty(PREFIX + "standalone")), "transaction scope leaked after exception");
  }

  private static String key(String name) {
    return PREFIX + name.replace('/', '.');
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    require(latch.await(10, TimeUnit.SECONDS), "interleaving timed out");
  }

  private static byte[] apply(OrderedTransformerPipeline pipeline, String name) {
    return pipeline.transform(OrderedTransformerPipelineTest.class.getClassLoader(), name, null, null, INPUT);
  }

  private static OrderedTransformerPipeline pipeline(OrderedTransformerPipeline.Stage... stages) {
    return new OrderedTransformerPipeline(List.of(stages), OrderedTransformerPipelineTest.class.getClassLoader(), false, false);
  }

  private static OrderedTransformerPipeline.Stage stage(String label, Body body) {
    return new OrderedTransformerPipeline.Stage(label, new ClassFileTransformer() {
      @Override
      public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
          byte[] bytes) {
        try {
          return body.apply(name, bytes);
        } catch (RuntimeException failure) {
          throw failure;
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }
    });
  }

  @FunctionalInterface
  private interface Body {
    byte[] apply(String name, byte[] bytes) throws Exception;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
