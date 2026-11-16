#!/system/bin/sh
cd /data/local/tmp/mn; export LD_LIBRARY_PATH=.
M=$1; shift
./llama-completion-cpu -m $M -t 4 -c 4096 -n 800 --temp 0.2 --jinja -f full_prompt.txt -cnv -st "$@" 2>&1
