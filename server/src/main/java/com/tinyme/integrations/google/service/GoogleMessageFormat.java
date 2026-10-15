package com.tinyme.integrations.google.service;

public enum GoogleMessageFormat {
    MINIMAL("minimal"),
    METADATA("metadata"),
    FULL("full");

    private final String apiValue;
    GoogleMessageFormat(String apiValue) { this.apiValue = apiValue; }
    public String apiValue() { return apiValue; }
}
