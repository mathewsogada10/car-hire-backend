package ke.carhire.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import ke.carhire.exceptions.ApiError;
import org.springframework.stereotype.Component;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object o) {
    try {
      return mapper.writeValueAsString(o);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Serialization failed", e);
    }
  }

  public JsonNode read(String s) {
    try {
      return mapper.readTree(s);
    } catch (java.io.IOException e) {
      throw ApiError.invalid("Invalid JSON");
    }
  }

  public ObjectNode object() {
    return mapper.createObjectNode();
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public String hash(Object o) {
    return hash(write(canonical(mapper.valueToTree(o))).getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode canonical(JsonNode n) {
    if (n.isObject()) {
      ObjectNode o = object();
      java.util.TreeSet<String> keys = new java.util.TreeSet<>();
      n.fieldNames().forEachRemaining(keys::add);
      keys.forEach(k -> o.set(k, canonical(n.get(k))));
      return o;
    }
    if (n.isArray()) {
      var a = mapper.createArrayNode();
      n.forEach(v -> a.add(canonical(v)));
      return a;
    }
    return n;
  }

  public <T> T convert(Object value, Class<T> type) {
    return mapper.convertValue(value, type);
  }

  public <T> T readTree(JsonNode value, Class<T> type)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    return mapper.treeToValue(value, type);
  }

  public JsonNode tree(Object value) {
    return mapper.valueToTree(value);
  }

  public java.util.Map<String, Object> map(Object value) {
    return mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
  }
}
