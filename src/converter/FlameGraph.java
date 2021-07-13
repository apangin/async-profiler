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

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class FlameGraph {
    public String title = "Flame Graph";
    public boolean reverse;
    public double minwidth;
    public int skip;
    public boolean diff;
    public String input1;
    public String input2;
    public String output;

    private final Frame root = new Frame();
    private int depth;
    private long mintotal;
    private double maxDiff;

    public FlameGraph(String... args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") && !arg.isEmpty()) {
                if (input1 == null) {
                    input1 = arg;
                } else if (diff && input2 == null) {
                    input2 = arg;
                } else {
                    output = arg;
                }
            } else if (arg.equals("--title")) {
                title = args[++i];
            } else if (arg.equals("--reverse")) {
                reverse = true;
            } else if (arg.equals("--diff")) {
                diff = true;
            } else if (arg.equals("--minwidth")) {
                minwidth = Double.parseDouble(args[++i]);
            } else if (arg.equals("--skip")) {
                skip = Integer.parseInt(args[++i]);
            }
        }
    }


    public void parse() throws IOException {
        if (diff) {
            parseDiff(new InputStreamReader(new FileInputStream(input1), StandardCharsets.UTF_8),
                new InputStreamReader(new FileInputStream(input2), StandardCharsets.UTF_8));
        } else {
            parse(new InputStreamReader(new FileInputStream(input1), StandardCharsets.UTF_8));
        }
    }

    public void parse(Reader in) throws IOException {
        try (BufferedReader br = new BufferedReader(in)) {
            for (String line; (line = br.readLine()) != null; ) {
                int space = line.lastIndexOf(' ');
                if (space <= 0) continue;

                String[] trace = line.substring(0, space).split(";");
                long ticks = Long.parseLong(line.substring(space + 1));
                addSample(trace, ticks, 0);
            }
        }
    }

    public void parseDiff(Reader in1, Reader in2) throws IOException {
        Map<String, long[]> data = new HashMap<String, long[]>();

        try (BufferedReader br1 = new BufferedReader(in1)) {
            for (String line; (line = br1.readLine()) != null; ) {
                int space = line.lastIndexOf(' ');
                if (space <= 0) continue;
                String trace = line.substring(0, space);
                long ticks = Long.parseLong(line.substring(space + 1));
                if (data.containsKey(trace)) {
                    data.put(trace, new long[] {data.get(trace)[0] + ticks, 0});
                } else {
                    data.put(trace, new long[] {ticks, 0});
                }
            }
        }
        try (BufferedReader br2 = new BufferedReader(in2)) {
            for (String line; (line = br2.readLine()) != null; ) {
                int space = line.lastIndexOf(' ');
                if (space <= 0) continue;
                String trace = line.substring(0, space);
                long ticks = Long.parseLong(line.substring(space + 1));
                if (data.containsKey(trace)) {
                    data.put(trace, new long[] {data.get(trace)[0], data.get(trace)[1] + ticks});
                } else {
                    data.put(trace, new long[] {0, ticks});
                }
            }
        }

        for (String trace : data.keySet()) {
            addSample(trace.split(";"), data.get(trace)[0], data.get(trace)[1]);
        }

        calculateDiff(root);
    }

    public void addSample(String[] trace, long ticks, long comparison) {
        Frame frame = root;
        if (reverse) {
            for (int i = trace.length; --i >= skip; ) {
                frame.total += ticks;
                frame = frame.child(trace[i]);
            }
        } else {
            for (int i = skip; i < trace.length; i++) {
                frame.total += ticks;
                frame.comparison += comparison;
                frame = frame.child(trace[i]);
            }
        }
        frame.total += ticks;
        frame.self += ticks;
        frame.comparison += comparison;

        depth = Math.max(depth, trace.length);
    }


    public void dump() throws IOException {
        if (output == null) {
            dump(System.out);
        } else {
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(output), 32768);
                 PrintStream out = new PrintStream(bos, false, "UTF-8")) {
                dump(out);
            }
        }
    }

    public void calculateDiff(Frame frame) {
        frame.diff = ((double) frame.total / (double) root.total) -  ((double) frame.comparison / (double) root.comparison);
        maxDiff = Math.max(Math.abs(frame.diff), maxDiff);

        for (Map.Entry<String, Frame> e : frame.entrySet()) {
            Frame child = e.getValue();
            calculateDiff(child);
        }
    }

    public void dump(PrintStream out) {
        out.print(applyReplacements(HEADER,
            "{title}", title,
            "{height}", (depth + 1) * 16,
            "{depth}", depth + 1,
            "{reverse}", reverse,
            "{diff}", diff,
            "{maxDiff}", Double.toString(maxDiff)));

        mintotal = (long) (root.total * minwidth / 100);
        printFrame(out, "all", root, 0, 0);

        out.print(FOOTER);
    }

    // Replace ${variables} in the given string with field values
    private String applyReplacements(String s, Object... params) {
        StringBuilder result = new StringBuilder(s.length() + 256);

        int p = 0;
        for (int q; (q = s.indexOf('$', p)) >= 0; ) {
            result.append(s, p, q);
            p = s.indexOf('}', q + 2) + 1;
            String var = s.substring(q + 1, p);
            for (int i = 0; i < params.length; i += 2) {
                if (var.equals(params[i])) {
                    result.append(params[i + 1]);
                    break;
                }
            }
        }

        result.append(s, p, s.length());
        return result.toString();
    }


    private void printFrame(PrintStream out, String title, Frame frame, int level, long x) {
        int type = frameType(title);
        title = stripSuffix(title);
        if (title.indexOf('\'') >= 0) {
            title = title.replace("'", "\\'");
        }

        out.println("f(" + level + "," + x + "," + frame.total + "," + type + "," + frame.diff + "," + frame.comparison + ",'" + title + "')");

        x += frame.self;

        for (Map.Entry<String, Frame> e : frame.entrySet()) {
            Frame child = e.getValue();
            if (child.total >= mintotal) {
                printFrame(out, e.getKey(), child, level + 1, x);
            }
            x += child.total;
        }
    }

    private String stripSuffix(String title) {
        int len = title.length();
        if (len >= 4 && title.charAt(len - 1) == ']' && title.regionMatches(len - 4, "_[", 0, 2)) {
            return title.substring(0, len - 4);
        }
        return title;
    }

    private int frameType(String title) {
        if (title.endsWith("_[j]")) {
            return 0;
        } else if (title.endsWith("_[i]")) {
            return 1;
        } else if (title.endsWith("_[k]")) {
            return 2;
        } else if (title.contains("::") || title.startsWith("-[") || title.startsWith("+[")) {
            return 3;
        } else if (title.indexOf('/') > 0 || title.indexOf('.') > 0 && Character.isUpperCase(title.charAt(0))) {
            return 0;
        } else {
            return 4;
        }
    }

    public static void main(String[] args) throws IOException {
        FlameGraph fg = new FlameGraph(args);
        if (fg.input1 == null | fg.diff && fg.input2 == null) {
            System.out.println("Usage: java " + FlameGraph.class.getName() + " [options] input1.collapsed input2.collapsed [output.html]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  --title TITLE");
            System.out.println("  --reverse");
            System.out.println("  --diff");
            System.out.println("  --minwidth PERCENT");
            System.out.println("  --skip FRAMES");
            System.exit(1);
        }

        fg.parse();
        fg.dump();
    }

    static class Frame extends TreeMap<String, Frame> {
        long total;
        long self;
        long comparison;
        double diff;

        Frame child(String title) {
            Frame child = get(title);
            if (child == null) {
                put(title, child = new Frame());
            }
            return child;
        }

    }

    private static final String HEADER = "<!DOCTYPE html>\n" +
            "<html lang='en'>\n" +
            "<head>\n" +
            "<meta charset='utf-8'>\n" +
            "<style>\n" +
            "\tbody {margin: 0; padding: 10px; background-color: #ffffff}\n" +
            "\th1 {margin: 5px 0 0 0; font-size: 18px; font-weight: normal; text-align: center}\n" +
            "\theader {margin: -24px 0 5px 0; line-height: 24px}\n" +
            "\tbutton {font: 12px sans-serif; cursor: pointer}\n" +
            "\tp {margin: 5px 0 5px 0}\n" +
            "\ta {color: #0366d6}\n" +
            "\t#hl {position: absolute; display: none; overflow: hidden; white-space: nowrap; pointer-events: none; background-color: #ffffe0; outline: 1px solid #ffc000; height: 15px}\n" +
            "\t#hl span {padding: 0 3px 0 3px}\n" +
            "\t#status {overflow: hidden; white-space: nowrap; position: fixed; background: white; right: 0; top: 0; padding: 10px;}\n" +
            "\t#match {overflow: hidden; white-space: nowrap; display: none; float: right; text-align: right}\n" +
            "\t#reset {cursor: pointer}\n" +
            "</style>\n" +
            "</head>\n" +
            "<body style='font: 12px Verdana, sans-serif'>\n" +
            "<h1>${title}</h1>\n" +
            "<header style='text-align: left'><button id='reverse' title='Reverse'>&#x1f53b;</button>&nbsp;&nbsp;<button id='search' title='Search'>&#x1f50d;</button></header>\n" +
            "<header style='text-align: right'>Produced by <a href='https://github.com/jvm-profiling-tools/async-profiler'>async-profiler</a></header>\n" +
            "<canvas id='canvas' style='width: 100%; height: ${height}px'></canvas>\n" +
            "<div id='hl'><span></span></div>\n" +
            "<p id='match'>Matched: <span id='matchval'></span> <span id='reset' title='Clear'>&#x274c;</span></p>\n" +
            "<p id='status'>&nbsp;</p>\n" +
            "<script>\n" +
            "\t// Copyright 2020 Andrei Pangin\n" +
            "\t// Licensed under the Apache License, Version 2.0.\n" +
            "\t'use strict';\n" +
            "\tvar root, rootLevel, px, pattern;\n" +
            "\tvar reverse = ${reverse};\n" +
            "\tconst levels = Array(${depth});\n" +
            "\tfor (let h = 0; h < levels.length; h++) {\n" +
            "\t\tlevels[h] = [];\n" +
            "\t}\n" +
            "\n" +
            "\tconst canvas = document.getElementById('canvas');\n" +
            "\tconst c = canvas.getContext('2d');\n" +
            "\tconst hl = document.getElementById('hl');\n" +
            "\tconst status = document.getElementById('status');\n" +
            "\n" +
            "\tconst canvasWidth = canvas.offsetWidth;\n" +
            "\tconst canvasHeight = canvas.offsetHeight;\n" +
            "\tcanvas.style.width = canvasWidth + 'px';\n" +
            "\tcanvas.width = canvasWidth * (devicePixelRatio || 1);\n" +
            "\tcanvas.height = canvasHeight * (devicePixelRatio || 1);\n" +
            "\tif (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);\n" +
            "\tc.font = document.body.style.font;\n" +
            "\n" +
            "\tconst palette = [\n" +
            "\t\t[0x50e150, 30, 30, 30],\n" +
            "\t\t[0x50bebe, 30, 30, 30],\n" +
            "\t\t[0xe17d00, 30, 30,  0],\n" +
            "\t\t[0xc8c83c, 30, 30, 10],\n" +
            "\t\t[0xe15a5a, 30, 40, 40],\n" +
            "\t];\n" +
            "\n" +
            "\tfunction getColor(p) {\n" +
            "\t\tconst v = Math.random();\n" +
            "\t\treturn '#' + (p[0] + ((p[1] * v) << 16 | (p[2] * v) << 8 | (p[3] * v))).toString(16);\n" +
            "\t}\n" +
            "\n" +
            "\tfunction getDiffColor(diff, comparison) {\n" +
            "\tif (comparison === 0) {\n" +
            "\t\treturn 'GoldenRod'\n" +
            "\t} else if (diff > 0) {\n" +
            "\t\treturn 'rgba(255, 0, 0, ' + diff / ${maxDiff} + ')'\n" +
            "\t} else if (diff < 0) {\n" +
            "\t\treturn 'rgba(0, 0, 255, ' + Math.abs(diff) / ${maxDiff} + ')'\n" +
            "\t} else {\n" +
            "\t\treturn 'LightGrey'}\n" +
            "\t}\n" +
            "\n" +
            "\tfunction f(level, left, width, type, diff, comparison, title) {\n" +
            "\t\tlevels[level].push({left: left, width: width, type: type, comparison: comparison, color: ${diff} ? getDiffColor(diff, comparison) : getColor(palette[type]), diff: diff, title: title});\n" +
            "\t}\n" +
            "\n" +
            "\tfunction samples(n) {\n" +
            "\t\treturn n === 1 ? '1 sample' : n.toString().replace(/\\B(?=(\\d{3})+(?!\\d))/g, ',') + ' samples';\n" +
            "\t}\n" +
            "\n" +
            "\tfunction pct(a, b) {\n" +
            "\t\treturn a >= b ? '100' : (100 * a / b).toFixed(2);\n" +
            "\t}\n" +
            "\n" +
            "\tfunction findFrame(frames, x) {\n" +
            "\t\tlet left = 0;\n" +
            "\t\tlet right = frames.length - 1;\n" +
            "\n" +
            "\t\twhile (left <= right) {\n" +
            "\t\t\tconst mid = (left + right) >>> 1;\n" +
            "\t\t\tconst f = frames[mid];\n" +
            "\n" +
            "\t\t\tif (f.left > x) {\n" +
            "\t\t\t\tright = mid - 1;\n" +
            "\t\t\t} else if (f.left + f.width <= x) {\n" +
            "\t\t\t\tleft = mid + 1;\n" +
            "\t\t\t} else {\n" +
            "\t\t\t\treturn f;\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\n" +
            "\t\tif (frames[left] && (frames[left].left - x) * px < 0.5) return frames[left];\n" +
            "\t\tif (frames[right] && (x - (frames[right].left + frames[right].width)) * px < 0.5) return frames[right];\n" +
            "\n" +
            "\t\treturn null;\n" +
            "\t}\n" +
            "\n" +
            "\tfunction search(r) {\n" +
            "\t\tif (r && (r = prompt('Enter regexp to search:', '')) === null) {\n" +
            "\t\t\treturn;\n" +
            "\t\t}\n" +
            "\n" +
            "\t\tpattern = r ? RegExp(r) : undefined;\n" +
            "\t\tconst matched = render(root, rootLevel);\n" +
            "\t\tdocument.getElementById('matchval').textContent = pct(matched, root.width) + '%';\n" +
            "\t\tdocument.getElementById('match').style.display = r ? 'inherit' : 'none';\n" +
            "\t}\n" +
            "\n" +
            "\tfunction render(newRoot, newLevel) {\n" +
            "\t\tif (root) {\n" +
            "\t\t\tc.fillStyle = '#ffffff';\n" +
            "\t\t\tc.fillRect(0, 0, canvasWidth, canvasHeight);\n" +
            "\t\t}\n" +
            "\n" +
            "\t\troot = newRoot || levels[0][0];\n" +
            "\t\trootLevel = newLevel || 0;\n" +
            "\t\tpx = canvasWidth / root.width;\n" +
            "\n" +
            "\t\tconst x0 = root.left;\n" +
            "\t\tconst x1 = x0 + root.width;\n" +
            "\t\tconst marked = [];\n" +
            "\n" +
            "\t\tfunction mark(f) {\n" +
            "\t\t\treturn marked[f.left] >= f.width || (marked[f.left] = f.width);\n" +
            "\t\t}\n" +
            "\n" +
            "\t\tfunction totalMarked() {\n" +
            "\t\t\tlet total = 0;\n" +
            "\t\t\tlet left = 0;\n" +
            "\t\t\tfor (let x in marked) {\n" +
            "\t\t\t\tif (+x >= left) {\n" +
            "\t\t\t\t\ttotal += marked[x];\n" +
            "\t\t\t\t\tleft = +x + marked[x];\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t\treturn total;\n" +
            "\t\t}\n" +
            "\n" +
            "\t\tfunction drawFrame(f, y, alpha) {\n" +
            "\t\t\tif (f.left < x1 && f.left + f.width > x0) {\n" +
            "\t\t\t\tc.fillStyle = pattern && f.title.match(pattern) && mark(f) ? '#ee00ee' : f.color;\n" +
            "\t\t\t\tc.fillRect((f.left - x0) * px, y, f.width * px, 15);\n" +
            "\n" +
            "\t\t\t\tif (f.width * px >= 21) {\n" +
            "\t\t\t\t\tconst chars = Math.floor(f.width * px / 7);\n" +
            "\t\t\t\t\tconst title = f.title.length <= chars ? f.title : f.title.substring(0, chars - 2) + '..';\n" +
            "\t\t\t\t\tc.fillStyle = '#000000';\n" +
            "\t\t\t\t\tc.fillText(title, Math.max(f.left - x0, 0) * px + 3, y + 12, f.width * px - 6);\n" +
            "\t\t\t\t}\n" +
            "\n" +
            "\t\t\t\tif (alpha) {\n" +
            "\t\t\t\t\tc.fillStyle = 'rgba(255, 255, 255, 0.5)';\n" +
            "\t\t\t\t\tc.fillRect((f.left - x0) * px, y, f.width * px, 15);\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\n" +
            "\t\tfor (let h = 0; h < levels.length; h++) {\n" +
            "\t\t\tconst y = reverse ? h * 16 : canvasHeight - (h + 1) * 16;\n" +
            "\t\t\tconst frames = levels[h];\n" +
            "\t\t\tfor (let i = 0; i < frames.length; i++) {\n" +
            "\t\t\t\tdrawFrame(frames[i], y, h < rootLevel);\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\n" +
            "\t\treturn totalMarked();\n" +
            "\t}\n" +
            "\n" +
            "\tcanvas.onmousemove = function() {\n" +
            "\t\tconst h = Math.floor((reverse ? event.offsetY : (canvasHeight - event.offsetY)) / 16);\n" +
            "\t\tif (h >= 0 && h < levels.length) {\n" +
            "\t\t\tconst f = findFrame(levels[h], event.offsetX / px + root.left);\n" +
            "\t\t\tif (f) {\n" +
            "\t\t\t\thl.style.left = (Math.max(f.left - root.left, 0) * px + canvas.offsetLeft) + 'px';\n" +
            "\t\t\t\thl.style.width = (Math.min(f.width, root.width) * px) + 'px';\n" +
            "\t\t\t\thl.style.top = ((reverse ? h * 16 : canvasHeight - (h + 1) * 16) + canvas.offsetTop) + 'px';\n" +
            "\t\t\t\thl.firstChild.textContent = f.title;\n" +
            "\t\t\t\thl.style.display = 'block';\n" +
            "\t\t\t\tcanvas.title = ${diff} ? f.title + '\\n(' + samples(f.width) + ' VS ' + f.comparison + ', DIFF = ' + f.diff + ')'\n" +
            "\t\t\t\t\t: f.title + '\\n(' + samples(f.width) + ', ' + pct(f.width, levels[0][0].width) + '%)';\n" +
            "\t\t\t\tcanvas.style.cursor = 'pointer';\n" +
            "\t\t\t\tcanvas.onclick = function() {\n" +
            "\t\t\t\t\tif (f != root) {\n" +
            "\t\t\t\t\t\trender(f, h);\n" +
            "\t\t\t\t\t\tcanvas.onmousemove();\n" +
            "\t\t\t\t\t}\n" +
            "\t\t\t\t};\n" +
            "\t\t\t\tstatus.textContent = 'Function: ' + canvas.title;\n" +
            "\t\t\t\treturn;\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t\tcanvas.onmouseout();\n" +
            "\t}\n" +
            "\n" +
            "\tcanvas.onmouseout = function() {\n" +
            "\t\thl.style.display = 'none';\n" +
            "\t\tstatus.textContent = '\\xa0';\n" +
            "\t\tcanvas.title = '';\n" +
            "\t\tcanvas.style.cursor = '';\n" +
            "\t\tcanvas.onclick = '';\n" +
            "\t}\n" +
            "\n" +
            "\tdocument.getElementById('reverse').onclick = function() {\n" +
            "\t\treverse = !reverse;\n" +
            "\t\trender();\n" +
            "\t}\n" +
            "\n" +
            "\tdocument.getElementById('search').onclick = function() {\n" +
            "\t\tsearch(true);\n" +
            "\t}\n" +
            "\n" +
            "\tdocument.getElementById('reset').onclick = function() {\n" +
            "\t\tsearch(false);\n" +
            "\t}\n" +
            "\n" +
            "\twindow.onkeydown = function() {\n" +
            "\t\tif (event.ctrlKey && event.keyCode === 70) {\n" +
            "\t\t\tevent.preventDefault();\n" +
            "\t\t\tsearch(true);\n" +
            "\t\t} else if (event.keyCode === 27) {\n" +
            "\t\t\tsearch(false);\n" +
            "\t\t}\n" +
            "\t}\n";

    private static final String FOOTER = "render();\n" +
            "</script></body></html>\n";
}
