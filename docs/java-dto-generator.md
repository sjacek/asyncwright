# Java DTO generator

Released version `0.2.1` includes
`io.github.sjacek.asyncwright.generator.java.JavaDtoGenerator` in the plugin JAR.
It is an implementation of the existing `AsyncApiGenerator` SPI, not a new plugin
or a separately published parser/generator dependency. Generated Java uses only JDK
types and has no Gradle, Asyncwright or JSON-library dependency.

Version 0.2.1 is available from Plugin Portal and its model .5 from Maven Central.
No local model repository or plugin checkout is required; see the [release status](releases.md#published-021-patch-release).

This backend generates transport beans and named string enums from message payloads.
It does not generate application services, broker clients or runtime schema validators.
Headers and unused component schemas are not model roots. References are followed
through the public resolver; source trees and reference URIs remain unchanged.

## Configure generation

Use the public repositories from the [quick start](../README.md#quick-start).
In a Java consumer:

```groovy
import io.github.sjacek.asyncwright.generator.java.JavaDtoGenerator
import io.github.sjacek.asyncwright.gradle.AsyncApiGenerate

plugins {
    id 'java'
    id 'io.github.sjacek.asyncwright' version '0.2.1'
}

asyncwright {
    specs {
        events {
            file = layout.projectDirectory.file('events.yaml')
            generators {
                dto {
                    className = JavaDtoGenerator.class.getName()
                    parameters.put(JavaDtoGenerator.PACKAGE_NAME, 'example.events')
                    parameters.put(JavaDtoGenerator.COLLECTION_TYPE, 'List')
                }
            }
        }
    }
}
def dto = tasks.named('asyncApiGenerateEventsDto', AsyncApiGenerate)
sourceSets.main.java.srcDir(dto.flatMap { it.outputDirectory })
tasks.named('compileJava') { dependsOn dto }
```

Kotlin DSL uses the same public class and parameter constants:

```kotlin
generators {
    register("dto") {
        className.set(JavaDtoGenerator::class.java.name)
        parameters.put(JavaDtoGenerator.PACKAGE_NAME, "example.events")
    }
}
```

Generation is explicit. Register its output as a source root and make compilation
depend on its task, as above. Each worker owns one directory; use a separate Sync
owner when combining several specifications. Errors remove that worker's previous
output and block dependent compilation.

| Parameter | Meaning |
| --- | --- |
| `packageName` | Required Java package; paths, empty segments and keywords are rejected |
| `collectionType` | Optional `List` (default) or `Set`; `uniqueItems: true` always selects Set |

Unknown parameter names and invalid settings fail configuration execution instead of
being ignored. Choosing Set explicitly also chooses set semantics for non-unique arrays.

## Type profile

| Schema | Java representation |
| --- | --- |
| Named object with properties | Bean with private fields, public getter/setter and implicit no-arg constructor |
| Named string enum | Uppercase symbols, `getValue`, `fromValue` and `toString` retaining contract values |
| String | String; UUID for `uuid`, LocalDate for `date`, OffsetDateTime for `date-time` |
| Integer | int/Integer; long/Long for `format: int64` |
| Number | double/Double |
| Boolean | boolean/Boolean |
| Array | List/Set with boxed element types |
| Dictionary property | Map with a supported value type |
| Supported scalar `const` | Initialized final field with getter and no setter |

Required, non-null numeric/boolean fields use primitives. Optional or nullable
properties use references. A union may contain exactly one non-null type and null.
An actual JSON null in a named enum is a Java null reference, not a string member;
the distinct string `"null"` remains a real enum value.

The source profile preserves the existing annotation-free bean API and formatting.
Configure serializers to use contract enum values (`toString`/`getValue`) when their
Java symbols differ from the wire values. The additional-property map on an open
bean is a plain bean property, not an automatic JSON any-getter/any-setter.
Boundary adapters own unknown-field binding and runtime validation.

Patterns, bounds, requiredness rules and conditional constraints stay in the contract;
generated setters do not enforce them. Fields are derived from declared base properties.
The backend does not flatten schema compositions into new members.

The explicit first profile rejects `allOf`/`oneOf`/`anyOf`, boolean schema leaves,
multi-type unions, dictionary-only roots and unsupported Java literals.
Recursive containers without a concrete object boundary are rejected as well.
Property names must retain their JavaBean wire names. Type/file/accessor collisions
and enum symbols colliding after case conversion are rejected. These are located
`java-dto.unsupported`, `java-dto.name` or `java-dto.collision` diagnostics.
All models are planned before output is written.

The output preserves property/enum declaration order and uses UTF-8 with LF.
The compatibility source profile intentionally has no terminal newline.
Concrete recursive object graphs remain references between Java types.

## Runnable example and verification

From the source repository root:

```shell
./gradlew -p examples/java-dto-consumer demo --configuration-cache-problems=fail
```

The [example](../examples/java-dto-consumer) uses the released plugin from Plugin Portal,
generates Event/State, compiles a Java client and exercises its public API. It also runs
outside this checkout with an installed Gradle 9.0+ and a compatible JDK 17+.
TestKit covers both DSLs, real worker isolation, compilation, up-to-date,
configuration/build cache, relocation, package-parameter invalidation, error cleanup
and recovery. A cold HTTP consumer loads the backend from the staged plugin JAR and
compiles a Java client without an injected plugin classpath. The binary, source and
Javadoc artifacts all include the backend.
Standalone tests compile generated sources and verify nullable/generic scalar types,
constant bounds and escaping, alias roots, concrete recursion, locale-independent
enums and preservation of infrastructure errors.
