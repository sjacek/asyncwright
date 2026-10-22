/**
 * Offline AsyncAPI 3.0 parsing, source diagnostics and ordered extension execution.
 * Parsing preserves reference objects in the model and exposes a separate resolver.
 * Invalid documents return diagnostics; file I/O and extension execution failures retain their causes.
 * Direct extension execution may leave partial output. The Gradle host supplies staging and cleanup.
 */
package io.github.sjacek.asyncwright.parser;
