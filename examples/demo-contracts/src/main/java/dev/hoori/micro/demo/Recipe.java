package dev.hoori.micro.demo;

import hoori.rest.codegen.GenerateJsonCodec;
import hoori.rest.json.JsonException;

/** Wire result with the same invariants as the previous response codec. */
@GenerateJsonCodec
public record Recipe(long id, String title) {
    public Recipe {
        if (id < 1 || title == null || title.isEmpty() || title.length() > 200)
            throw new JsonException("Invalid recipe");
    }
}
