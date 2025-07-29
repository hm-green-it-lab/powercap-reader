# Powercap Reader

A Java-based tool to read power consumption from RAPL interfaces via the Linux powercap framework. Designed for integration into automated energy measurement pipelines.

> [!note]
> **Platform Support**: This reader requires a Linux system with Intel RAPL support and a populated `/sys/class/powercap/intel-rapl/` directory. Root permissions may be required to access energy measurements.

## Configuration

### Measurement Interval

Set measurement interval in `application.properties`:

```properties
powercap.cron=*/1 * * * * ?
```

> [!warning]
> A value less than one second may not be supported by the underlying scheduler implementation. In that case a warning message is logged during build and application start. Check https://quarkus.io/guides/scheduler-reference

You can override this setting at runtime using a JVM parameter:

```bash
java -Dpowercap.cron=""*/5 * * * * ? -jar powercap-reader-[version]-runner.jar
```

### Measurement Mode

The application logs raw energy readings in microjoules (µJ) once per configured interval. No time-based calculations or conversions are performed during measurement to keep logging lightweight and efficient.
Any further analysis — such as calculating power in watts (1 µJ = 1.0E-6 W·s or 1 W·s = 1000000 µJ) — is intended to be done afterward in post-processing.

## Build 

```bash
mvn clean package
```

## Run

```bash
java -jar target/powercap-reader-[version]-runner.jar
```

Example Output:

```text
Timestamp,Domain,Energy (micro joules), DRAM Energy (micro joules)
1746240000000,package-0,12345678,4567890
1746240010000,package-0,12349999,4572100
...
```

## Notes

- RAPL provides cumulative energy readings.
- The DRAM domain is optional and may not be supported on all systems.
- Output is printed to stdout and can be redirected or processed by orchestration layers.
- This tool does not write CSV files itself.






