package edu.hm.greenit.tools.powercap;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

record RaplDomainPaths(String name, Path energyPath, Path dramEnergyPath, Path namePath) {
}

@ApplicationScoped
@QuarkusMain
public class RaplReader implements QuarkusApplication {

    private static final String MINUS_ONE_VALUE = "-1";

    private static final String COMMA_SEPERATOR = ",";

    private static final Path raplBasePath = Path.of("/sys/class/powercap/intel-rapl");

    private List<RaplDomainPaths> raplDomains;

    @Inject
    private ScheduledExecutorService scheduledExecutorService;

    @ConfigProperty(name = "powercap.interval.ms", defaultValue = "1000")
    private long powerCapIntervalMs;

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

    // Load available RAPL domains from sysfs and create paths data structure
    private void initializeRaplDomains() throws IOException {
        raplDomains = Files.list(raplBasePath)
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
                        return new RaplDomainPaths(domain, energyPath, dramEnergyPath, namePath);
                    }
                    return null;
                })
                .filter(domainPaths -> domainPaths != null)
                .toList();
    }

    void readPowercapDataAndWriteTofiles() throws IOException {
        long timestamp = System.currentTimeMillis();

        for (RaplDomainPaths domain : raplDomains) {
            // Read the name of the RAPL domain
            String domainName = readFile(domain.namePath());

            // Read the energy consumption data
            String energyUj = readFile(domain.energyPath());

            // Read DRAM energy if the path exists
            String dramEnergyUj = domain.dramEnergyPath() != null
                    ? readFile(domain.dramEnergyPath())
                    : MINUS_ONE_VALUE;

            if (energyUj != null) {
                String output = String.join(COMMA_SEPERATOR,
                        String.valueOf(timestamp),
                        domainName,
                        energyUj,
                        dramEnergyUj);
                System.out.println(output);
            }
        }
    }
}