package com.careflow.hms.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class JacksonConfig {

    /**
     * Monetary and measured values are serialized as JSON strings ("1250.00") so clients never
     * lose precision by parsing them into binary floating point.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer decimalAsString() {
        SimpleModule module = new SimpleModule("decimal-as-string");
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        return builder -> builder.modulesToInstall(module);
    }
}
