package org.teavm.model.resource;

import java.io.IOException;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.teavm.common.Mapper;
import org.teavm.model.ClassHolder;
import org.teavm.parsing.Parser;

/**
 *
 * @author Alexey Andreev <konsoletyper@gmail.com>
 */
public class ResourceParser implements Mapper<String, ClassHolder> {
    private ResourceReader resourceReader;

    public ResourceParser(ResourceReader resourceReader) {
        this.resourceReader = resourceReader;
    }

    @Override
    public ClassHolder map(String name) {
        ClassNode clsNode = new ClassNode();
        try (InputStream input = resourceReader.openResource(name.replace('.', '/') + ".class")) {
            ClassReader reader = new ClassReader(input);
            reader.accept(clsNode, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Parser.parseClass(clsNode);
    }
}
