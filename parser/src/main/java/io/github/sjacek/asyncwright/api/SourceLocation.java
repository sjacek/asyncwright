package io.github.sjacek.asyncwright.api;

/** A project-relative file and a one-based source position. */
public record SourceLocation(String file, int line, int column, String pointer) { }
