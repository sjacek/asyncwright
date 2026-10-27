package example;

import example.generated.Event;
import example.generated.State;

/** Uses generated types as ordinary Java classes; generation adds no runtime dependency. */
public final class Client {
    private Client() { }
    /**
     * Runs the fixed example using the standard Java 17 launcher signature.
     * @param args unused command-line arguments
     */
    public static void main(@SuppressWarnings("unused") String[] args) {
        Event event = new Event();
        event.setCount(3_000_000_000L);
        event.setState(State.fromValue("ready"));
        if (event.getCount() != 3_000_000_000L || event.getState() != State.READY)
            throw new IllegalStateException("Generated API mismatch");
        System.out.println(event.getCount() + " / " + event.getState());
    }
}
