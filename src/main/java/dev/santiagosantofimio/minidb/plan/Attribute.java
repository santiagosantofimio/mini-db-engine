package dev.santiagosantofimio.minidb.plan;

import java.util.Objects;
import java.util.Optional;

public record Attribute(Optional<String> qualifier, String name, ValueType type) {

    public Attribute {
        Objects.requireNonNull(qualifier, "qualifier");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public boolean matches(Optional<String> wantedQualifier, String wantedName) {
        return name.equals(wantedName) && (wantedQualifier.isEmpty() || wantedQualifier.equals(qualifier));
    }

    public String qualifiedName() {
        return qualifier.map(q -> q + "." + name).orElse(name);
    }
}
