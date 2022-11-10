#!/usr/bin/python3
"""
./profile_processor.py FILE
"""

import sys

with open(sys.argv[1], "r") as f:
    content = f.read()
content = content.replace("JATTACH=$SCRIPT_DIR/build/jattach", "")\
    .replace("PROFILER=$SCRIPT_DIR/build/libasyncProfiler.so", "")
with open(sys.argv[1], "w") as f:
    f.write(content)
