package dev.hoori.micro.contracts.dto;

public record Overview(Recipe recipe, java.util.List<String> available) {
    public Overview {
        if (recipe == null || available == null) throw new IllegalArgumentException("Missing overview data");

        available = java.util.List.copyOf(available);
    }
}
