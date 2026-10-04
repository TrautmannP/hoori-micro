package dev.hoori.micro.demo;

import hoori.rest.codegen.GenerateJsonCodec;
import hoori.rest.codegen.JsonNumber;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/** Decimal strings are accepted explicitly for gateway path parameters. */
@GenerateJsonCodec
@Valid
public record GetRecipe(
        @JsonNumber(allowString = true) @Positive long id) {}
