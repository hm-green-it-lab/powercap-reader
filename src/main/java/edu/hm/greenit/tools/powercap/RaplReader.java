package edu.hm.greenit.tools.powercap;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

record RaplDomainPaths(String domainName, Path energyPath, Path dramEnergyPath, Path namePath) {
}

record SampleRow(long epochTimestampMs, long timestampNs, String domainName, String energyUj, String dramEnergyUj) {
    String toCsv() {
        return String.join(RaplReader.COMMA_SEPERATOR, String.valueOf(epochTimestampMs), String.valueOf(timestampNs), domainName, energyUj, dramEnergyUj);
    }
}

@ApplicationScoped
@QuarkusMain
public class RaplReader implements QuarkusApplication {

    static final String COMMA_SEPERATOR = ",";
    private static final String MINUS_ONE_VALUE = "-1";
    private static final SampleRow POISON_PILL = new SampleRow(Long.MIN_VALUE, Long.MIN_VALUE, "", "", "");

    private static final long PARK_THRESHOLD_NS = 50_000L;

    private static final long PARK_SAFETY_MARGIN_NS = 20_000L;

    private static final int OUTPUT_QUEUE_CAPACITY = 8192;

    private static final Path raplBasePath = Path.of("/sys/class/powercap/intel-rapl");
    private final BlockingQueue<SampleRow> outputQueue = new ArrayBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
    private List<RaplDomainPaths> raplDomains;
    private long droppedLines;

    @ConfigProperty(name = "powercap.interval.ms", defaultValue = "1000.0")
    private double powerCapIntervalMs;

    public static void main(String[] args) {
        Quarkus.run(RaplReader.class, args);
    }

    // Reads a single line from a file if it exists
    private static String readFile(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return reader.readLine();
        }
    }

    @Override
    public int run(String... args) throws IOException, InterruptedException {
        // Initialize RAPL domains at startup
        initializeRaplDomains();

        // Print CSV header for raw mode
        System.out.println("EpochTimestampMilliSeconds,TimestampNanoSeconds,Domain,Energy (micro joules),DRAM Energy (micro joules)");

        long intervalNs = resolveSamplingIntervalNs();
        Thread writerThread = startWriterThread();

        try {
            runSamplingLoop(intervalNs);
        } finally {
            stopWriterThread(writerThread);
        }

        return 0;
    }

    private long resolveSamplingIntervalNs() {
        return Math.max(1L, Math.round(powerCapIntervalMs * 1_000_000.0d));
    }

    private Thread startWriterThread() {
        Thread writerThread = new Thread(() -> {
            try {
                while (true) {
                    SampleRow output = outputQueue.take();
                    if (output == POISON_PILL) {
                        return;
                    }
                    System.out.println(output.toCsv());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "powercap-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        return writerThread;
    }

    private void runSamplingLoop(long intervalNs) {
        long nextDeadlineNs = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            nextDeadlineNs += intervalNs;

            try {
                readPowercapDataAndWriteToQueue();
            } catch (IOException e) {
                System.err.println("Failed to read powercap data: " + e.getMessage());
            }

            waitUntil(nextDeadlineNs);
        }
    }

    private void waitUntil(long deadlineNs) {
        long remainingNs;
        while ((remainingNs = deadlineNs - System.nanoTime()) > 0) {
            if (remainingNs > PARK_THRESHOLD_NS) {
                LockSupport.parkNanos(remainingNs - PARK_SAFETY_MARGIN_NS);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void stopWriterThread(Thread writerThread) throws InterruptedException {
        while (!outputQueue.offer(POISON_PILL, 100, TimeUnit.MILLISECONDS)) {
            Thread.onSpinWait();
        }
        writerThread.join(1000);
        if (droppedLines > 0) {
            System.err.println("Dropped output lines due to full queue: " + droppedLines);
        }
    }

    // Load available RAPL domains from sysfs and create paths data structure
    private void initializeRaplDomains() throws IOException {
        try (var raplDomainStream = Files.list(raplBasePath)) {
            raplDomains = raplDomainStream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("intel-rapl:"))
                    .map(domain -> {
                        Path energyPath = raplBasePath.resolve(domain).resolve("energy_uj");
                        Path dramEnergyPath = raplBasePath.resolve(domain).resolve(domain + ":0").resolve("energy_uj");
                        Path namePath = raplBasePath.resolve(domain).resolve("name");

                        if (!Files.exists(dramEnergyPath)) {
                            dramEnergyPath = null;
                        }
                        // Only include domains where energyPath and namePath exist (dramEnergyPath is optional)
                        if (Files.exists(energyPath) && Files.exists(namePath)) {

                            // Read the name of the RAPL domain
                            try {
                                String domainName = readFile(namePath);
                                return new RaplDomainPaths(domainName, energyPath, dramEnergyPath, namePath);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    void readPowercapDataAndWriteToQueue() throws IOException {
        long epochTimeStampMs = System.currentTimeMillis();
        long nsTimestamp = System.nanoTime();

        for (RaplDomainPaths domain : raplDomains) {
            // Read the energy consumption data
            String energyUj = readFile(domain.energyPath());

            // Read DRAM energy if the path exists
            String dramEnergyUj = domain.dramEnergyPath() != null
                    ? readFile(domain.dramEnergyPath())
                    : MINUS_ONE_VALUE;

            if (energyUj != null) {
                // Queue raw values and defer CSV creation to the writer thread to keep sampling timing stable.
                SampleRow output = new SampleRow(epochTimeStampMs, nsTimestamp,
                        domain.domainName(),
                        energyUj,
                        dramEnergyUj);

                // Avoid blocking the sampling loop on stdout when running high-frequency intervals.
                if (!outputQueue.offer(output)) {
                    droppedLines++;
                }
            }
        }
    }
}