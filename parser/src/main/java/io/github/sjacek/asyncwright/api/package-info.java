/**
 * Gradle-independent extension contracts for Asyncwright 0.1.
 * Rules and generators receive per-invocation document models, immutable text parameters,
 * project-relative source locations and reference resolution without graph expansion.
 * The host selects the classloader and output directory; extension classes are trusted Java code.
 */
package io.github.sjacek.asyncwright.api;
