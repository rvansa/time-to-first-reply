    package org.github.crac.benchmarks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

public class TimeToFirstReply {
    static final int WARMUP = Integer.getInteger("benchmark.warmup", 10);
    static final int MEASURED = Integer.getInteger("benchmark.measured", 40);
    static final URI uri = URI.create(System.getProperty("benchmark.uri", "http://localhost:8080"));

    static final LongAdder sum = new LongAdder();
    static final LongAccumulator min = new LongAccumulator(Math::min, Long.MAX_VALUE);
    static final LongAccumulator max = new LongAccumulator(Math::max, 0);
    static AtomicBoolean received = new AtomicBoolean();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("No command to start");
            System.exit(1);
        }
        for (int i = 0; i < WARMUP + MEASURED; ++i) {
            boolean isWarmup = i < WARMUP;
            Process process = null;
            // We need to explicitly setup version 1.1 - otherwise the client would attempt switching to 2.0
            // which would take very long (and the server might not be warmed up).
            try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                ProcessBuilder pb = new ProcessBuilder().command(args);
                if (System.getProperty("benchmark.print.output") != null) {
                    pb.inheritIO();
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                }
                process = pb.start();
                long start = System.nanoTime();
                received.set(false);
                while (process.isAlive() && !received.get()) {
                    long send = System.nanoTime();
                    client.sendAsync(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
                        long end = System.nanoTime();
                        if (response.statusCode() < 200 || response.statusCode() > 299) {
                            System.out.println("Invalid status: " + response.statusCode());
                            System.exit(1);
                        }
                        if (!received.compareAndSet(false, true)) {
                            return;
                        }
                        if (isWarmup) {
                            System.out.print('-');
                        } else {
                            long diff = end - start;
                            sum.add(diff);
                            min.accumulate(diff);
                            max.accumulate(diff);
                            System.out.print('+');
                        }
                        System.out.flush();
                    }).exceptionally(ex -> null).join();
                }
                if (!received.get()) {
                    System.err.println("Process failed before receiving reply.");
                    System.exit(1);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                if (process != null) {
                    Phaser phaser = new Phaser(1);
                    try {
                        process.toHandle().descendants().forEach(h -> {
                            h.destroyForcibly();
                            phaser.register();
                            h.onExit().thenRun(phaser::arrive);
                        });
                        process.destroyForcibly().waitFor();
                        phaser.arriveAndAwaitAdvance();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        System.out.println();
        System.err.printf("AVG %.2f ms MIN %.2f ms MAX %.2f ms%n",
                (sum.sum() / MEASURED) / 1_000_000.0, min.get() / 1_000_000.0, max.get() / 1_000_000.0);
    }
}
