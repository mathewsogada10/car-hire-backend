package ke.carhire.models;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Confirmation")
public record PurgeConfirmation(PurgeRequest scope, String previewHash, String confirmation) {}
