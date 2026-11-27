package ke.carhire.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ke.carhire.exceptions.ApiError;
import ke.carhire.models.Row;
import ke.carhire.repositories.RecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HireConflictTest {
  private final RecordRepository records = mock(RecordRepository.class);
  private final DomainPolicy policy = new DomainPolicy(records);
  private final UUID vehicle = UUID.randomUUID();
  private final ObjectMapper json = new ObjectMapper();

  private Row hire(String extra) throws Exception {
    var data = json.createObjectNode();
    data.put("vehicleId", vehicle.toString());
    data.put("plannedStart", "2026-12-01T07:00:00Z");
    data.put("plannedEnd", "2026-12-02T09:00:00Z");
    data.put("chargeMinor", 250099);
    data.put("status", "Booked");
    data.setAll((com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(extra));
    var row = new Row(UUID.randomUUID(), "hires", 1, data, Instant.EPOCH, Instant.EPOCH, null);
    when(records.all("hires")).thenReturn(List.of(row));
    return row;
  }

  @Test
  void overlapShowsDatesRoundedDaysAndChargeInsteadOfAnId() throws Exception {
    var hire = hire("{}");
    var error =
        assertThrows(
            ApiError.class,
            () ->
                policy.overlap(
                    vehicle,
                    Instant.parse("2026-12-01T08:00:00Z"),
                    Instant.parse("2026-12-03T08:00:00Z"),
                    UUID.randomUUID()));
    assertEquals(HttpStatus.CONFLICT, error.status());
    assertEquals(
        "Vehicle has an overlapping hire: 2026-12-01 10:00 - 2026-12-02 12:00 | Days:2 | KES 2,500.99",
        error.getMessage());
    assertFalse(error.getMessage().contains(hire.id().toString()));
    assertFalse(error.detail().toString().contains(hire.id().toString()));
    assertTrue(error.detail().toString().contains("Days:2"));
    policy.overlap(
        vehicle,
        Instant.parse("2026-12-02T09:00:00Z"),
        Instant.parse("2026-12-03T09:00:00Z"),
        UUID.randomUUID());
  }

  @Test
  void completedHireUsesActualTimesAndOverdueActiveHireKeepsReadableDates() throws Exception {
    hire(
        """
        {"status":"Completed","actualStart":"2026-12-02T07:00:00Z","actualEnd":"2026-12-02T08:00:00Z"}
        """);
    var error =
        assertThrows(
            ApiError.class,
            () ->
                policy.overlap(
                    vehicle,
                    Instant.parse("2026-12-02T07:30:00Z"),
                    Instant.parse("2026-12-03T08:00:00Z"),
                    UUID.randomUUID()));
    assertTrue(error.getMessage().contains("2026-12-02 10:00 - 2026-12-02 11:00 | Days:1"));
    hire(
        """
        {"status":"Active","plannedStart":"2020-01-01T07:00:00Z","plannedEnd":"2020-01-02T07:00:00Z"}
        """);
    error =
        assertThrows(
            ApiError.class,
            () ->
                policy.overlap(
                    vehicle,
                    Instant.parse("2035-12-02T07:30:00Z"),
                    Instant.parse("2035-12-03T08:00:00Z"),
                    UUID.randomUUID()));
    assertTrue(error.getMessage().contains("2020-01-01 10:00 - 2020-01-02 10:00 | Days:1"));
  }
}
