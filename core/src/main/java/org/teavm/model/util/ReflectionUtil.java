/*
 *  Copyright 2024 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.model.util;

import org.teavm.model.PrimitiveType;

public final class ReflectionUtil {
    private ReflectionUtil() {
    }

    public static String typeName(PrimitiveType type) {
        switch (type) {
            case BOOLEAN:
                return "boolean";
            case BYTE:
                return "byte";
            case SHORT:
                return "short";
            case CHARACTER:
                return "char";
            case INTEGER:
                return "int";
            case LONG:
                return "long";
            case FLOAT:
                return "float";
            case DOUBLE:
                return "double";
            default:
                throw new IllegalArgumentException();
        }
    }
}
