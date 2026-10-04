package dev.hoori.micro.demo;

import hoori.rest.codegen.GenerateJsonCodec;
import hoori.rest.codegen.JsonList;
import hoori.rest.json.JsonException;
import java.util.List;

/** Typed result composed from two independently owned read models. */
@GenerateJsonCodec
public record Overview(Recipe recipe, @JsonList(max = 16) List<String> available) {
    public Overview {
        if (recipe == null || available == null) throw new JsonException("Incomplete overview");

        for (String item : available)
            if (item == null || item.isEmpty() || item.length() > 200) throw new JsonException("Invalid pantry items");
        available = List.copyOf(available);
    }
}
