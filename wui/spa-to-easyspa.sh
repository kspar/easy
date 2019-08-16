#!/usr/bin/env bash

# Add page generation time to html
d=$(date)
s="s#<div id=\"date\">.*</div>#<div id=\"date\">$d</div>#g"
sed -i "$s" static/spaproto.html


# Only html
scp static/spaproto.html devgate:/var/www/wui/index.html

# Only main js file
#scp build/kotlin-js-min/main/wui.js devgate:/var/www/wui/static/js/

# All js files
scp build/kotlin-js-min/main/* devgate:/var/www/wui/static/js/

# All CSS files
scp -r static/css/ devgate:/var/www/wui/static


# Remove page generation time
b="s+<div id=\"date\">.*</div>+<div id=\"date\"></div>+g"
sed -i "$b" static/spaproto.html
