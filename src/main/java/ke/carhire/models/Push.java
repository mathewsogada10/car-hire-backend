package ke.carhire.models;

import java.util.List;

public record Push(List<Change> operations, String installationId) {}
