package hoori.micro;

import hoori.rest.json.JsonCodec;
import hoori.validation.DtoValidator;

/**
 * Typed contract of one remote action: "service.action" plus wire codecs. Deliberately no host,
 * URL, route or version; the caller's dependsOn() declares the compatible major version.
 */
public final class Action<I, O> {
    public final String name;
    public final JsonCodec<I> input;
    public final JsonCodec<O> output;
    public final DtoValidator<I> validator;
    final String service, operation;

    public Action(String name, JsonCodec<I> input, JsonCodec<O> output) {
        this(name, input, output, null);
    }

    /** Optional input validation, enforced by the receiving service before its handler. */
    public Action(String name, JsonCodec<I> input, JsonCodec<O> output, DtoValidator<I> validator) {
        String[] parts = ServiceName.qualified(name);

        if (input == null || output == null) throw new NullPointerException();

        this.name = name;
        this.input = input;
        this.output = output;
        this.validator = validator;
        service = parts[0];
        operation = parts[1];
    }
}
