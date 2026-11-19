package ke.carhire.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;

public record Change(
    @JsonProperty(required = true) UUID operationId,
    @JsonProperty(required = true) UUID id,
    @JsonProperty(required = true) String kind,
    @JsonProperty(required = true) long baseRevision,
    @JsonProperty(required = true)
        @Schema(
            implementation = Map.class,
            type = "object",
            additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        ObjectNode data,
    @JsonProperty(required = true) boolean deleted,
    String reason) {}
