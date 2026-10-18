package com.homelabhub.internal.config;

/** Answers "is a secret with this name available?". Values are never exposed through this interface. */
public interface SecretLookup {
    boolean isSet(String name);
}
