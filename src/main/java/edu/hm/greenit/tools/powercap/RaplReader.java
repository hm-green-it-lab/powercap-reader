package edu.hm.greenit.tools.powercap;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

record SampleRow(long epochTimestampMs, long timestampNs, String domainName, long energyUj, long dramEnergyUj) {
    String toCsv() {
        return String.join(RaplReader.COMMA_SEPERATOR, String.valueOf(epochTimestampMs), String.valueOf(timestampNs), domainName, String.valueOf(energyUj), String.valueOf(dramEnergyUj));
    }
}

@ApplicationScoped
@QuarkusMain
public class RaplReader implements QuarkusApplication {

    static final String COMMA_SEPERATOR = ",";
    private static final long MINUS_ONE_VALUE = -1L;
    private static final SampleRow POISON_PILL = new SampleRow(Long.MIN_VALUE, Long.MIN_VALUE, "", Long.MIN_VALUE, Long.MIN_VALUE);

    private static final long PARK_THRESHOLD_NS = 50_000L;
    private static final long PARK_SAFETY_MARGIN_NS = 20_000L;
    private static final int OUTPUT_QUEUE_CAPACITY = 8192;

    /** Max byte length of an energy_uj value: 20 digits + newline, rounded up. */
    private static final int READ_BUFFER_SIZE = 24;

    private static final Path raplBasePath = Path.of("/sys/class/powercap/intel-rapl");
    private final BlockingQueue<SampleRow> outputQueue = new ArrayBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
    private List<RaplDomain> raplDomains;
    private long droppedLines;

    @ConfigProperty(name = "powercap.interval.ms", defaultValue = "1000.0")
    private double powerCapIntervalMs;

    // ---------------------------------------------------------------------------
    // Runtime domain: holds pre-opened FileChannels and a reused ByteBuffer so
    // that each sample only needs a single pread() syscall per file — no open/
    // close overhead and no ephemeral object allocation on the hot path.
    // ---------------------------------------------------------------------------
    private static final class RaplDomain implements Closeable {
        final String domainName;
        final FileChannel energyChannel;
        final FileChannel dramEnergyChannel; // nullable
        // Direct buffer avoids an internal JVM copy when calling FileChannel.read()
        final ByteBuffer buffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);

        RaplDomain(String domainName, FileChannel energyChannel, FileChannel dramEnergyChannel) {
            this.domainName = domainName;
            this.energyChannel = energyChannel;
            this.dramEnergyChannel = dramEnergyChannel;
        }

        @Override
        public void close() throws IOException {
            energyChannel.close();
            if (dramEnergyChannel != null) {
                dramEnergyChannel.close();
            }
        }
    }

    public static void main(String[] args) {
        Quarkus.run(RaplReader.class, args);
    }

    // Used only during initialisation to read the domain name (a string, not a number).
    private static String readFile(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return reader.readLine();
        }
    }

    /**
     * Reads an ASCII decimal integer from a sysfs file using a pre-opened
     * FileChannel.  Reading from position 0 via pread() always returns fresh
     * kernel-generated data without reopening the file.
     */
    private static long readLong(FileChannel channel, ByteBuffer buffer) throws IOException {
        buffer.clear();
        channel.read(buffer, 0L);
        buffer.flip();
        long value = 0L;
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            } else {
                break; // newline or end of content
            }
        }
        return value;
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
            for (RaplDomain domain : raplDomains) {
                try {
                    domain.close();
                } catch (IOException ignored) {
                }
            }
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

    // Load available RAPL domains from sysfs, open FileChannels, and build the runtime domain list.
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
                        if (Files.exists(energyPath) && Files.exists(namePath)) {
                            try {
                                String domainName = readFile(namePath);
                                FileChannel energyChannel = FileChannel.open(energyPath, StandardOpenOption.READ);
                                FileChannel dramChannel = dramEnergyPath != null
                                        ? FileChannel.open(dramEnergyPath, StandardOpenOption.READ)
                                        : null;
                                return new RaplDomain(domainName, energyChannel, dramChannel);
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

        for (RaplDomain domain : raplDomains) {
            // Re-read from position 0: pread() always returns fresh sysfs data
            long energyUj = readLong(domain.energyChannel, domain.buffer);

            long dramEnergyUj = domain.dramEnergyChannel != null
                    ? readLong(domain.dramEnergyChannel, domain.buffer)
                    : MINUS_ONE_VALUE;

            SampleRow output = new SampleRow(epochTimeStampMs, nsTimestamp,
                    domain.domainName, energyUj, dramEnergyUj);

            if (!outputQueue.offer(output)) {
                droppedLines++;
            }
        }
    }
}

