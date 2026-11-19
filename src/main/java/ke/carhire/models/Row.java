package ke.carhire.models;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Row(
    UUID id,
    String kind,
    long revision,
    @Schema(
            implementation = Map.class,
            type = "object",
            additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        JsonNode data,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {}
