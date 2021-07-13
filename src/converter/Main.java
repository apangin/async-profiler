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

/**
 * Main entry point of jar.
 * Lists available converters.
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("Usage: java -cp converter.jar <Converter> [options] <input> <output>");
        System.out.println();
        System.out.println("Available converters:");
        System.out.println("  FlameGraph input.collapsed output.html");
        System.out.println("  FlameGraph --diff input1.collapsed input2.collapsed output.html");
        System.out.println("  jfr2flame  input.jfr       output.html");
        System.out.println("  jfr2nflx   input.jfr       output.nflx");
    }
}
