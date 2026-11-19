package ke.carhire.models;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "Request")
public record PurgeRequest(UUID exportBatchId, LocalDate through) {}
