package dev.hoori.micro.demo;

import hoori.rest.codegen.GenerateJsonCodec;
import hoori.rest.codegen.JsonList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsing stops at 16 elements; validation rejects null/non-positive IDs before the handler. */
@GenerateJsonCodec
@Valid
public record RecipeIds(
        @JsonList(max = 16) @NotNull @Size(max = 16) List<@NotNull @Positive Long> ids) {
    public RecipeIds {
        if (ids != null) ids = Collections.unmodifiableList(new ArrayList<>(ids));
    }
}
