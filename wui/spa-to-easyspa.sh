#!/usr/bin/env bash

d=$(date)
s="s+<div id=\"date\">.*</div>+<div id=\"date\">$d</div>+g"
#echo $s
sed -i "$s" static/spaproto.html

scp static/spaproto.html easyspa:/var/www/html/index.html
scp build/kotlin-js-min/main/wui.js easyspa:/var/www/html/static/js/

#scp build/kotlin-js-min/main/* easyspa:/var/www/html/static/js/
#scp -r static/css/ easyspa:/var/www/html

b="s+<div id=\"date\">.*</div>+<div id=\"date\"></div>+g"
sed -i "$b" static/spaproto.html
