package com.fleetpulse.normalizer.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class MappingCompilerTest {

    private MappingCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new MappingCompiler(new ObjectMapper());
    }

    @Test
    void testCompileOemA() throws Exception {
        Path path = findConfigPath("oem_a.json");
        String content = Files.readString(path);

        CompiledMapping mapping = compiler.compile(content);
        assertEquals("A", mapping.oem());
        assertEquals(1, mapping.schemaVer());
        assertEquals("json", mapping.format());
        assertEquals("vehicleId", mapping.vinField());
        assertEquals("ACTIVE", mapping.status());
        assertTrue(mapping.rules().size() >= 10);
    }

    @Test
    void testCompileOemDDelimited() throws Exception {
        Path path = findConfigPath("oem_d.json");
        String content = Files.readString(path);

        CompiledMapping mapping = compiler.compile(content);
        assertEquals("D", mapping.oem());
        assertEquals(1, mapping.schemaVer());
        assertEquals("delimited", mapping.format());
        assertEquals("|", mapping.delimiter());
        assertEquals("1", mapping.vinField());
    }

    private Path findConfigPath(String filename) {
        Path p1 = Paths.get("../../config/oem-mappings", filename);
        if (Files.exists(p1)) return p1;
        Path p2 = Paths.get("config/oem-mappings", filename);
        if (Files.exists(p2)) return p2;
        throw new IllegalStateException("Could not find " + filename);
    }
}
