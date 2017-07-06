/*
 * Copyright 2016 Andrei Pangin
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

#include <fstream>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <cxxabi.h>
#include <sys/param.h>
#include "profiler.h"
#include "perfEvent.h"
#include "stackFrame.h"
#include "symbols.h"


Profiler Profiler::_instance;

static void sigprofHandler(int signo, siginfo_t* siginfo, void* ucontext) {
    Profiler::_instance.recordSample(ucontext);
    PerfEvent::reenable(siginfo);
}

static inline u64 atomicInc(u64& var) {
    return __sync_fetch_and_add(&var, 1);
}


class MethodName {
  private:
    char _buf[520];
    const char* _str;

    char* fixClassName(char* name, bool dotted) {
        if (dotted) {
            for (char* s = name + 1; *s; s++) {
                if (*s == '/') *s = '.';
            }
        }

        // Class signature is a string of form 'Ljava/lang/Thread;'
        // So we have to remove the first 'L' and the last ';'
        name[strlen(name) - 1] = 0;
        return name + 1;
    }

    const char* demangle(const char* name) {
        if (name != NULL && name[0] == '_' && name[1] == 'Z') {
            int status;
            char* demangled = abi::__cxa_demangle(name, NULL, NULL, &status);
            if (demangled != NULL) {
                strncpy(_buf, demangled, sizeof(_buf));
                free(demangled);
                return _buf;
            }
        }
        return name;
    }

  public:
    MethodName(ASGCT_CallFrame& frame, bool dotted = false) {
        if (frame.method_id == NULL) {
            _str = "[unknown]";
        } else if (frame.bci == BCI_NATIVE_FRAME) {
            _str = demangle((const char*)frame.method_id);
        } else {
            jclass method_class;
            char* class_name = NULL;
            char* method_name = NULL;

            jvmtiEnv* jvmti = VM::jvmti();
            jvmtiError err;

            if ((err = jvmti->GetMethodName(frame.method_id, &method_name, NULL, NULL)) == 0 &&
                (err = jvmti->GetMethodDeclaringClass(frame.method_id, &method_class)) == 0 &&
                (err = jvmti->GetClassSignature(method_class, &class_name, NULL)) == 0) {
                snprintf(_buf, sizeof(_buf), "%s.%s", fixClassName(class_name, dotted), method_name);
            } else {
                snprintf(_buf, sizeof(_buf), "[jvmtiError %d]", err);
            }
            _str = _buf;

            jvmti->Deallocate((unsigned char*)class_name);
            jvmti->Deallocate((unsigned char*)method_name);
        }
    }

    const char* toString() {
        return _str;
    }
};


u64 Profiler::hashCallTrace(int num_frames, ASGCT_CallFrame* frames) {
    const u64 M = 0xc6a4a7935bd1e995ULL;
    const int R = 47;

    u64 h = num_frames * M;

    for (int i = 0; i < num_frames; i++) {
        u64 k = (u64)frames[i].method_id;
        k *= M;
        k ^= k >> R;
        k *= M;
        h ^= k;
        h *= M;
    }

    h ^= h >> R;
    h *= M;
    h ^= h >> R;

    return h;
}

void Profiler::storeCallTrace(int num_frames, ASGCT_CallFrame* frames) {
    u64 hash = hashCallTrace(num_frames, frames);
    int bucket = (int)(hash % MAX_CALLTRACES);
    int i = bucket;

    while (_hashes[i] != hash) {
        if (_hashes[i] == 0) {
            if (__sync_bool_compare_and_swap(&_hashes[i], 0, hash)) {
                copyToFrameBuffer(num_frames, frames, &_traces[i]);
                break;
            }
            continue;
        }

        if (++i == MAX_CALLTRACES) i = 0;  // move to next slot
        if (i == bucket) return;           // the table is full
    }
    
    // CallTrace hash found => atomically increment counter
    atomicInc(_traces[i]._counter);
}

void Profiler::copyToFrameBuffer(int num_frames, ASGCT_CallFrame* frames, CallTraceSample* trace) {
    // Atomically reserve space in frame buffer
    int start_frame;
    do {
        start_frame = _frame_buffer_index;
        if (start_frame + num_frames > _frame_buffer_size) {
            _frame_buffer_overflow = true;  // not enough space to store full trace
            return;
        }
    } while (!__sync_bool_compare_and_swap(&_frame_buffer_index, start_frame, start_frame + num_frames));

    trace->_start_frame = start_frame;
    trace->_num_frames = num_frames;

    for (int i = 0; i < num_frames; i++) {
        _frame_buffer[start_frame++] = frames[i];
    }
}

u64 Profiler::hashMethod(jmethodID method) {
    const u64 M = 0xc6a4a7935bd1e995ULL;
    const int R = 17;

    u64 h = (u64)method;

    h ^= h >> R;
    h *= M;
    h ^= h >> R;

    return h;
}

void Profiler::storeMethod(jmethodID method, jint bci) {
    u64 hash = hashMethod(method);
    int bucket = (int)(hash % MAX_CALLTRACES);
    int i = bucket;

    while (_methods[i]._method.method_id != method) {
        if (_methods[i]._method.method_id == NULL) {
            if (__sync_bool_compare_and_swap(&_methods[i]._method.method_id, NULL, method)) {
                _methods[i]._method.bci = bci;
                break;
            }
            continue;
        }
        
        if (++i == MAX_CALLTRACES) i = 0;  // move to next slot
        if (i == bucket) return;           // the table is full
    }

    // Method found => atomically increment counter
    atomicInc(_methods[i]._counter);
}

void Profiler::addJavaMethod(const void* address, int length, jmethodID method) {
    _jit_lock.lock();
    _java_methods.add(address, length, method);
    updateJitRange(address, (const char*)address + length);
    _jit_lock.unlock();
}

void Profiler::removeJavaMethod(const void* address, jmethodID method) {
    _jit_lock.lock();
    _java_methods.remove(address, method);
    _jit_lock.unlock();
}

void Profiler::addRuntimeStub(const void* address, int length, const char* name) {
    _jit_lock.lock();
    _runtime_stubs.add(address, length, name);
    updateJitRange(address, (const char*)address + length);
    _jit_lock.unlock();
}

void Profiler::updateJitRange(const void* min_address, const void* max_address) {
    if (min_address < _jit_min_address) _jit_min_address = min_address;
    if (max_address > _jit_max_address) _jit_max_address = max_address;
}

const char* Profiler::findNativeMethod(const void* address) {
    for (int i = 0; i < _native_lib_count; i++) {
        if (_native_libs[i]->contains(address)) {
            return _native_libs[i]->binary_search(address);
        }
    }
    return NULL;
}

int Profiler::getNativeTrace(void* ucontext, ASGCT_CallFrame* frames) {
    const void* native_callchain[MAX_NATIVE_FRAMES];
    int native_frames = PerfEvent::getCallChain(native_callchain, MAX_NATIVE_FRAMES);

    for (int i = 0; i < native_frames; i++) {
        const void* address = native_callchain[i];
        if (address >= _jit_min_address && address < _jit_max_address) {
            return i;
        }
        frames[i].bci = BCI_NATIVE_FRAME;
        frames[i].method_id = (jmethodID)findNativeMethod(address);
    }

    return native_frames;
}

int Profiler::getJavaTrace(void* ucontext, ASGCT_CallFrame* frames, int max_depth) {
    JNIEnv* jni = VM::jni();
    if (jni == NULL) {
        atomicInc(_failures[-ticks_no_Java_frame]);
        return 0;
    }

    ASGCT_CallTrace trace = {jni, 0, frames};
    VM::asyncGetCallTrace(&trace, max_depth, ucontext);

    if (trace.num_frames == ticks_unknown_Java) {
        // If current Java stack is not walkable (e.g. the top frame is not fully constructed),
        // try to manually pop the top frame off, hoping that the previous frame is walkable.
        // This is a temporary workaround for AsyncGetCallTrace issues,
        // see https://bugs.openjdk.java.net/browse/JDK-8178287
        StackFrame top_frame(ucontext);
        if (top_frame.pop()) {
            // Guess top method by PC and insert it manually into the call trace
            if (fillTopFrame(top_frame.pc(), trace.frames)) {
                trace.frames++;
                max_depth--;
            }

            // Retry with the fixed context
            VM::asyncGetCallTrace(&trace, max_depth, ucontext);

            if (trace.num_frames > 0) {
                return trace.num_frames + (trace.frames - frames);
            }

            // Restore previous context
            trace.num_frames = ticks_unknown_Java;
        }
    }

    if (trace.num_frames > 0) {
        return trace.num_frames;
    }

    // Record failure
    int type = -trace.num_frames < FAILURE_TYPES ? -trace.num_frames : -ticks_unknown_state;
    atomicInc(_failures[type]);
    return 0;
}

bool Profiler::fillTopFrame(const void* pc, ASGCT_CallFrame* frame) {
    jmethodID method = NULL;
    _jit_lock.lockShared();

    // Check if PC lies within JVM's compiled code cache
    if (pc >= _jit_min_address && pc < _jit_max_address) {
        if ((method = _java_methods.find(pc)) != NULL) {
            // PC belong to a JIT compiled method
            frame->bci = 0;
            frame->method_id = method;
        } else if ((method = _runtime_stubs.find(pc)) != NULL) {
            // PC belongs to a VM runtime stub
            frame->bci = BCI_NATIVE_FRAME;
            frame->method_id = method;
        }
    }

    _jit_lock.unlockShared();
    return method != NULL;
}

void Profiler::recordSample(void* ucontext) {
    u64 lock_index = atomicInc(_samples) % CONCURRENCY_LEVEL;
    if (!_locks[lock_index].tryLock()) {
        atomicInc(_failures[-ticks_skipped]);  // too many concurrent signals already
        return;
    }

    ASGCT_CallFrame* frames = _asgct_buffer[lock_index];
    int num_frames = getNativeTrace(ucontext, frames);
    num_frames += getJavaTrace(ucontext, frames + num_frames, MAX_STACK_FRAMES - num_frames);

    if (num_frames > 0) {
        storeCallTrace(num_frames, frames);
        storeMethod(frames[0].method_id, frames[0].bci);
    }

    _locks[lock_index].unlock();
}

void Profiler::resetSymbols() {
    for (int i = 0; i < _native_lib_count; i++) {
        delete _native_libs[i];
    }
    _native_lib_count = Symbols::parseMaps(_native_libs, MAX_NATIVE_LIBS);
}

void Profiler::setSignalHandler() {
    struct sigaction sa;
    sigemptyset(&sa.sa_mask);
    sa.sa_handler = NULL;
    sa.sa_sigaction = sigprofHandler;
    sa.sa_flags = SA_RESTART | SA_SIGINFO;

    if (sigaction(SIGPROF, &sa, NULL)) {
        perror("sigaction failed");
    }
}

bool Profiler::start(int interval, int frame_buffer_size) {
    if (interval <= 0) return false;

    MutexLocker ml(_state_lock);
    if (_state != IDLE) return false;
    _state = RUNNING;
    _start_time = time(NULL);

    _samples = 0;
    memset(_failures, 0, sizeof(_failures));
    memset(_hashes, 0, sizeof(_hashes));
    memset(_traces, 0, sizeof(_traces));
    memset(_methods, 0, sizeof(_methods));

    // Reset frames
    free(_frame_buffer);
    _frame_buffer_size = frame_buffer_size;
    _frame_buffer = (ASGCT_CallFrame*)malloc(_frame_buffer_size * sizeof(ASGCT_CallFrame));
    _frame_buffer_index = 0;
    _frame_buffer_overflow = false;

    resetSymbols();
    setSignalHandler();
    
    PerfEvent::start(interval);
    return true;
}

bool Profiler::stop() {
    MutexLocker ml(_state_lock);
    if (_state != RUNNING) return false;
    _state = IDLE;

    PerfEvent::stop();
    return true;
}

void Profiler::dumpSummary(std::ostream& out) {
    static const char* title[FAILURE_TYPES] = {
        "Non-Java:",
        "JVM not initialized:",
        "GC active:",
        "Unknown (native):",
        "Not walkable (native):",
        "Unknown (Java):",
        "Not walkable (Java):",
        "Unknown state:",
        "Thread exit:",
        "Deopt:",
        "Safepoint:",
        "Skipped:"
    };

    char buf[256];
    snprintf(buf, sizeof(buf),
            "--- Execution profile ---\n"
            "Total:                 %lld\n",
            _samples);
    out << buf;
    
    double percent = 100.0 / _samples;
    for (int i = 0; i < FAILURE_TYPES; i++) {
        if (_failures[i] > 0) {
            snprintf(buf, sizeof(buf), "%-22s %lld (%.2f%%)\n", title[i], _failures[i], _failures[i] * percent);
            out << buf;
        }
    }
    out << std::endl;

    if (_frame_buffer_overflow) {
        out << "Frame buffer overflowed! Consider increasing its size." << std::endl;
    } else {
        double usage = 100.0 * _frame_buffer_index / _frame_buffer_size;
        out << "Frame buffer usage:    " << usage << "%" << std::endl;
    }
    out << std::endl;
}

/*
 * Dump traces in FlameGraph format:
 * 
 * <frame>;<frame>;...;<topmost frame> <count>
 */
void Profiler::dumpFlameGraph(std::ostream& out) {
    MutexLocker ml(_state_lock);
    if (_state != IDLE) return;

    for (int i = 0; i < MAX_CALLTRACES; i++) {
        u64 samples = _traces[i]._counter;
        if (samples == 0) continue;
        
        CallTraceSample& trace = _traces[i];
        for (int j = trace._num_frames - 1; j >= 0; j--) {
            MethodName mn(_frame_buffer[trace._start_frame + j]);
            out << mn.toString() << (j == 0 ? ' ' : ';');
        }
        out << samples << "\n";
    }
}

void Profiler::dumpTraces(std::ostream& out, int max_traces) {
    MutexLocker ml(_state_lock);
    if (_state != IDLE) return;

    double percent = 100.0 / _samples;
    char buf[1024];

    qsort(_traces, MAX_CALLTRACES, sizeof(CallTraceSample), CallTraceSample::comparator);
    if (max_traces > MAX_CALLTRACES) max_traces = MAX_CALLTRACES;

    for (int i = 0; i < max_traces; i++) {
        u64 samples = _traces[i]._counter;
        if (samples == 0) break;

        snprintf(buf, sizeof(buf), "Samples: %lld (%.2f%%)\n", samples, samples * percent);
        out << buf;

        CallTraceSample& trace = _traces[i];
        for (int j = 0; j < trace._num_frames; j++) {
            MethodName mn(_frame_buffer[trace._start_frame + j], true);
            snprintf(buf, sizeof(buf), "  [%2d] %s\n", j, mn.toString());
            out << buf;
        }
        out << "\n";
    }
}

void Profiler::dumpMethods(std::ostream& out, int max_methods) {
    MutexLocker ml(_state_lock);
    if (_state != IDLE) return;

    double percent = 100.0 / _samples;
    char buf[1024];

    qsort(_methods, MAX_CALLTRACES, sizeof(MethodSample), MethodSample::comparator);
    if (max_methods > MAX_CALLTRACES) max_methods = MAX_CALLTRACES;

    for (int i = 0; i < max_methods; i++) {
        u64 samples = _methods[i]._counter;
        if (samples == 0) break;

        MethodName mn(_methods[i]._method, true);
        snprintf(buf, sizeof(buf), "%10lld (%.2f%%) %s\n", samples, samples * percent, mn.toString());
        out << buf;
    }
}

void Profiler::runInternal(Arguments& args, std::ostream& out) {
    switch (args._action) {
        case ACTION_START:
            if (start(args._interval, args._framebuf)) {
                out << "Profiling started with interval " << args._interval << " ns" << std::endl;
            } else {
                out << "Profiler is already running for " << uptime() << " seconds" << std::endl;
            }
            break;
        case ACTION_STOP:
            if (stop()) {
                out << "Profiling stopped after " << uptime() << " seconds" << std::endl;
            } else {
                out << "Profiler is not active" << std::endl;
            }
            break;
        case ACTION_STATUS: {
            MutexLocker ml(_state_lock);
            if (_state == RUNNING) {
                out << "Profiler is running for " << uptime() << " seconds" << std::endl;
            } else {
                out << "Profiler is not active" << std::endl;
            }
            break;
        }
        case ACTION_DUMP:
            stop();
            if (args._dump_flamegraph) dumpFlameGraph(out);
            if (args._dump_summary) dumpSummary(out);
            if (args._dump_traces > 0) dumpTraces(out, args._dump_traces);
            if (args._dump_methods > 0) dumpMethods(out, args._dump_methods);
            break;
    }
}

void Profiler::run(Arguments& args) {
    if (args._file == NULL) {
        runInternal(args, std::cout);
    } else {
        std::ofstream out(args._file, std::ios::out | std::ios::trunc);
        if (out.is_open()) {
            runInternal(args, out);
            out.close();
        } else {
            std::cerr << "Could not open " << args._file << std::endl;
        }
    }
}
