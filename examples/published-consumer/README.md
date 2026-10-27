# Public Asyncwright consumer

This example uses released **Asyncwright 0.2.0** from the Gradle Plugin Portal and
model **1.0.0-RC4-asyncwright.5** from Maven Central. It does not include the plugin build
or require a sibling model checkout, a local model repository or Maven Local.

With an installed Gradle 9.0+ and a compatible JDK 17+, run from this directory:

```powershell
gradle check asyncApiGenerateEventsCatalog
```

From the Asyncwright repository root, the existing wrapper can run the example:

```powershell
.\gradlew.bat -p examples/published-consumer check asyncApiGenerateEventsCatalog
```

The `extensions` subproject compiles `ChannelAddressRule` and `ChannelCatalogGenerator`
against the published API. Validation checks the AMQP protocol and the `events.` channel
prefix. Generation resolves typed channels and writes `events.created` followed by LF to
`build/generated/asyncapi/events/catalog/channels.txt`. Reports are under
`build/reports/asyncapi/`.

`gradle.properties` enables configuration and build caches. Repeating the command reuses
the configuration and up-to-date results. An invalid channel prefix produces
`sample.channel-prefix`; explicitly running generation removes its old output on failure.

Both the plugin and extension dependency default to `0.2.0`.
`-PasyncwrightVersion=...` selects another published version. The optional HTTP repository
properties in settings exist for the automated prepublication test; normal use needs no
repository overrides. See the [release guide](../../docs/releases.md) for the acceptance
procedure and the [Gradle DSL](../../docs/gradle-plugin.md) for configuration.

## Java DTO generation

The separate [Java DTO consumer](../java-dto-consumer) uses the same released 0.2.0
plugin and model .5, generates Java models and compiles a client without an included
plugin build. The automated publication test adds DTO generation to a copy of this
custom-extension example and checks compilation, failure cleanup and cache behavior.
