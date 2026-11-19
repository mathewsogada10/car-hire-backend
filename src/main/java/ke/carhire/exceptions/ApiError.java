package ke.carhire.exceptions;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
  private final HttpStatus status;
  private final Object detail;

  public ApiError(HttpStatus status, String message) {
    this(status, message, Map.of());
  }

  public ApiError(HttpStatus status, String message, Object detail) {
    super(message);
    this.status = status;
    this.detail = detail;
  }

  public static ApiError invalid(String message) {
    return new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  public HttpStatus status() {
    return status;
  }

  public Object detail() {
    return detail;
  }
}
