/*
 *  Copyright 2023 Alexey Andreev.
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
package org.teavm.classlib.java.util.concurrent;

import static org.junit.Assert.assertTrue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.TeaVMTestRunner;
import org.teavm.junit.WholeClassCompilation;

@RunWith(TeaVMTestRunner.class)
@WholeClassCompilation
public class MultiThreadConcurrentHashMapTest {
    private ArrayBlockingQueue<Runnable> backgroundTasks = new ArrayBlockingQueue<>(100);
    private boolean stopped;

    public MultiThreadConcurrentHashMapTest() {
        var t = new Thread(() -> {
            while (!stopped) {
                try {
                    backgroundTasks.take().run();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        t.start();
    }

    @After
    public void dispose() {
        backgroundTasks.add(() -> stopped = true);
    }

    @Test
    public void containsValue() {
        var key = new Wrapper("q");
        var value = new Wrapper("23");
        var map = new ConcurrentHashMap<>(Map.of(key, value));

        assertTrue(map.containsValue(new Wrapper("23")));
        assertTrue(map.containsValue(new Wrapper("23", () -> map.remove(key))));
    }

    private void runInBackground(Runnable runnable) {
        backgroundTasks.add(runnable);
    }

    private class Wrapper {
        private final String s;
        private final Runnable task;

        Wrapper(String s) {
            this(s, null);
        }

        Wrapper(String s, Runnable task) {
            this.s = s;
            this.task = task;
        }

        @Override
        public boolean equals(Object o) {
            awaitIfNecessary();
            if (o instanceof Wrapper) {
                ((Wrapper) o).awaitIfNecessary();
            }

            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            var key = (Wrapper) o;
            return Objects.equals(s, key.s);
        }

        @Override
        public int hashCode() {
            awaitIfNecessary();
            return s.hashCode();
        }

        private void awaitIfNecessary() {
            if (task != null) {
                runInBackground(() -> {
                    task.run();
                    send();
                });
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        void send() {
            synchronized (this) {
                notifyAll();
            }
        }
    }
}
