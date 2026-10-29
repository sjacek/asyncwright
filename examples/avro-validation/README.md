# Avro validation example (development 0.3.0)

This source-build example validates a file-backed Avro payload and an inline Avro Kafka
key. It uses the unchanged model `.5` from Maven Central. `settings.gradle` includes
the current Asyncwright source build because R7 has not been released to Plugin Portal.
The other public examples continue to use released 0.2.1.

From the Asyncwright repository root, with a compatible JDK:

```powershell
.\gradlew.bat -p examples/avro-validation check '-PasyncwrightModelRepository=' --configuration-cache --configuration-cache-problems=fail
```

On Unix use `./gradlew` with the same arguments. JSON and text reports appear under
`build/reports/asyncapi/orders.*`. A valid run has an empty JSON array. Editing only
`contracts/avro/order.avsc` invalidates validation; a second unchanged run can reuse outputs.

The explicit `schemaFiles.setFrom(...)` replaces the default `.avsc` tree below the
document directory. Referenced schemas must belong to that allowlist and remain inside
the project, including their real paths. References are relative to `orders.yaml`.

This example validates schemas. It does not provision Kafka, contact Schema Registry,
or generate Avro Java DTOs. See the [Avro profile and parser API](../../docs/avro.md).
