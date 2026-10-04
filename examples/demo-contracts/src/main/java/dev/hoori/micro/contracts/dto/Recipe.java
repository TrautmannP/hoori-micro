package dev.hoori.micro.contracts.dto;

public record Recipe(long id, String title) {
    public Recipe {
        if (id < 1 || title == null || title.isBlank()) throw new IllegalArgumentException("Invalid recipe");
    }
}
