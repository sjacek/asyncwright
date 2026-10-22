package io.github.sjacek.asyncwright.api;

/**
 * A project-relative file and a one-based source position.
 *
 * @param file project-relative file name using forward slashes
 * @param line one-based line number
 * @param column one-based column number
 * @param pointer document JSON Pointer, or empty for the root
 */
public record SourceLocation(String file, int line, int column, String pointer) { }
