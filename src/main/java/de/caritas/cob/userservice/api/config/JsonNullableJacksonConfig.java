package de.caritas.cob.userservice.api.config;

import java.util.List;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Jackson 3 support for the generated clients' {@link JsonNullable} fields ({@code nullable: true}
 * in services/*.yaml). jackson-databind-nullable only ships a Jackson 2 module, so Boot's
 * JsonMapper (and with it the shared RestTemplate) could not read e.g. {@code
 * RestrictedTenantDTO.active}. In request bodies of the generated clients an undefined value is
 * left out and an explicit null is written as null, as in the Jackson 2 module. Own API responses
 * keep the CustomWebMvcConfigurer behaviour (undefined written as null).
 */
@Configuration
public class JsonNullableJacksonConfig {

  @Bean
  public JacksonModule openApiJsonNullableJackson3Module() {
    return new SimpleModule("OpenApiJsonNullableJackson3")
        .addSerializer(JsonNullable.class, new JsonNullableSerializer())
        .addDeserializer(JsonNullable.class, new JsonNullableDeserializer(null))
        .setSerializerModifier(new SkipUndefinedModifier());
  }

  /** Only the generated client models ({@code *.generated.*}); own DTOs are unchanged. */
  static class SkipUndefinedModifier extends ValueSerializerModifier {

    @Override
    public List<BeanPropertyWriter> changeProperties(
        SerializationConfig config,
        BeanDescription.Supplier beanDesc,
        List<BeanPropertyWriter> properties) {
      if (!beanDesc.getBeanClass().getPackageName().contains(".generated.")) {
        return properties;
      }
      properties.replaceAll(
          writer ->
              writer.getType().hasRawClass(JsonNullable.class)
                  ? new SkipUndefinedWriter(writer)
                  : writer);
      return properties;
    }
  }

  static class SkipUndefinedWriter extends BeanPropertyWriter {

    SkipUndefinedWriter(BeanPropertyWriter base) {
      super(base);
    }

    @Override
    public void serializeAsProperty(
        Object bean, JsonGenerator generator, SerializationContext context) throws Exception {
      if (get(bean) instanceof JsonNullable<?> value && !value.isPresent()) {
        return;
      }
      super.serializeAsProperty(bean, generator, context);
    }
  }

  @SuppressWarnings("rawtypes")
  static class JsonNullableSerializer extends ValueSerializer<JsonNullable> {

    @Override
    public void serialize(JsonNullable value, JsonGenerator generator, SerializationContext context)
        throws JacksonException {
      context.writeValue(generator, value.orElse(null));
    }

    @Override
    public boolean isEmpty(SerializationContext context, JsonNullable value) {
      return value == null || !value.isPresent();
    }
  }

  @SuppressWarnings("rawtypes")
  static class JsonNullableDeserializer extends ValueDeserializer<JsonNullable> {

    private final JavaType contentType;

    JsonNullableDeserializer(JavaType contentType) {
      this.contentType = contentType;
    }

    @Override
    public ValueDeserializer<?> createContextual(
        DeserializationContext context, BeanProperty property) {
      JavaType type = property != null ? property.getType() : context.getContextualType();
      return new JsonNullableDeserializer(type == null ? null : type.containedTypeOrUnknown(0));
    }

    @Override
    public JsonNullable deserialize(JsonParser parser, DeserializationContext context)
        throws JacksonException {
      JavaType type = contentType != null ? contentType : context.constructType(Object.class);
      return JsonNullable.of(context.readValue(parser, type));
    }

    /** An explicit JSON {@code null} is a present null, as in the Jackson 2 module. */
    @Override
    public JsonNullable getNullValue(DeserializationContext context) {
      return JsonNullable.of(null);
    }
  }
}
