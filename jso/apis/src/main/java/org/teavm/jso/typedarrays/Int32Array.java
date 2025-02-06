/*
 *  Copyright 2015 Alexey Andreev.
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
package org.teavm.jso.typedarrays;

import java.nio.Buffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSBuffer;
import org.teavm.jso.JSBufferType;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSIndexer;

@JSClass
public class Int32Array extends TypedArray {
    public Int32Array(int length) {
    }

    public Int32Array(ArrayBuffer buffer) {
    }

    public Int32Array(TypedArray buffer) {
    }

    public Int32Array(ArrayBuffer buffer, int offset, int length) {
    }

    public Int32Array(ArrayBuffer buffer, int offset) {
    }

    @JSIndexer
    public native int get(int index);

    @JSIndexer
    public native void set(int index, int value);

    @Override
    public native void set(@JSByRef int[] data, int offset);

    @Override
    public native void set(@JSByRef int[] data);

    @JSBody(params = "length", script = "return new Int32Array(length);")
    @Deprecated
    public static native Int32Array create(int length);

    @JSBody(params = "buffer", script = "return new Int32Array(buffer);")
    @Deprecated
    public static native Int32Array create(ArrayBuffer buffer);

    @JSBody(params = "buffer", script = "return new Int32Array(buffer);")
    @Deprecated
    public static native Int32Array create(TypedArray buffer);

    @JSBody(params = { "buffer", "offset", "length" }, script = "return new Int32Array(buffer, offset, length);")
    @Deprecated
    public static native Int32Array create(ArrayBuffer buffer, int offset, int length);

    @JSBody(params = { "buffer", "offset" }, script = "return new Int32Array(buffer, offset);")
    @Deprecated
    public static native Int32Array create(ArrayBuffer buffer, int offset);

    @JSBody(params = "array", script = "return array;")
    public static native Int32Array fromJavaArray(@JSByRef int[] array);

    @JSBody(params = "buffer", script = "return buffer;")
    public static native Int32Array fromJavaBuffer(@JSBuffer(JSBufferType.INT32) Buffer buffer);
}
