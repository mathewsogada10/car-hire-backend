package ke.carhire.services;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ke.carhire.exceptions.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final InMemoryUserDetailsManager users;
  private final BCryptPasswordEncoder encoder;
  private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

  private record Attempt(int failures, Instant blockedUntil) {}

  public AuthService(InMemoryUserDetailsManager users, BCryptPasswordEncoder encoder) {
    this.users = users;
    this.encoder = encoder;
  }

  public Map<String, String> currentUser() {
    return Map.of(
        "username",
        SecurityContextHolder.getContext().getAuthentication().getName(),
        "role",
        "OWNER");
  }

  public synchronized void verify(String password) {
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    Attempt a = attempts.getOrDefault(actor, new Attempt(0, Instant.EPOCH));
    if (a.blockedUntil.isAfter(Instant.now()))
      throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts; retry later");
    if (password == null
        || !encoder.matches(password, users.loadUserByUsername(actor).getPassword())) {
      int failures = a.failures + 1;
      attempts.put(
          actor,
          new Attempt(failures, failures >= 5 ? Instant.now().plusSeconds(300) : Instant.EPOCH));
      throw new ApiError(HttpStatus.UNAUTHORIZED, "Reauthentication failed");
    }
    attempts.remove(actor);
  }

  public void requireRecent(Object value) {
    if (!(value instanceof Instant instant) || instant.isBefore(Instant.now().minusSeconds(120)))
      throw new ApiError(HttpStatus.FORBIDDEN, "Reauthenticate before this operation");
  }
}
