package ke.carhire.controllers;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import ke.carhire.services.AuthService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService service;

  public AuthController(AuthService service) {
    this.service = service;
  }

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/me")
  Map<String, String> me() {
    return service.currentUser();
  }

  @PostMapping("/reauth")
  void reauth(@RequestBody Map<String, String> body, HttpServletRequest request) {
    service.verify(body.get("password"));
    request.getSession().setAttribute("reauth", Instant.now());
  }
}
