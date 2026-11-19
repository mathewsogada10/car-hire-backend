package ke.carhire.models;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public record VerifiedExport(
    JsonNode manifest, List<Row> rows, Map<String, byte[]> files, String hash) {}
