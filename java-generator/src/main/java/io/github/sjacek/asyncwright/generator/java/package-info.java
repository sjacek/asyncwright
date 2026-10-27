/**
 * Native Java transport generation through Asyncwright's
 * {@link io.github.sjacek.asyncwright.api.AsyncApiGenerator} SPI.
 * <p>Message payloads are roots; unused component schemas and headers do not create DTOs.
 * Local aliases resolve to the same concrete type without changing the source document.
 * The first profile emits annotation-free beans, named string enums and JDK collection types.
 * Runtime validation and serializer configuration remain responsibilities of boundary adapters.
 * <p>The Gradle plugin bundles this package. It is not a separately published dependency,
 * and generated source code has no dependency on Asyncwright, Gradle or a JSON library.
 * Output order and formatting form part of the source compatibility profile.
 */
package io.github.sjacek.asyncwright.generator.java;
