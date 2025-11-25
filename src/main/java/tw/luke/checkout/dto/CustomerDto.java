package tw.luke.checkout.dto;

public record CustomerDto(
        String name,
        String phone,
        String email
) {}