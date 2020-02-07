#!/usr/bin/env bash

# Add page generation time to html
d=$(date)
s="s#<div id=\"date\">.*</div>#<div id=\"date\">$d</div>#g"
sed -i "$s" static/spaproto.html


# Only html
scp static/spaproto.html devgate:/var/www/wui/index.html

# All CSS files
scp -r static/css/ devgate:/var/www/wui/static

# Only main js file
#scp build/kotlin-js-min/main/wui.js devgate:/var/www/wui/static/js/

# All js files
scp build/kotlin-js-min/main/{kotlin.js,kotlinx-coroutines-core.js,kotlinx-serialization-kotlinx-serialization-runtime.js,wui.js} devgate:/var/www/wui/static/js/

# All js maps
scp build/kotlin-js-min/main/{kotlin.js.map,kotlinx-coroutines-core.js.map,kotlinx-serialization-kotlinx-serialization-runtime.js.map,wui.js.map} devgate:/var/www/wui/static/js/


# Remove page generation time
b="s+<div id=\"date\">.*</div>+<div id=\"date\"></div>+g"
sed -i "$b" static/spaproto.html
