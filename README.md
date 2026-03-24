# Powercap Reader

A Java-based tool to read power consumption from RAPL interfaces via the Linux powercap framework. Designed for integration into automated energy measurement pipelines.

> [!note]
> **Platform Support**: This reader requires a Linux system with Intel RAPL support and a populated `/sys/class/powercap/intel-rapl/` directory. Root permissions may be required to access energy measurements.

## Configuration

### Measurement Interval

Set measurement interval in `application.properties`:

```properties
powercap.interval.ms=100.0
```

You can override this setting at runtime using a JVM parameter:

```bash
java -Dpowercap.interval.ms=1000.0 -jar powercap-reader-[version]-runner.jar
```

The interval is specified in milliseconds (ms) and determines how often energy readings are logged. The default value is 100 ms, but it can be adjusted based on the desired granularity of measurements. Internally, the interval is converted to nanoseconds for scheduling purposes, as the application uses a high-resolution timer to trigger energy readings. 

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
EpochTimestampMilliSeconds,TimestampNanoSeconds,Domain,Energy (micro joules),DRAM Energy (micro joules)
1774351404175,527041666265300,package-0,12345678,4567890
1774351404175,527041666265400,package-0,12349999,4572100
...
```

## Notes

- The TimestampNanoSeconds is provided in nanoseconds resolution but not necessarily in nanosecond precision. It is intended to provide a high-resolution timestamp for each reading, but the actual precision may vary based on the underlying hardware and operating system scheduling.
- RAPL provides cumulative energy readings.
- The DRAM domain is optional and may not be supported on all systems.
- Output is printed to stdout and can be redirected or processed by orchestration layers.
- This tool does not write CSV files itself.







