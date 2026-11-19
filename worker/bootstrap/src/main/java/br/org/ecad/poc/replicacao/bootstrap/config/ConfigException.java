package br.org.ecad.poc.replicacao.bootstrap.config;

/** Thrown when a configuration value is missing or malformed. The message is one line. */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
