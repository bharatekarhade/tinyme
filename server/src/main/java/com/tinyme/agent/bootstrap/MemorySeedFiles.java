package com.tinyme.agent.bootstrap;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Loads the same classpath memory templates for startup provisioning and a data reset. */
@Component
public class MemorySeedFiles {
    public Map<String, String> load() throws IOException {
        var result = new LinkedHashMap<String, String>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:seed/memories/*.md")) {
            result.put("/me/" + resource.getFilename(),
                    resource.getContentAsString(StandardCharsets.UTF_8));
        }
        if (result.isEmpty()) throw new IllegalStateException("No classpath memory seeds found");
        return Collections.unmodifiableMap(result);
    }
}
