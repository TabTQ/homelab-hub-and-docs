package com.homelabhub.docs.github;

import java.io.IOException;
import java.util.Map;

/** Builds the whole site as it should be right now: path in the repository → content. */
@FunctionalInterface
public interface SiteSource {

    Map<String, SiteContent> build() throws IOException;
}
