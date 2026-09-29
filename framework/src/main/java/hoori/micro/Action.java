package hoori.micro;

import hoori.rest.json.JsonCodec;

/**
 * Typed contract of one remote action: "service.action" plus wire codecs. Deliberately no host,
 * URL, route or version; the caller's dependsOn() declares the compatible major version.
 */
public final class Action<I, O> {
    public final String name;
    public final JsonCodec<I> input;
    public final JsonCodec<O> output;

    public Action(String name, JsonCodec<I> input, JsonCodec<O> output) {
        ServiceName.qualified(name);

        if (input == null || output == null) throw new NullPointerException();

        this.name = name;
        this.input = input;
        this.output = output;
    }
}
