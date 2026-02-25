package edu.hm.greenit.tools.powercap;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

@ApplicationScoped
@QuarkusMain
public class RaplReader implements QuarkusApplication {

    private static final Path raplBasePath = Path.of("/sys/class/powercap/intel-rapl");

    private List<String> raplDomains;

    @Inject
    private ScheduledExecutorService scheduledExecutorService;

    @ConfigProperty(name = "powercap.interval.ms", defaultValue = "1000")
    private long powerCapIntervalMs;

    public static void main(String[] args) {
        Quarkus.run(RaplReader.class, args);
    }

    // Reads a single line from a file if it exists
    private static String readFile(String path) throws IOException {
        if (Files.exists(Path.of(path))) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                return reader.readLine();
            }
        } else {
            return "-1";
        }
    }

    @Override
    public int run(String... args) throws IOException, InterruptedException {
        // Initialize RAPL domains at startup
        initializeRaplDomains();

        // Print CSV header for raw mode
        System.out.println("Timestamp,Domain, Energy (micro joules), DRAM Energy (micro joules)");

        scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                readPowercapDataAndWriteTofiles();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, 0, powerCapIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        // Keep application running
        while (true) {
            Thread.sleep(60000);
        }
    }

    // Load available RAPL domains from sysfs
    private void initializeRaplDomains() throws IOException {
        raplDomains = Files.list(raplBasePath)
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("intel-rapl:"))
                .toList();
    }

    void readPowercapDataAndWriteTofiles() throws IOException {
        long timestamp = System.currentTimeMillis();

        for (String domain : raplDomains) {
            String energyPath = raplBasePath + "/" + domain + "/energy_uj";
            String dramEnergyPath = raplBasePath + "/" + domain + "/" + domain + ":0/energy_uj";
            String namePath = raplBasePath + "/" + domain + "/name";

            // Read the name of the RAPL domain
            String domainName = readFile(namePath);

            // Read the energy consumption data (only once per execution)
            String energyUj = readFile(energyPath);
            String dramEnergyUj = readFile(dramEnergyPath);

            if (energyUj != null && dramEnergyUj != null) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(timestamp);
                stringBuilder.append(",");
                stringBuilder.append(domainName);
                stringBuilder.append(",");
                stringBuilder.append(energyUj);
                stringBuilder.append(",");
                stringBuilder.append(dramEnergyUj);
                System.out.println(stringBuilder);
            }
        }
    }
}