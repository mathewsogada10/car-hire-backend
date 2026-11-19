package ke.carhire.models;

import java.util.UUID;

public record ExportPackage(UUID id, byte[] bytes) {}
