# Java generator module

This pure Java module implements the public Asyncwright generator SPI. It has a
compile-only dependency on the parser/API and no Gradle dependency. The Gradle plugin
bundles its classes, sources and Javadoc; this module is not separately published.

See the [Java DTO profile](../docs/java-dto-generator.md), including runtime-boundary
limitations, and [runnable consumer](../examples/java-dto-consumer).

Run `:java-generator:test` for standalone compilation and profile regressions.
`:gradle-plugin:test` also checks packaged worker execution and consumer behavior.

Tests compile the generated Java 17 API and cover nullable/generic primitive mappings,
integer constant bounds, Unicode/control escaping, message alias roots, concrete recursion,
locale-independent enum symbols and preserved I/O failures. TestKit checks relocation
and parameter invalidation as well as output cleanup. The HTTP publication consumer loads
the bundled backend and compiles a client without injecting the plugin classpath.
