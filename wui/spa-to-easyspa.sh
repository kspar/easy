#!/usr/bin/env bash

d=$(date)
s="s+<div id=\"date\">.*</div>+<div id=\"date\">$d</div>+g"
#echo $s
sed -i "$s" spaproto.html

scp spaproto.html easyspa:/var/www/html/index.html
scp -r css/ easyspa:/var/www/html
scp build/kotlin-js-min/main/* easyspa:/var/www/html/js/

b="s+<div id=\"date\">.*</div>+<div id=\"date\"></div>+g"
sed -i "$b" spaproto.html
