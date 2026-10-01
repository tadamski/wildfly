package org.jboss.as.test.integration.ejb.access.log.util;

/**
 * Holder for a single EJB access log line.
 *
 * <p>In v1 all records are emitted as JSON by {@code JsonEventFormatter}. The raw line is
 * retrieved with {@link #getLine()} and parsed via {@code jakarta.json.Json.createReader}.
 * The individual field accessors below are retained for source compatibility but are not
 * populated by the test infrastructure.
 */
public class AccessLog {
    private String line;

    public AccessLog(String line) {
        this.line = line;
    }

    public String getLine() {
        return line;
    }

    public void setLine(String line) {
        this.line = line;
    }
}
