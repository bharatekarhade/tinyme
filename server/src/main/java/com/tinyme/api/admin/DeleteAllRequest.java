package com.tinyme.api.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeleteAllRequest(
        @JsonProperty("confirm") String confirm,
        @NotBlank @Size(max = 256) String password) { }
