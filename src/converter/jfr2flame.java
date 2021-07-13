/*
 * Copyright 2020 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import one.jfr.ClassRef;
import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.MethodRef;
import one.jfr.StackTrace;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.jfr.event.EventAggregator;
import one.jfr.event.ExecutionSample;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Converts .jfr output produced by async-profiler to HTML Flame Graph.
 */
public class jfr2flame {

    private static final int FRAME_KERNEL = 5;

    private final JfrReader jfr;
    private final Dictionary<String> methodNames = new Dictionary<>();

    public jfr2flame(JfrReader jfr) {
        this.jfr = jfr;
    }

    public void convert(final FlameGraph fg, final boolean threads, final boolean total,
                        final Class<? extends Event> eventClass) {
        EventAggregator agg = new EventAggregator(threads, total);
        for (Event event; (event = jfr.readEvent(eventClass)) != null; ) {
            agg.collect(event);
        }

        // Don't use lambda for faster startup
        agg.forEach(new EventAggregator.Visitor() {
            @Override
            public void visit(Event event, long value) {
                StackTrace stackTrace = jfr.stackTraces.get(event.stackTraceId);
                if (stackTrace != null) {
                    long[] methods = stackTrace.methods;
                    byte[] types = stackTrace.types;
                    String classFrame = getClassFrame(event);
                    String[] trace = new String[methods.length + (threads ? 1 : 0) + (classFrame != null ? 1 : 0)];
                    if (threads) {
                        trace[0] = getThreadFrame(event.tid);
                    }
                    int idx = trace.length;
                    if (classFrame != null) {
                        trace[--idx] = classFrame;
                    }
                    for (int i = 0; i < methods.length; i++) {
                        trace[--idx] = getMethodName(methods[i], types[i]);
                    }
                    fg.addSample(trace, value, 0);
                }
            }
        });
    }

    private String getThreadFrame(int tid) {
        String threadName = jfr.threads.get(tid);
        return threadName == null ? "[tid=" + tid + ']' : '[' + threadName + " tid=" + tid + ']';
    }

    private String getClassFrame(Event event) {
        long classId;
        String suffix;
        if (event instanceof AllocationSample) {
            classId = ((AllocationSample) event).classId;
            suffix = ((AllocationSample) event).tlabSize == 0 ? "_[k]" : "_[i]";
        } else if (event instanceof ContendedLock) {
            classId = ((ContendedLock) event).classId;
            suffix = "_[i]";
        } else {
            return null;
        }

        ClassRef cls = jfr.classes.get(classId);
        byte[] className = jfr.symbols.get(cls.name);

        int arrayDepth = 0;
        while (className[arrayDepth] == '[') {
            arrayDepth++;
        }

        StringBuilder sb = new StringBuilder(toJavaClassName(className, arrayDepth));
        while (arrayDepth-- > 0) {
            sb.append("[]");
        }
        return sb.append(suffix).toString();
    }

    private String toJavaClassName(byte[] symbol, int start) {
        switch (symbol[start]) {
            case 'B':
                return "byte";
            case 'C':
                return "char";
            case 'S':
                return "short";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'Z':
                return "boolean";
            case 'F':
                return "float";
            case 'D':
                return "double";
            case 'L':
                return new String(symbol, start + 1, symbol.length - start - 2, StandardCharsets.UTF_8).replace('/', '.');
            default:
                return new String(symbol, start, symbol.length - start, StandardCharsets.UTF_8).replace('/', '.');
        }
    }

    private String getMethodName(long methodId, int type) {
        String result = methodNames.get(methodId);
        if (result != null) {
            return result;
        }

        MethodRef method = jfr.methods.get(methodId);
        ClassRef cls = jfr.classes.get(method.cls);
        byte[] className = jfr.symbols.get(cls.name);
        byte[] methodName = jfr.symbols.get(method.name);

        if (className == null || className.length == 0) {
            String methodStr = new String(methodName, StandardCharsets.UTF_8);
            result = type == FRAME_KERNEL ? methodStr + "_[k]" : methodStr;
        } else {
            String classStr = new String(className, StandardCharsets.UTF_8);
            String methodStr = new String(methodName, StandardCharsets.UTF_8);
            result = classStr + '.' + methodStr + "_[j]";
        }

        methodNames.put(methodId, result);
        return result;
    }

    public static void main(String[] args) throws Exception {
        FlameGraph fg = new FlameGraph(args);
        if (fg.input1 == null) {
            System.out.println("Usage: java " + jfr2flame.class.getName() + " [options] input.jfr [output.html]");
            System.out.println();
            System.out.println("options include all supported FlameGraph options, plus the following:");
            System.out.println("  --alloc    Allocation Flame Graph");
            System.out.println("  --lock     Lock contention Flame Graph");
            System.out.println("  --threads  Split profile by threads");
            System.out.println("  --total    Accumulate the total value (time, bytes, etc.)");
            System.exit(1);
        }

        HashSet<String> options = new HashSet<>(Arrays.asList(args));
        boolean threads = options.contains("--threads");
        boolean total = options.contains("--total");

        Class<? extends Event> eventClass;
        if (options.contains("--alloc")) {
            eventClass = AllocationSample.class;
        } else if (options.contains("--lock")) {
            eventClass = ContendedLock.class;
        } else {
            eventClass = ExecutionSample.class;
        }

        try (JfrReader jfr = new JfrReader(fg.input1)) {
            new jfr2flame(jfr).convert(fg, threads, total, eventClass);
        }

        fg.dump();
    }
}
