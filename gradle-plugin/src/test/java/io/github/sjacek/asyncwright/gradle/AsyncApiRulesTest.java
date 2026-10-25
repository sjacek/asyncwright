package io.github.sjacek.asyncwright.gradle;

import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.gradle.api.provider.Property;
import static org.junit.jupiter.api.Assertions.*;

/** Compatibility and precedence of the managed single-value and list DSL properties. */
class AsyncApiRulesTest {
    @Test
    void listFollowsTheLegacyPropertyUntilExplicitlyOverriddenAndDslCallsReplaceTheRequirement() {
        var rules = ProjectBuilder.builder().build().getObjects().newInstance(AsyncApiRules.class);
        assertEquals(List.of(), rules.getProtocols().get());
        rules.getProtocol().set("amqp");
        assertEquals(List.of("amqp"), rules.getProtocols().get());
        rules.requireProtocol("kafka", "kafka-secure", "kafka");
        assertEquals(List.of("kafka", "kafka-secure"), rules.getProtocols().get());
        rules.getProtocol().set("mqtt");
        assertEquals(List.of("kafka", "kafka-secure"), rules.getProtocols().get());
        rules.requireProtocol("amqp");
        assertEquals(List.of("amqp"), rules.getProtocols().get());
        assertEquals("amqp", rules.getProtocol().get());
        rules.requireProtocol("");
        assertEquals(List.of(), rules.getProtocols().get());
        rules.requireProtocol("kafka", "kafka-secure");
        rules.requireProtocol();
        assertEquals(List.of(), rules.getProtocols().get());
        rules.getProtocols().set(List.of("mqtt"));
        assertEquals(List.of("mqtt"), rules.getProtocols().get());
    }

    @Test
    void invalidListArgumentsDoNotReplaceThePreviousPolicy() {
        var rules = ProjectBuilder.builder().build().getObjects().newInstance(AsyncApiRules.class);
        rules.requireProtocol("amqp");
        assertThrows(IllegalArgumentException.class, () -> rules.requireProtocol("kafka", " "));
        assertThrows(NullPointerException.class, () -> rules.requireProtocol("kafka", null));
        assertEquals(List.of("amqp"), rules.getProtocols().get());
    }

    /** Preserve provider laziness when a build script configures the scalar after applying the plugin. */
    @Test
    void legacyProviderChangesRemainVisibleUntilTheListIsExplicitlySet() {
        var objects = ProjectBuilder.builder().build().getObjects();
        var rules = objects.newInstance(AsyncApiRules.class);
        Property<String> selected = objects.property(String.class);
        selected.set("amqp");
        rules.getProtocol().set(selected);
        assertEquals(List.of("amqp"), rules.getProtocols().get());
        selected.set("kafka");
        assertEquals(List.of("kafka"), rules.getProtocols().get());
        rules.getProtocols().set(List.of("mqtt"));
        selected.set("kafka-secure");
        assertEquals(List.of("mqtt"), rules.getProtocols().get());
        rules.getProtocols().unset();
        assertEquals(List.of("kafka-secure"), rules.getProtocols().get());
    }

    /** Gradle inputs must not be backed by a caller-owned mutable array. */
    @Test
    void protocolArrayIsCopiedAndNoArgumentsDisableBothRepresentations() {
        var rules = ProjectBuilder.builder().build().getObjects().newInstance(AsyncApiRules.class);
        String[] values = {"kafka", "kafka-secure", "kafka"};
        rules.requireProtocol(values);
        values[0] = "mqtt";
        assertEquals(List.of("kafka", "kafka-secure"), rules.getProtocols().get());
        rules.requireProtocol();
        assertEquals("", rules.getProtocol().get());
        assertEquals(List.of(), rules.getProtocols().get());
        assertThrows(NullPointerException.class, () -> rules.requireProtocol((String[]) null));
        assertThrows(NullPointerException.class, () -> rules.requireProtocol((String) null));
        assertEquals(List.of(), rules.getProtocols().get());
    }
}
