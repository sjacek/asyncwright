/**
 * Gradle DSL and cache-aware tasks for AsyncAPI validation and trusted Java generators.
 * Task inputs consist of managed values and an isolated extension classpath; typed models and
 * extension instances are created inside workers. Reports use portable project-relative locations.
 * Generation replaces a dedicated directory below the build directory only after validation and
 * generation succeed; a failed generation worker removes its output and temporary staging directory.
 * Output boundary checks happen before execution or cache restoration and preserve protected files.
 */
package io.github.sjacek.asyncwright.gradle;
