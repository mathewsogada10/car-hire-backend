package ke.carhire.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

@Configuration
public class SecurityConfig {
  @Bean
  BCryptPasswordEncoder encoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  InMemoryUserDetailsManager users(
      @Value("${carhire.owner}") String owner,
      @Value("${carhire.password}") String password,
      BCryptPasswordEncoder encoder) {
    if (password.length() < 12)
      throw new IllegalStateException("OWNER_PASSWORD must contain at least 12 characters");
    return new InMemoryUserDetailsManager(
        User.withUsername(owner).password(encoder.encode(password)).roles("OWNER").build());
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/api/v1/auth/csrf", "/api/v1/auth/login")
                    .permitAll()
                    .anyRequest()
                    .hasRole("OWNER"))
        .addFilterBefore(
            new LoginThrottle(),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .httpBasic(Customizer.withDefaults())
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/v1/auth/login")
                    .successHandler(
                        (r, s, a) -> {
                          s.setContentType("application/json");
                          s.getWriter().write("{\"authenticated\":true}");
                        })
                    .failureHandler((r, s, e) -> s.sendError(401)))
        .logout(
            l ->
                l.logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler((r, s, a) -> s.setStatus(204)))
        .exceptionHandling(e -> e.authenticationEntryPoint((r, s, x) -> s.sendError(401)))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .build();
  }

  class LoginThrottle extends org.springframework.web.filter.OncePerRequestFilter {
    record Counter(int failures, long until) {}

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response,
        jakarta.servlet.FilterChain chain)
        throws java.io.IOException, jakarta.servlet.ServletException {
      boolean authentication =
          request.getRequestURI().equals("/api/v1/auth/login")
              || request.getHeader("Authorization") != null;
      if (!authentication) {
        chain.doFilter(request, response);
        return;
      }
      String key = request.getRemoteAddr();
      long now = System.currentTimeMillis();
      Counter prior = counters.get(key);
      if (prior != null && prior.failures >= 5 && prior.until > now) {
        response.setHeader("Retry-After", "300");
        response.sendError(429, "Too many failed sign-in attempts");
        return;
      }
      if (prior != null && prior.until < now) counters.remove(key, prior);
      chain.doFilter(request, response);
      if (response.getStatus() == 401)
        counters.compute(
            key,
            (k, c) ->
                new Counter(c == null ? 1 : c.failures + 1, System.currentTimeMillis() + 300000));
      else if (response.getStatus() < 400) counters.remove(key);
      if (counters.size() > 10000)
        counters.entrySet().removeIf(e -> e.getValue().until < System.currentTimeMillis());
    }
  }
}
